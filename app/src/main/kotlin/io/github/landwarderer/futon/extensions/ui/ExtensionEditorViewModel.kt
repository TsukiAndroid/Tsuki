package io.github.landwarderer.futon.extensions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dokar.quickjs.QuickJs
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.extensions.data.ExtensionRepository
import io.github.landwarderer.futon.extensions.domain.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
     * Runs the given [functionName] from [sourceCode] inside a disposable QuickJS
     * sandbox and emits the result (or error) via [testState].
     *
     * @param functionName  One of "getMangaList", "getMangaDetails", "getChapterPages"
     * @param arg           Raw user input: offset number for List, URL string for others.
     */
    fun runTest(sourceCode: String, functionName: String, arg: String) {
        _testState.value = TestState.Running
        viewModelScope.launch {
            val safeArg = arg.trim()
            val call = when (functionName) {
                "getMangaList" -> {
                    val offset = safeArg.toIntOrNull() ?: 0
                    "getMangaList($offset, null);"
                }
                "getMangaDetails" -> {
                    val escaped = safeArg.replace("\\", "\\\\").replace("\"", "\\\"")
                    "getMangaDetails(\"$escaped\");"
                }
                "getChapterPages" -> {
                    val escaped = safeArg.replace("\\", "\\\\").replace("\"", "\\\"")
                    "getChapterPages(\"$escaped\");"
                }
                else -> "undefined;"
            }
            val script = "$sourceCode\n$call"
            _testState.value = withContext(Dispatchers.Default) {
                runCatching {
                    QuickJs.create(jobDispatcher = Dispatchers.Default).use { qjs ->
                        qjs.maxStackSize = 1L shl 20
                        qjs.memoryLimit = 64L shl 20
                        val raw = qjs.evaluate<Any?>(script)?.toString() ?: "null"
                        // Pretty-print JSON if possible, otherwise return raw
                        val pretty = tryPrettyPrintJson(raw)
                        TestState.Success(pretty)
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
