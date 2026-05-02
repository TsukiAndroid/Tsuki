package io.github.landwarderer.futon.customsource.ui

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class CustomSourceViewModel @Inject constructor(
    private val repository: CustomSourcesRepository,
) : ViewModel() {

    val sources: StateFlow<List<CustomSource>> = repository.sources

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun addSource(name: String, url: String, type: CustomSourceType, description: String) {
        viewModelScope.launch {
            val normalized = normalizeUrl(url)
            if (normalized == null) {
                _uiState.value = UiState.Error("Please enter a valid website URL (e.g. example.com)")
                return@launch
            }
            val source = CustomSource(
                id = CustomSourcesRepository.generateId(),
                name = name.trim().ifBlank { hostFromUrl(normalized) ?: normalized },
                baseUrl = normalized,
                type = type,
                description = description.trim().takeIf { it.isNotEmpty() },
            )
            repository.add(source)
            _uiState.value = UiState.SourceAdded(source)
            fetchAndStoreFavicon(source)
        }
    }

    fun removeSource(id: Long) {
        viewModelScope.launch {
            repository.remove(id)
        }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }

    /**
     * Accept input with or without scheme and add https:// when needed.
     * Returns the cleaned URL or null if it isn't a recognisable web URL.
     */
    private fun normalizeUrl(raw: String): String? {
        var trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed = "https://$trimmed"
        }
        return if (Patterns.WEB_URL.matcher(trimmed).matches()) trimmed else null
    }

    private fun hostFromUrl(url: String): String? = runCatching {
        URI(url).host?.removePrefix("www.")
    }.getOrNull()

    /**
     * Best-effort favicon resolver. Uses Google's favicon service because it
     * abstracts away site-specific quirks (some sites only ship apple-touch
     * icons or rely on rel=manifest), and falls back to /favicon.ico if the
     * service is unreachable. Failures are silent — sources still work without
     * an icon.
     */
    private suspend fun fetchAndStoreFavicon(source: CustomSource) {
        val host = hostFromUrl(source.baseUrl) ?: return
        val candidate = withContext(Dispatchers.IO) {
            runCatching {
                val gUrl = "https://www.google.com/s2/favicons?domain=$host&sz=128"
                val req = Request.Builder().url(gUrl).get().build()
                val resp = httpClient.newCall(req).execute()
                resp.use {
                    if (it.isSuccessful && (it.body?.contentLength() ?: 0L) > 200L) {
                        return@withContext gUrl
                    }
                }
                "https://$host/favicon.ico"
            }.getOrNull()
        } ?: return
        repository.update(source.copy(iconUrl = candidate))
    }

    sealed class UiState {
        object Idle : UiState()
        data class Error(val message: String) : UiState()
        data class SourceAdded(val source: CustomSource) : UiState()
    }

    companion object {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
