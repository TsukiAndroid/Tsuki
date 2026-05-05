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
  import io.github.landwarderer.futon.core.parser.KotatsuParserMatcher
  import io.github.landwarderer.futon.customsource.data.CmsTypeDetector
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
      private val kotatsuParserMatcher: KotatsuParserMatcher,
  ) : ViewModel() {

      val sources: StateFlow<List<CustomSource>> = repository.sources

      private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
      val uiState: StateFlow<UiState> = _uiState.asStateFlow()

      /** Look up a saved source by its id (used to pre-fill the edit sheet). */
      fun findById(id: Long): CustomSource? = repository.findById(id)

      /** Add a source with an already-known [type]. */
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

      /**
       * Auto-detects the CMS type of [url] and saves a new source with that type.
       *
       * Detection order:
       *  1. Check [KotatsuParserMatcher] — if the domain matches a built-in parser,
       *     save as [CustomSourceType.KOTATSU_PARSER] so the factory routes it to
       *     [ParserMangaRepository] giving full inbuilt-source quality.
       *  2. Fall back to [CmsTypeDetector] HTML fingerprinting.
       *
       * Emits [UiState.Detecting] while the probe is in flight.
       */
      fun detectAndAddSource(name: String, url: String, description: String) {
          viewModelScope.launch {
              val normalized = normalizeUrl(url)
              if (normalized == null) {
                  _uiState.value = UiState.Error("Please enter a valid website URL (e.g. example.com)")
                  return@launch
              }
              _uiState.value = UiState.Detecting

              // Step 1: check if a Kotatsu parser already covers this domain
              val matchedParser = withContext(Dispatchers.IO) {
                  runCatching { kotatsuParserMatcher.findForUrl(normalized) }.getOrNull()
              }

              val (detectedType, parserSourceName) = if (matchedParser != null) {
                  CustomSourceType.KOTATSU_PARSER to matchedParser.name
              } else {
                  // Step 2: fall back to HTML fingerprinting
                  val cms = withContext(Dispatchers.IO) {
                      runCatching { CmsTypeDetector.detect(normalized) }.getOrElse { CustomSourceType.WEBVIEW }
                  }
                  cms to null
              }

              val source = CustomSource(
                  id = CustomSourcesRepository.generateId(),
                  // Always use what the user typed; fall back to the site hostname.
                  // Never inject the parser's internal title — the user's chosen name
                  // is what appears everywhere in the app.
                  name = name.trim().ifBlank { hostFromUrl(normalized) ?: normalized },
                  baseUrl = normalized,
                  type = detectedType,
                  parserSourceName = parserSourceName,
                  description = description.trim().takeIf { it.isNotEmpty() },
              )
              repository.add(source)
              _uiState.value = UiState.SourceAdded(source, detectedType)
              fetchAndStoreFavicon(source)
          }
      }

      /**
       * Save edits to an existing source.
       * Preserves [createdAt] and [iconUrl]; re-fetches the favicon if the URL changed.
       */
      fun updateSource(
          id: Long,
          name: String,
          url: String,
          type: CustomSourceType,
          description: String,
      ) {
          viewModelScope.launch {
              val existing = repository.findById(id) ?: run {
                  _uiState.value = UiState.Error("Source not found")
                  return@launch
              }
              val normalized = normalizeUrl(url)
              if (normalized == null) {
                  _uiState.value = UiState.Error("Please enter a valid website URL (e.g. example.com)")
                  return@launch
              }
              val updated = existing.copy(
                  name = name.trim().ifBlank { hostFromUrl(normalized) ?: normalized },
                  baseUrl = normalized,
                  type = type,
                  description = description.trim().takeIf { it.isNotEmpty() },
              )
              repository.update(updated)
              _uiState.value = UiState.SourceUpdated(updated)
              // Refresh favicon only when the URL changed
              if (normalized != existing.baseUrl) fetchAndStoreFavicon(updated)
          }
      }

      /**
       * Probes [url] in the background and calls [onDetected] on the main thread
       * with the result. Emits [UiState.Detecting] while the probe runs.
       * Used by the edit sheet's "Re-detect" button so the dropdown updates without
       * saving the source yet.
       */
      fun redetectType(sourceId: Long, url: String, onDetected: (CustomSourceType) -> Unit) {
          viewModelScope.launch {
              val normalized = normalizeUrl(url)
              if (normalized == null) {
                  _uiState.value = UiState.Error("Please enter a valid website URL (e.g. example.com)")
                  return@launch
              }
              _uiState.value = UiState.Detecting
              val detected = withContext(Dispatchers.IO) {
                  runCatching { CmsTypeDetector.detect(normalized) }.getOrElse { CustomSourceType.WEBVIEW }
              }
              _uiState.value = UiState.Idle
              onDetected(detected)
          }
      }

      fun removeSource(id: Long) {
          viewModelScope.launch { repository.remove(id) }
      }

      fun resetState() {
          _uiState.value = UiState.Idle
      }

      fun exportSourcesJson(): String = repository.exportJson()

      fun importSourcesJson(json: String): Int = repository.importJson(json)

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
          object Detecting : UiState()
          data class Error(val message: String) : UiState()
          data class SourceAdded(val source: CustomSource, val detectedType: CustomSourceType? = null) : UiState()
          data class SourceUpdated(val source: CustomSource, val detectedType: CustomSourceType? = null) : UiState()
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
  