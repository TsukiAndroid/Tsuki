package io.github.landwarderer.futon.extensions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dokar.quickjs.QuickJs
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.core.network.MangaHttpClient
import io.github.landwarderer.futon.extensions.data.ExtensionRepository
import io.github.landwarderer.futon.extensions.domain.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val output: String) : TestState
    data class Failure(val message: String) : TestState
}

@HiltViewModel
class ExtensionEditorViewModel @Inject constructor(
    private val extensionRepository: ExtensionRepository,
    @MangaHttpClient private val okHttpClient: OkHttpClient,
) : ViewModel() {

    private val _extension = MutableStateFlow<Extension?>(null)
    val extension: StateFlow<Extension?> = _extension.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    fun load(id: String) {
        _extension.value = extensionRepository.findById(id)
    }

    fun saveCode(id: String, newCode: String) {
        viewModelScope.launch {
            val ext = extensionRepository.findById(id) ?: return@launch
            extensionRepository.save(ext.copy(sourceCode = newCode))
            _saved.value = true
        }
    }

    fun clearSaved() {
        _saved.value = false
    }

    /**
     * Runs the given [functionName] from [sourceCode].
     *
     * The runner first resolves the target URL (using `getMangaListUrl` for List,
     * or using [arg] directly for Details/Pages), fetches the page HTML via OkHttp,
     * then calls the pure-parse JS function with the HTML.
     *
     * @param functionName  "getMangaList" | "getMangaDetails" | "getChapterPages"
     * @param arg           Offset for List (numeric string), full URL for Details/Pages.
     */
    fun runTest(sourceCode: String, functionName: String, arg: String) {
        _testState.value = TestState.Running
        viewModelScope.launch {
            _testState.value = withContext(Dispatchers.IO) {
                runCatching {
                    when (functionName) {
                        "getMangaList" -> runListTest(sourceCode, arg)
                        "getMangaDetails" -> runDetailsTest(sourceCode, arg)
                        "getChapterPages" -> runPagesTest(sourceCode, arg)
                        else -> TestState.Failure("Unknown function: $functionName")
                    }
                }.getOrElse { e ->
                    TestState.Failure(e.message ?: "Unknown error")
                }
            }
        }
    }

    fun clearTestState() {
        _testState.value = TestState.Idle
    }

    // ─── Per-function test runners ───────────────────────────────────────────

    private suspend fun runListTest(sourceCode: String, arg: String): TestState {
        val offset = arg.trim().toIntOrNull() ?: 0

        // 1. Resolve URL via JS (getMangaListUrl)
        val urlScript = "$sourceCode\n" +
            "(typeof getMangaListUrl === 'function') ? getMangaListUrl($offset, null) : null;"
        val resolvedUrl = evalJs(urlScript)?.trim('"')
        if (resolvedUrl.isNullOrEmpty()) {
            return TestState.Failure("getMangaListUrl returned nothing. " +
                "Make sure your extension defines getMangaListUrl(offset, query).")
        }
        if (!resolvedUrl.startsWith("http")) {
            return TestState.Failure("getMangaListUrl returned an invalid URL: $resolvedUrl")
        }

        // 2. Fetch HTML
        val html = fetchHtml(resolvedUrl)
            ?: return TestState.Failure("HTTP GET failed for: $resolvedUrl")

        // 3. Parse with JS
        val htmlArg = JSONObject.quote(html)
        val parseScript = "$sourceCode\ngetMangaList($htmlArg, $offset, null);"
        val raw = evalJs(parseScript) ?: return TestState.Failure("getMangaList returned null")
        return TestState.Success("[URL] $resolvedUrl\n\n" + tryPrettyPrintJson(raw))
    }

    private suspend fun runDetailsTest(sourceCode: String, arg: String): TestState {
        val url = arg.trim()
        if (!url.startsWith("http")) {
            return TestState.Failure("Please enter a full URL (starting with https://).")
        }
        val html = fetchHtml(url)
            ?: return TestState.Failure("HTTP GET failed for: $url")

        val htmlArg = JSONObject.quote(html)
        val escaped = url.replace("\\", "\\\\").replace("\"", "\\\"")
        val script = "$sourceCode\ngetMangaDetails($htmlArg, \"$escaped\");"
        val raw = evalJs(script) ?: return TestState.Failure("getMangaDetails returned null")
        return TestState.Success(tryPrettyPrintJson(raw))
    }

    private suspend fun runPagesTest(sourceCode: String, arg: String): TestState {
        val url = arg.trim()
        if (!url.startsWith("http")) {
            return TestState.Failure("Please enter a full chapter URL (starting with https://).")
        }
        val html = fetchHtml(url)
            ?: return TestState.Failure("HTTP GET failed for: $url")

        val htmlArg = JSONObject.quote(html)
        val escaped = url.replace("\\", "\\\\").replace("\"", "\\\"")
        val script = "$sourceCode\ngetChapterPages($htmlArg, \"$escaped\");"
        val raw = evalJs(script) ?: return TestState.Failure("getChapterPages returned null")
        return TestState.Success(tryPrettyPrintJson(raw))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun fetchHtml(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    private suspend fun evalJs(script: String): String? =
        withContext(Dispatchers.Default) {
            QuickJs.create(jobDispatcher = Dispatchers.Default).use { qjs ->
                qjs.maxStackSize = 1L shl 20
                qjs.memoryLimit = 64L shl 20
                qjs.evaluate<Any?>(script)?.toString()
            }
        }

    private fun tryPrettyPrintJson(raw: String): String = runCatching {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return raw
        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var escape = false
        for (ch in trimmed) {
            when {
                escape -> { sb.append(ch); escape = false }
                ch == '\\' && inString -> { sb.append(ch); escape = true }
                ch == '"' -> { sb.append(ch); inString = !inString }
                inString -> sb.append(ch)
                ch == '{' || ch == '[' -> {
                    sb.append(ch).append('\n')
                    indent++
                    repeat(indent) { sb.append("  ") }
                }
                ch == '}' || ch == ']' -> {
                    sb.append('\n')
                    indent--
                    repeat(indent) { sb.append("  ") }
                    sb.append(ch)
                }
                ch == ',' -> {
                    sb.append(ch).append('\n')
                    repeat(indent) { sb.append("  ") }
                }
                ch == ':' -> sb.append(": ")
                ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' -> Unit
                else -> sb.append(ch)
            }
        }
        sb.toString()
    }.getOrDefault(raw)
}
