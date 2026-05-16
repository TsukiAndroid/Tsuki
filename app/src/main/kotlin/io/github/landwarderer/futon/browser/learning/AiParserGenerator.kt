package io.github.landwarderer.futon.browser.learning

  import android.util.Log
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import org.json.JSONArray
  import org.json.JSONException
  import org.json.JSONObject
  import java.net.URI

  /**
   * Generates a ParserTemplate-compatible JSON from captured page HTML.
   * Uses Gemini API when an API key is available; falls back to heuristics.
   */
  class AiParserGenerator {

      suspend fun generate(
          session: LearningSession,
          geminiApiKey: String?,
      ): String = withContext(Dispatchers.IO) {
          if (!geminiApiKey.isNullOrBlank()) {
              runCatching { generateWithGemini(session, geminiApiKey) }
                  .onFailure { e -> Log.w(TAG, "Gemini failed, falling back to heuristics", e) }
                  .getOrNull()
                  ?.takeIf { isValidParserJson(it) }
          } else null
      } ?: generateHeuristic(session)

      // ── Gemini ────────────────────────────────────────────────────────────────

      private suspend fun generateWithGemini(
          session: LearningSession,
          apiKey: String,
      ): String = withContext(Dispatchers.IO) {
          val prompt = buildGeminiPrompt(session)
          val requestBody = JSONObject().apply {
              put("contents", JSONArray().apply {
                  put(JSONObject().apply {
                      put("parts", JSONArray().apply {
                          put(JSONObject().apply { put("text", prompt) })
                      })
                  })
              })
              put("generationConfig", JSONObject().apply {
                  put("temperature", 0.05)
                  put("maxOutputTokens", 8192)
                  put("responseMimeType", "application/json")
              })
              put("systemInstruction", JSONObject().apply {
                  put("parts", JSONArray().apply {
                      put(JSONObject().apply {
                          put("text", SYSTEM_INSTRUCTION)
                      })
                  })
              })
          }

          val url = java.net.URL(GEMINI_URL + apiKey)
          val connection = url.openConnection() as java.net.HttpURLConnection
          connection.requestMethod = "POST"
          connection.setRequestProperty("Content-Type", "application/json")
          connection.doOutput = true
          connection.connectTimeout = 30_000
          connection.readTimeout = 90_000

          connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

          if (connection.responseCode != 200) {
              val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
              error("Gemini API error ${connection.responseCode}: $err")
          }

          val responseText = connection.inputStream.bufferedReader().readText()
          val response = JSONObject(responseText)
          val candidates = response.getJSONArray("candidates")
          val content = candidates.getJSONObject(0).getJSONObject("content")
          val parts = content.getJSONArray("parts")
          val raw = parts.getJSONObject(0).getString("text").trim()
          // Strip markdown code fences if Gemini wraps despite responseMimeType
          raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
      }

      private fun buildGeminiPrompt(session: LearningSession): String {
          val sb = StringBuilder()

          sb.appendLine("""
  TASK: Analyse the HTML from a manga website and produce an accurate parser configuration.

  SITE DOMAIN: ${session.domain}

  ═══════════════════════════════════════════════════════════════
  STEP 1 — IDENTIFY CMS TYPE
  ═══════════════════════════════════════════════════════════════
  Scan the HTML for these STRUCTURAL SIGNALS and set "suggestedBuiltinParser" accordingly.
  Only match if the signal is ACTUALLY PRESENT in the provided HTML — do not guess.

  | Parser key (use exactly) | Structural signals to look for in HTML |
  |--------------------------|----------------------------------------|
  | MADARA        | "wp-manga", "WpMangaReader", "madara", "mangomic-core", "summary_image", "tab-summary", "wp-manga-chapter" |
  | MANGATHEMESIA | "ts_reader.run", ".bsx", "mangathemesia", "anilist-button" |
  | MANGASTREAM   | "WPMangaStream", "readerarea", "eph-num", "chapimage" |
  | MANGASEE      | "vm.Directory", "vm.Chapters", "vm.CurChapter" |
  | COMIXTO       | "list-story-item", "lstImages", "chapImages", ".story-item" alongside comic/manga list |
  | MANGANELO     | "manganelo", "mangakakalot", "story_item", "chapmanganelo" |
  | MANGAFIRE     | "manga-poster", "chapter-images" together |
  | KEYOAPP       | "series_tags_page", "#chapters > a", "series-card" |
  | LHTRANSLATION | "row-content-chapter", "reading-detail", "lhtranslation" |
  | GENKAN        | "/comics/", "genkan" |
  | MANGAHERE     | "manga-list", "detail-main-list" (without wp-manga) |
  | MANGAHUB      | "media-heading", "manga-page", "chapter-table" |
  | MANGAPILL     | "js-page", "data-src" on chapter images |
  | MANGAGO       | "book_list", "booklist_item" |
  | MANGAFREAK    | "manga_search_item", "reader_images" |
  | MANGAOWL      | "comic-item", "story-chapter-item" |
  | TCBSCANS      | "entry-img", "latest-chapter" |
  | KISSMANGA     | "lstImagesUrl", "barContent", "listing" |
  | NETTRUYEN     | "ModuleContent", "reading-detail", "truyen-tranh" |
  | TRUYENQQ      | "book_avatar", "listChapters", ".html" |
  | MANGAKATANA   | "chapter-img", "id=\"chapters\"" |
  | MANGABOX      | "content-genres-item", "manga-list?type=" |
  | MADTHEME      | "book-item", "score", "/search/" |
  | MMRCMS        | "filterList", "media-body" |
  | ZEISTMANGA    | "feeds/posts/default/-/" atom feed |
  | WPCOMICS      | "tim-truyen", "div.items", "box_tooti" |
  | SCAN          | "chapter-list" with /manga (no wp-manga) |
  | MANGAREADER   | "manga-poster", "sort-name", "manga-detail" together |
  | NINEMANGA     | "detail_list", "manga_detail", "page_select" |
  | MANGAHOST     | "manga-card", "kw-title" |
  | MANGAFOX      | "detail-info-right", "detail-main-list" |

  If none match: set "suggestedBuiltinParser" to null.

  ═══════════════════════════════════════════════════════════════
  STEP 2 — EXTRACT PRECISE CSS SELECTORS
  ═══════════════════════════════════════════════════════════════
  Look at the ACTUAL class names and IDs in the HTML. Find:

  A) MANGA LIST PAGE: What HTML element repeats once per manga?
     - Find the wrapper div/article/li that contains: title, cover image, link
     - Note the EXACT class name (e.g. ".manga-card", ".book-item", "article.manga")
     - Find the title element's selector (h3, h2, .title, etc.)
     - Find the cover img selector

  B) MANGA DETAIL PAGE: The full info page for one manga
     - Title: h1, .post-title h1, .manga-title, etc.
     - Cover image: .summary_image img, .manga-poster img, .book-thumbnail img, etc.
     - Description: .summary__content, .description-summary, .entry-content p, etc.
     - Chapter list wrapper: .wp-manga-chapter, .chapter-list a, #chapters a, ul.chapters li, etc.
     - Chapter link + title + date selectors

  C) CHAPTER READER PAGE: Where are the page images?
     - Find ALL img tags in the reading area
     - Check if images use src, data-src, data-lazy-src, or data-original
     - Find the container selector (not just "img" globally — find the specific reading div)

  ═══════════════════════════════════════════════════════════════
  STEP 3 — CRITICAL VALIDATION RULES
  ═══════════════════════════════════════════════════════════════
  Before outputting each selector:
  ✓ The selector string must appear (or be derivable) from the HTML below
  ✓ Do NOT output selectors you are guessing — use the broadest verified selector instead
  ✓ For imageUrlAttr: look for data-src="https://" or data-lazy-src="https://" in reader HTML
  ✓ mangaList.selector must find REPEATED card items, not their container
  ✓ chapters.selector must find individual chapter links, not their list container

  OUTPUT SCHEMA (return ONLY valid JSON, no markdown):
  {
    "name": "Site Name",
    "version": "1",
    "type": "html",
    "baseUrl": "https://example.com",
    "suggestedBuiltinParser": "<key from table above, or null>",
    "suggestedBuiltinParserReason": "<brief explanation of which signals you found>",
    "mangaList": {
      "selector": "<verified selector for one manga card>",
      "url": "a[href]",
      "title": "<verified title selector>",
      "cover": "<verified cover img selector>"
    },
    "mangaDetail": {
      "title": "<verified selector>",
      "cover": "<verified selector>",
      "description": "<verified selector>",
      "chapters": {
        "selector": "<verified selector for chapter rows>",
        "url": "a[href]",
        "title": "<verified title selector>",
        "date": "<verified date selector, or empty string if not found>"
      }
    },
    "chapterPages": {
      "selector": "<verified container + img selector>",
      "imageUrl": "img",
      "imageUrlAttr": "<'src' | 'data-src' | 'data-lazy-src' | 'data-original'>"
    }
  }
          """.trimIndent())

          session.mangaListPage?.let {
              sb.appendLine("\n═══ MANGA LIST PAGE HTML (url: ${it.url}) ═══")
              sb.appendLine(it.html.take(HTML_BUDGET_PER_PAGE))
          }
          session.mangaDetailPage?.let {
              sb.appendLine("\n═══ MANGA DETAIL PAGE HTML (url: ${it.url}) ═══")
              sb.appendLine(it.html.take(HTML_BUDGET_PER_PAGE))
          }
          session.chapterReaderPage?.let {
              sb.appendLine("\n═══ CHAPTER READER PAGE HTML (url: ${it.url}) ═══")
              sb.appendLine(it.html.take(HTML_BUDGET_PER_PAGE))
          }

          sb.appendLine("\nReturn ONLY the JSON object. No markdown fences. No explanation.")
          return sb.toString()
      }

      /** Validates that the Gemini output is parseable JSON with required fields. */
      private fun isValidParserJson(json: String): Boolean = try {
          val obj = JSONObject(json)
          obj.has("mangaList") && obj.has("chapterPages") && obj.has("baseUrl")
      } catch (_: JSONException) {
          false
      }

      // ── Heuristic fallback ────────────────────────────────────────────────────

      fun generateHeuristic(session: LearningSession): String {
          val baseUrl = runCatching {
              val uri = URI(session.mangaListPage?.url ?: session.domain)
              "${uri.scheme}://${uri.host}"
          }.getOrDefault("https://${session.domain}")

          val name = session.siteName.ifBlank {
              session.domain.removePrefix("www.").substringBefore(".")
                  .replaceFirstChar { it.uppercase() }
          }

          val listHtml = session.mangaListPage?.html ?: ""
          val detailHtml = session.mangaDetailPage?.html ?: ""
          val chapterHtml = session.chapterReaderPage?.html ?: ""

          val mangaCardSelector = detectSelector(listHtml, CARD_CANDIDATES)
          val titleSelector = detectSelector(detailHtml, TITLE_CANDIDATES)
          val coverSelector = detectSelector(detailHtml, COVER_CANDIDATES)
          val descSelector = detectSelector(detailHtml, DESC_CANDIDATES)
          val chapterSelector = detectSelector(detailHtml, CHAPTER_CANDIDATES)
          val pageImgSelector = detectSelector(chapterHtml, PAGE_IMG_CANDIDATES)
          val imgAttr = when {
              chapterHtml.contains("data-lazy-src=") -> "data-lazy-src"
              chapterHtml.contains("data-original=") -> "data-original"
              chapterHtml.contains("data-src=") -> "data-src"
              else -> "src"
          }

          return JSONObject().apply {
              put("name", name)
              put("version", "1")
              put("type", "html")
              put("baseUrl", baseUrl)
              put("suggestedBuiltinParser", JSONObject.NULL)
              put("mangaList", JSONObject().apply {
                  put("selector", mangaCardSelector)
                  put("url", "a[href]")
                  put("title", ".manga-title, h3, h2, .title")
                  put("cover", "img[src], img[data-src]")
              })
              put("mangaDetail", JSONObject().apply {
                  put("title", titleSelector)
                  put("cover", coverSelector)
                  put("description", descSelector)
                  put("chapters", JSONObject().apply {
                      put("selector", chapterSelector)
                      put("url", "a[href]")
                      put("title", "a, .chapter-title")
                      put("date", ".date, time, .chapter-time")
                  })
              })
              put("chapterPages", JSONObject().apply {
                  put("selector", pageImgSelector)
                  put("imageUrl", "img")
                  put("imageUrlAttr", imgAttr)
              })
          }.toString(2)
      }

      private fun detectSelector(html: String, candidates: List<String>): String {
          val htmlLower = html.lowercase()
          return candidates.firstOrNull { htmlLower.contains(it.lowercase().substringBefore("[")) }
              ?: candidates.first()
      }

      companion object {
          private const val TAG = "AiParserGenerator"
          private const val GEMINI_URL =
              "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="

          /** HTML characters passed to Gemini per page type (list, detail, chapter). */
          private const val HTML_BUDGET_PER_PAGE = 25_000

          private const val SYSTEM_INSTRUCTION = """You are a specialised HTML scraping engineer.
  Your only job is to output a single valid JSON object — no markdown, no explanation, no prose.
  Analyse the HTML carefully. Every selector you output MUST be verifiable in the provided HTML.
  If you cannot find a reliable selector for a field, use a broad fallback (e.g. "h1" for title).
  Never invent class names. Never reference classes you cannot find in the HTML."""

          private val CARD_CANDIDATES = listOf(
              ".manga-card", ".book-item", ".content-genres-item",
              ".manga-item", ".comic-item", ".c-image-hover",
              "div.manga", ".story-item", "article.manga",
              ".list-story-item", ".itemupdate", ".series-card",
          )
          private val TITLE_CANDIDATES = listOf(
              "h1.manga-title", ".manga-title h1", ".post-title h1",
              "h1.entry-title", ".series-title", "h2.heading", "h1",
          )
          private val COVER_CANDIDATES = listOf(
              ".manga-detail-img img", ".summary_image img",
              ".cover img", ".manga-poster img", ".manga-info img",
              ".detail-info-cover img", ".book-thumbnail img",
          )
          private val DESC_CANDIDATES = listOf(
              ".manga-description", ".description-summary",
              ".entry-content p", ".manga-summary", ".summary__content",
              ".detail-content p", ".story-summary p",
          )
          private val CHAPTER_CANDIDATES = listOf(
              ".wp-manga-chapter a", ".chapter-list a", ".listing-chapters a",
              "#chapters a", "ul.row-content-chapter a",
              ".list-chapter li a", ".chapters li a",
          )
          private val PAGE_IMG_CANDIDATES = listOf(
              ".reading-content img", ".reader-content img",
              ".chapter-img img", ".manga-page img",
              "#images img", ".js-page img",
              ".page-chapter img", "[class*=reader] img",
          )
      }
  }
  