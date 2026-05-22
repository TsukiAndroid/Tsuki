package io.github.landwarderer.futon.customsource.data

  import android.util.Log
  import io.github.landwarderer.futon.browser.learning.AiParserGenerator
  import io.github.landwarderer.futon.browser.learning.LearningSession
  import io.github.landwarderer.futon.browser.learning.PageType
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import org.json.JSONObject
  import org.jsoup.Jsoup
  import java.net.HttpURLConnection
  import java.net.URI
  import java.net.URL

  /**
   * Fetches a manga website's HTML and auto-detects CSS selectors
   * needed to configure it as a Universal Source.
   *
   * Flow:
   *  1. Fetch homepage HTML → classify as MANGA_LIST
   *  2. Follow the first manga card link → MANGA_DETAIL
   *  3. Follow the first chapter link → CHAPTER_READER
   *  4. Feed collected pages to [AiParserGenerator] heuristics
   *  5. Map the resulting JSON back to individual form fields with [Confidence] scores
   */
  class SiteAutoDetector {

      /**
       * How confident the heuristic is about a detected selector.
       *
       *  HIGH   — matched a specific class/ID selector (contains "." or "#") or came
       *           from a targeted known-CMS pattern; very likely correct.
       *  MEDIUM — matched a generic tag-only selector (e.g. "img", "a", "h1") via a
       *           fallback rule; probably works but should be reviewed.
       *  LOW    — field could not be detected; the user must fill it in manually.
       */
      enum class Confidence { HIGH, MEDIUM, LOW }

      /**
       * All fields that can be auto-populated in [UniversalSourceActivity].
       * Empty strings mean "could not detect" — the user fills them in manually.
       * [fieldConfidence] maps field keys (matching property names) to [Confidence].
       */
      data class DetectedFields(
          val siteName: String = "",
          val listPath: String = "",
          val searchPath: String = "",
          val cardSelector: String = "",
          val titleSelector: String = "",
          val coverSelector: String = "",
          val detailTitle: String = "",
          val description: String = "",
          val chapterSelector: String = "",
          val pageImageSelector: String = "",
          /** Key = property name, value = confidence level for that field. */
          val fieldConfidence: Map<String, Confidence> = emptyMap(),
      )

      suspend fun detect(baseUrl: String): DetectedFields = withContext(Dispatchers.IO) {
          val session = LearningSession()
          val domain = runCatching { URI(baseUrl).host ?: "" }.getOrDefault("")
          session.domain = domain

          // ── Step 1: Fetch homepage (manga list page) ──────────────────────────
          val listHtml = fetchHtml(baseUrl) ?: return@withContext DetectedFields()
          session.capture(PageType.MANGA_LIST, baseUrl, listHtml)

          // ── Step 2: Find and fetch a manga detail page ────────────────────────
          val listDoc = Jsoup.parse(listHtml, baseUrl)
          val detailUrl = findDetailPageUrl(listDoc, baseUrl)
          var detailHtml: String? = null
          if (detailUrl != null) {
              detailHtml = fetchHtml(detailUrl)
              if (detailHtml != null) {
                  session.capture(PageType.MANGA_DETAIL, detailUrl, detailHtml)
              }
          }

          // ── Step 3: Find and fetch a chapter reader page ──────────────────────
          if (detailHtml != null && detailUrl != null) {
              val detailDoc = Jsoup.parse(detailHtml, detailUrl)
              val chapterUrl = findChapterUrl(detailDoc, baseUrl)
              if (chapterUrl != null) {
                  val chapterHtml = fetchHtml(chapterUrl)
                  if (chapterHtml != null) {
                      session.capture(PageType.CHAPTER_READER, chapterUrl, chapterHtml)
                  }
              }
          }

          // ── Step 4: Run AiParserGenerator heuristics (no API key → pure heuristic) ──
          val generator = AiParserGenerator()
          val json = generator.generate(session, null)

          // ── Step 5: Map JSON → DetectedFields with confidence ─────────────────
          mapJsonToFields(json, domain)
      }

      // ── HTML fetching ─────────────────────────────────────────────────────────

      private fun fetchHtml(url: String): String? = runCatching {
          val conn = URL(url).openConnection() as HttpURLConnection
          conn.requestMethod = "GET"
          conn.setRequestProperty("User-Agent", USER_AGENT)
          conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
          conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5")
          conn.connectTimeout = 15_000
          conn.readTimeout = 20_000
          conn.instanceFollowRedirects = true
          if (conn.responseCode in 200..299) {
              conn.inputStream.bufferedReader(Charsets.UTF_8).readText().take(80_000)
          } else {
              Log.w(TAG, "HTTP ${conn.responseCode} for $url")
              null
          }
      }.onFailure { Log.w(TAG, "Failed to fetch $url", it) }.getOrNull()

      // ── Link discovery ────────────────────────────────────────────────────────

      private fun findDetailPageUrl(
          doc: org.jsoup.nodes.Document,
          baseUrl: String,
      ): String? {
          val cardSelectors = listOf(
              ".manga-card a[href]", ".book-item a[href]", ".manga-item a[href]",
              ".c-image-hover a[href]", ".post-title a[href]", "article.manga a[href]",
              ".item a[href]", "h3 a[href]", "h2 a[href]",
          )
          for (sel in cardSelectors) {
              val href = doc.selectFirst(sel)?.absUrl("href")
                  ?.takeIf { it.startsWith("http") } ?: continue
              if (looksLikeDetailUrl(href)) return href
          }
          return doc.select("a[href]").firstNotNullOfOrNull { el ->
              val href = el.absUrl("href").takeIf { it.startsWith(baseUrl) } ?: return@firstNotNullOfOrNull null
              if (looksLikeDetailUrl(href)) href else null
          }
      }

      private fun findChapterUrl(
          doc: org.jsoup.nodes.Document,
          baseUrl: String,
      ): String? {
          val chapterSelectors = listOf(
              ".wp-manga-chapter a[href]", ".chapter-list a[href]", "#chapters a[href]",
              "ul.chapters li a[href]", ".chapter-link[href]", ".chapter-item a[href]",
              "li.chapter a[href]", ".listing-chapters a[href]", ".chapter_list a[href]",
          )
          for (sel in chapterSelectors) {
              val href = doc.selectFirst(sel)?.absUrl("href")
                  ?.takeIf { it.startsWith("http") } ?: continue
              return href
          }
          return null
      }

      private fun looksLikeDetailUrl(href: String): Boolean {
          val path = runCatching { URI(href).path }.getOrNull() ?: return false
          return path.count { it == '/' } >= 2 && path.length > 8
      }

      // ── JSON → form fields ────────────────────────────────────────────────────

      private fun mapJsonToFields(json: String, domain: String): DetectedFields {
          val root = runCatching { JSONObject(json) }.getOrNull() ?: return DetectedFields()

          val mangaList   = root.optJSONObject("mangaList")
          val mangaDetail = root.optJSONObject("mangaDetail")
          val chapterList = root.optJSONObject("chapterList")
          val pageList    = root.optJSONObject("pageList")

          val listPath = mangaList?.optString("endpoint", "") ?: ""

          val searchEndpoint = mangaList?.optString("searchEndpoint", "") ?: ""
          val searchParam    = mangaList?.optString("searchParam", "s") ?: "s"
          val searchPath = if (searchEndpoint.isNotEmpty()) "$searchEndpoint?$searchParam=" else ""

          val siteName = root.optString("name", "").ifEmpty {
              domain.removePrefix("www.")
                  .substringBefore(".")
                  .replaceFirstChar { it.uppercaseChar() }
          }

          val cardSelector     = mangaList?.optString("itemSelector", "") ?: ""
          val titleSelector    = mangaList?.optString("titleSelector", "") ?: ""
          val coverSelector    = mangaList?.optString("coverSelector", "") ?: ""
          val detailTitle      = mangaDetail?.optString("titleSelector", "") ?: ""
          val description      = mangaDetail?.optString("descriptionSelector", "") ?: ""
          val chapterSelector  = chapterList?.optString("selector", "") ?: ""
          val pageImageSelector = pageList?.optString("imageSelector", "") ?: ""

          // ── Confidence scoring ────────────────────────────────────────────────
          // HIGH  → selector contains a class or ID (specific signal)
          // MEDIUM → non-empty but only a bare tag (generic fallback)
          // LOW   → empty (not detected)
          fun score(selector: String): Confidence = when {
              selector.isEmpty()                   -> Confidence.LOW
              selector.contains('.') ||
              selector.contains('#') ||
              selector.contains('[')               -> Confidence.HIGH
              else                                 -> Confidence.MEDIUM
          }

          val confidence = mapOf(
              "cardSelector"      to score(cardSelector),
              "titleSelector"     to score(titleSelector),
              "coverSelector"     to score(coverSelector),
              "detailTitle"       to score(detailTitle),
              "description"       to score(description),
              "chapterSelector"   to score(chapterSelector),
              "pageImageSelector" to score(pageImageSelector),
              // Paths are always at least MEDIUM if non-empty (we derive them from URL structure)
              "listPath"          to if (listPath.isNotEmpty()) Confidence.MEDIUM else Confidence.LOW,
              "searchPath"        to if (searchPath.isNotEmpty()) Confidence.MEDIUM else Confidence.LOW,
          )

          return DetectedFields(
              siteName          = siteName,
              listPath          = listPath.ifEmpty { "/" },
              searchPath        = searchPath,
              cardSelector      = cardSelector,
              titleSelector     = titleSelector,
              coverSelector     = coverSelector,
              detailTitle       = detailTitle,
              description       = description,
              chapterSelector   = chapterSelector,
              pageImageSelector = pageImageSelector,
              fieldConfidence   = confidence,
          )
      }

      companion object {
          private const val TAG = "SiteAutoDetector"
          private const val USER_AGENT =
              "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
              "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
      }
  }
  