package io.github.landwarderer.futon.customsource.ui

  import androidx.lifecycle.ViewModel
  import androidx.lifecycle.viewModelScope
  import dagger.hilt.android.lifecycle.HiltViewModel
  import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
  import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
  import io.github.landwarderer.futon.customsource.data.SiteAutoDetector
  import io.github.landwarderer.futon.customsource.domain.CustomSource
  import io.github.landwarderer.futon.customsource.domain.CustomSourceType
  import io.github.landwarderer.futon.customsource.domain.ParserTemplate
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.launch
  import org.json.JSONObject
  import javax.inject.Inject

  /**
   * ViewModel for [UniversalSourceActivity].
   *
   * Auto-detect: [autoDetect] fetches the target site's HTML via [SiteAutoDetector]
   * and emits pre-filled form values as [AutoDetectState.Done].
   *
   * Create: [create] converts 11 form fields into a [ParserTemplate] JSON, saves
   * it, and registers a [CustomSource] with type [CustomSourceType.CUSTOM_TEMPLATE].
   * The existing [TemplateHtmlParser] handles all actual scraping.
   */
  @HiltViewModel
  class UniversalSourceViewModel @Inject constructor(
      private val parserTemplateRepository: ParserTemplateRepository,
      private val customSourcesRepository: CustomSourcesRepository,
  ) : ViewModel() {

      // ── Create result ─────────────────────────────────────────────────────────

      sealed interface Result {
          object Idle : Result
          data class Error(val message: String) : Result
          data class Success(val name: String) : Result
      }

      private val _result = MutableStateFlow<Result>(Result.Idle)
      val result: StateFlow<Result> = _result.asStateFlow()

      fun resetResult() {
          _result.value = Result.Idle
      }

      // ── Auto-detect state ─────────────────────────────────────────────────────

      sealed interface AutoDetectState {
          object Idle : AutoDetectState
          object Loading : AutoDetectState
          data class Done(val fields: SiteAutoDetector.DetectedFields) : AutoDetectState
          data class Error(val message: String) : AutoDetectState
      }

      private val _autoDetectState = MutableStateFlow<AutoDetectState>(AutoDetectState.Idle)
      val autoDetectState: StateFlow<AutoDetectState> = _autoDetectState.asStateFlow()

      // Stores the pagination type detected during auto-detect so it can be written
      // to the template JSON when the user taps Create — without requiring a UI field.
      private var lastDetectedPaginationType: String = "page"

      /**
       * Fetches [url]'s HTML, runs CSS-selector heuristics, and emits
       * [AutoDetectState.Done] with pre-filled form values on success.
       */
      fun autoDetect(url: String) {
          val trimUrl = url.trim()
          if (trimUrl.isEmpty() ||
              (!trimUrl.startsWith("http://") && !trimUrl.startsWith("https://"))
          ) {
              _autoDetectState.value = AutoDetectState.Error(
                  "Enter the site URL (starting with https://) before auto-detecting."
              )
              return
          }
          viewModelScope.launch {
              _autoDetectState.value = AutoDetectState.Loading
              runCatching { SiteAutoDetector().detect(trimUrl) }
                  .onSuccess { fields ->
                      lastDetectedPaginationType = fields.paginationType
                      _autoDetectState.value = AutoDetectState.Done(fields)
                  }
                  .onFailure { e ->
                      _autoDetectState.value = AutoDetectState.Error(
                          e.message ?: "Detection failed — please fill in selectors manually."
                      )
                  }
          }
      }

      fun resetAutoDetect() {
          _autoDetectState.value = AutoDetectState.Idle
      }

      // ── Create source ─────────────────────────────────────────────────────────

      fun create(
          name: String,
          baseUrl: String,
          listPath: String,
          searchPath: String,
          cardSelector: String,
          titleSelector: String,
          coverSelector: String,
          detailTitle: String,
          description: String,
          chapterSelector: String,
          pageImageSelector: String,
      ) {
          val trimName = name.trim()
          val trimUrl  = baseUrl.trim().trimEnd('/')

          if (trimName.isEmpty()) {
              _result.value = Result.Error("Site name is required.")
              return
          }
          if (trimUrl.isEmpty() || (!trimUrl.startsWith("http://") && !trimUrl.startsWith("https://"))) {
              _result.value = Result.Error("Base URL must start with https://")
              return
          }
          val trimPageImg = pageImageSelector.trim()
          if (trimPageImg.isEmpty()) {
              _result.value = Result.Error("Page image selector is required — it tells the app where chapter images are.")
              return
          }

          val rawJson = buildJson(
              name          = trimName,
              listPath      = listPath.trim().ifEmpty { "/" },
              searchPath    = searchPath.trim(),
              cardSelector  = cardSelector.trim(),
              titleSelector = titleSelector.trim(),
              coverSelector = coverSelector.trim(),
              detailTitle   = detailTitle.trim(),
              description   = description.trim(),
              chapterSel    = chapterSelector.trim(),
              pageImageSel  = trimPageImg,
              paginationType = lastDetectedPaginationType,
          )

          val timestamp = System.currentTimeMillis()
          parserTemplateRepository.add(
              ParserTemplate(
                  id        = timestamp,
                  name      = trimName,
                  version   = "1.0",
                  type      = "html",
                  rawJson   = rawJson,
                  isEnabled = true,
              ),
          )
          customSourcesRepository.add(
              CustomSource(
                  id               = timestamp + 1L,
                  name             = trimName,
                  baseUrl          = trimUrl,
                  type             = CustomSourceType.CUSTOM_TEMPLATE,
                  parserSourceName = trimName,
                  isEnabled        = true,
              ),
          )
          _result.value = Result.Success(trimName)
      }

      private fun buildJson(
          name: String,
          listPath: String,
          searchPath: String,
          cardSelector: String,
          titleSelector: String,
          coverSelector: String,
          detailTitle: String,
          description: String,
          chapterSel: String,
          pageImageSel: String,
          paginationType: String = "page",
      ): String {
          val root = JSONObject()
          root.put("name", name)
          root.put("version", "1.0")
          root.put("type", "html")

          // Infer pagination type: any slug-only path (e.g. /manhwa/, /manga/) on a
          // WordPress site uses /page/N/ path pagination, not ?page=N query params.
          // Explicit paginationType from auto-detect takes precedence; otherwise we
          // fall back to heuristic: path looks like a WordPress archive slug.
          val resolvedPagination = when {
              paginationType != "page" -> paginationType
              listPath.trim('/').isNotEmpty() && !listPath.contains('?') &&
                  listPath.trim('/').none { it == '/' } -> "path"
              else -> "page"
          }

          val mangaList = JSONObject()
          mangaList.put("endpoint", listPath)
          mangaList.put("pagination", resolvedPagination)
          mangaList.put("pageParam", "page")
          if (cardSelector.isNotEmpty())  mangaList.put("itemSelector",  cardSelector)
          if (titleSelector.isNotEmpty()) mangaList.put("titleSelector", titleSelector)
          if (coverSelector.isNotEmpty()) mangaList.put("coverSelector", coverSelector)
          if (searchPath.isNotEmpty()) {
              val rawParam  = if (searchPath.contains("?")) searchPath.substringAfterLast("?") else ""
              val paramName = if (rawParam.contains("=")) rawParam.substringBefore("=") else "s"
              val endpoint  = searchPath.substringBefore("?").ifEmpty { searchPath }
              mangaList.put("searchEndpoint", endpoint)
              mangaList.put("searchParam", paramName)
          }
          root.put("mangaList", mangaList)

          val mangaDetail = JSONObject()
          if (detailTitle.isNotEmpty()) mangaDetail.put("titleSelector",       detailTitle)
          if (description.isNotEmpty()) mangaDetail.put("descriptionSelector", description)
          if (coverSelector.isNotEmpty()) mangaDetail.put("coverSelector",     coverSelector)
          root.put("mangaDetail", mangaDetail)

          val chapterList = JSONObject()
          if (chapterSel.isNotEmpty()) chapterList.put("selector", chapterSel)
          chapterList.put("titleSelector", "a")
          chapterList.put("linkSelector",  "a")
          root.put("chapterList", chapterList)

          val pageList = JSONObject()
          pageList.put("imageSelector", pageImageSel)
          root.put("pageList", pageList)

          return root.toString(2)
      }
  }
  