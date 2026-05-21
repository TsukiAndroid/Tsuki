package io.github.landwarderer.futon.customsource.ui

  import androidx.lifecycle.ViewModel
  import dagger.hilt.android.lifecycle.HiltViewModel
  import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
  import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
  import io.github.landwarderer.futon.customsource.domain.CustomSource
  import io.github.landwarderer.futon.customsource.domain.CustomSourceType
  import io.github.landwarderer.futon.customsource.domain.ParserTemplate
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import org.json.JSONObject
  import javax.inject.Inject

  /**
   * ViewModel for [UniversalSourceActivity].
   *
   * Converts 11 form fields into a valid [ParserTemplate] JSON, saves the
   * template, then registers a [CustomSource] with type
   * [CustomSourceType.CUSTOM_TEMPLATE]. The existing [TemplateHtmlParser]
   * handles all actual scraping — this class only wires the data layer.
   */
  @HiltViewModel
  class UniversalSourceViewModel @Inject constructor(
      private val parserTemplateRepository: ParserTemplateRepository,
      private val customSourcesRepository: CustomSourcesRepository,
  ) : ViewModel() {

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
      ): String {
          val root = JSONObject()
          root.put("name", name)
          root.put("version", "1.0")
          root.put("type", "html")

          val mangaList = JSONObject()
          mangaList.put("endpoint", listPath)
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
  