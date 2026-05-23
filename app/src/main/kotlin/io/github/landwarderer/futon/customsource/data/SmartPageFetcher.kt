package io.github.landwarderer.futon.customsource.data

  import android.content.Context
  import android.util.Log
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import org.jsoup.Jsoup
  import java.net.HttpURLConnection
  import java.net.URI
  import java.net.URL
  import java.util.zip.GZIPInputStream

  /**
   * Finds the correct manga-listing page, a sample detail page, and a sample chapter
   * page for a given site so [SiteAutoDetector] always receives content-rich HTML
   * instead of an empty homepage.
   *
   * Page-discovery strategy (list page):
   *  1. Probe common manga-list paths via HEAD request; fetch the first HTTP-200.
   *  2. Scan homepage navigation links for manga keywords.
   *  3. Fall back to the original URL.
   *
   * After finding the list page the fetcher also retrieves:
   *  - A manga detail page (href matching patterns like /manga/x, /series/x, /title/x)
   *  - A chapter page (href matching patterns like /chapter/x, /ch/x, /read/x)
   *
   * Every fetch is transparently upgraded to WebView rendering when
   * [JsRenderFetcher.isJsRendered] detects JavaScript-only content.
   */
  class SmartPageFetcher(private val context: Context? = null) {

      data class FetchResult(
          val listHtml: String,
          val listUrl: String,
          val detailHtml: String?,
          val detailUrl: String?,
          val chapterHtml: String?,
          val chapterUrl: String?,
      )

      private val jsRenderer by lazy { context?.let { JsRenderFetcher(it) } }

      suspend fun fetch(baseUrl: String, homepageHtml: String): FetchResult =
          withContext(Dispatchers.IO) {

          val listUrl = probeCommonPaths(baseUrl)
              ?: scanNavLinks(homepageHtml, baseUrl)
              ?: baseUrl

          val listHtml = if (listUrl == baseUrl) homepageHtml
                         else fetchRendered(listUrl) ?: homepageHtml

          val detailUrl  = findDetailUrl(listHtml, listUrl, baseUrl)
          val detailHtml = if (detailUrl != null) fetchRendered(detailUrl) else null

          val chapterUrl  = if (detailHtml != null && detailUrl != null)
              findChapterUrl(detailHtml, detailUrl, baseUrl) else null
          val chapterHtml = if (chapterUrl != null) fetchRendered(chapterUrl) else null

          FetchResult(listHtml, listUrl, detailHtml, detailUrl, chapterHtml, chapterUrl)
      }

      private fun probeCommonPaths(baseUrl: String): String? {
          val paths = listOf(
              "/manga", "/manhwa", "/manhua", "/comics", "/series", "/titles",
              "/catalog", "/manga-list", "/all-manga", "/browse", "/library",
          )
          return paths.firstOrNull { headReturns200("$baseUrl$it") }
              ?.let { "$baseUrl$it" }
      }

      private fun headReturns200(url: String): Boolean = runCatching {
          val conn = URL(url).openConnection() as HttpURLConnection
          conn.requestMethod = "HEAD"
          conn.setRequestProperty("User-Agent", USER_AGENT)
          conn.connectTimeout = 6_000
          conn.readTimeout    = 6_000
          conn.instanceFollowRedirects = true
          val code = conn.responseCode
          conn.disconnect()
          code in 200..299
      }.getOrDefault(false)

      private fun scanNavLinks(html: String, baseUrl: String): String? {
          val doc      = Jsoup.parse(html, baseUrl)
          val keywords = setOf(
              "manga", "manhwa", "manhua", "comics", "series",
              "browse", "catalog", "library", "all",
          )
          val navLinks = doc.select(
              "nav a[href], header a[href], .menu a[href], #menu a[href], .navbar a[href]"
          )
          for (link in navLinks) {
              val href = link.absUrl("href").ifBlank { continue }
              val text = link.text().lowercase()
              val path = runCatching { URI(href).path.lowercase() }.getOrDefault("")
              if (keywords.any { text.contains(it) || path.contains(it) }) {
                  if (headReturns200(href)) return href
              }
          }
          return null
      }

      private fun findDetailUrl(listHtml: String, listUrl: String, baseUrl: String): String? {
          val doc           = Jsoup.parse(listHtml, listUrl)
          // Raw string: avoids escaping issues for the " inside [^/"#?]
          val detailPattern = Regex(
              """/(manga|series|comics|title|manhwa|manhua|webtoon)/[^/"#?]+/?$""",
              RegexOption.IGNORE_CASE,
          )
          return doc.select("a[href]")
              .map { it.absUrl("href") }
              .firstOrNull { href -> href.startsWith(baseUrl) && detailPattern.containsMatchIn(href) }
      }

      private fun findChapterUrl(detailHtml: String, detailUrl: String, baseUrl: String): String? {
          val doc            = Jsoup.parse(detailHtml, detailUrl)
          val chapterPattern = Regex("/(chapter|ch|read|chapters?)/", RegexOption.IGNORE_CASE)
          return doc.select("a[href]")
              .map { it.absUrl("href") }
              .firstOrNull { href -> href.startsWith(baseUrl) && chapterPattern.containsMatchIn(href) }
      }

      /** Fetches [url] using plain HTTP, upgrading to WebView when JS-only content is detected. */
      private suspend fun fetchRendered(url: String): String? {
          val raw = fetchPlain(url) ?: return null
          return if (jsRenderer != null && JsRenderFetcher.isJsRendered(raw)) {
              Log.d(TAG, "JS-rendered page detected: $url")
              jsRenderer!!.fetch(url) ?: raw
          } else {
              raw
          }
      }

      private fun fetchPlain(url: String): String? = runCatching {
          val conn = URL(url).openConnection() as HttpURLConnection
          conn.requestMethod = "GET"
          conn.setRequestProperty("User-Agent", USER_AGENT)
          conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8")
          conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
          conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
          conn.connectTimeout = 15_000
          conn.readTimeout    = 20_000
          conn.instanceFollowRedirects = true
          if (conn.responseCode !in 200..299) return@runCatching null
          val enc    = conn.contentEncoding
          val stream = if (enc == "gzip") GZIPInputStream(conn.inputStream) else conn.inputStream
          stream.bufferedReader(Charsets.UTF_8).readText().take(500_000)
      }.onFailure { Log.w(TAG, "fetchPlain failed: $url", it) }.getOrNull()

      companion object {
          private const val TAG        = "SmartPageFetcher"
          private const val USER_AGENT =
              "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
              "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
      }
  }