package io.github.landwarderer.futon.customsource.data

  import io.github.landwarderer.futon.customsource.domain.CustomSourceType
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import java.util.concurrent.TimeUnit

  /**
   * Sniffs the CMS/theme type of a manga website by fetching its home page
   * and inspecting the HTML for well-known fingerprints.
   *
   * Detection is ordered from most-specific to most-generic so that niche CMS
   * variants are caught before broader WordPress patterns.
   *
   * Falls back to [CustomSourceType.WEBVIEW] when the site cannot be identified.
   */
  object CmsTypeDetector {

      /**
       * Fetches [url] and returns the best-matching [CustomSourceType].
       * This call performs a network request — run it on a background dispatcher.
       */
      fun detect(url: String): CustomSourceType {
          val html = runCatching { fetchHtml(url) }.getOrNull() ?: return CustomSourceType.WEBVIEW
          return detectFromHtml(url, html)
      }

      fun detectFromHtml(url: String, html: String): CustomSourceType {
          val lower = html.lowercase()
          val urlLower = url.lowercase()

          // ── Zeroscans / JSON REST API ─────────────────────────────────────────
          // These sites serve an API and their root may redirect or return JSON
          if (urlLower.contains("/api/") ||
              lower.contains("\"result\":\"ok\"") ||
              lower.contains("\"comics\":") && lower.contains("\"chapter\":")) {
              return CustomSourceType.ZEROSCANS_API
          }

          // ── FoolSlide2 ────────────────────────────────────────────────────────
          if (lower.contains("foolslide") ||
              lower.contains("/directory/") && lower.contains("class=\"list\"") ||
              lower.contains("powered by foolslide")) {
              return CustomSourceType.FOOLSLIDE2
          }

          // ── Genkan ────────────────────────────────────────────────────────────
          if ((lower.contains("genkan") || lower.contains("leviatan")) &&
              lower.contains("/comics")) {
              return CustomSourceType.GENKAN
          }
          if (lower.contains("class=\"col-lg-2") && lower.contains("/comics/")) {
              return CustomSourceType.GENKAN
          }

          // ── MangaKakalot / Manganelo ──────────────────────────────────────────
          if (lower.contains("manganelo") || lower.contains("mangakakalot") ||
              lower.contains("chapmanganelo") || lower.contains("mkklcdn") ||
              (lower.contains("manga-list.html") || lower.contains("genre-all")) &&
              lower.contains("story_item")) {
              return CustomSourceType.MANGANELO
          }

          // ── LHTranslation / MangaDNA style ────────────────────────────────────
          if (lower.contains("row-content-chapter") ||
              lower.contains("reading-detail") && lower.contains("manga-info-pic") ||
              lower.contains("panel-story-info-description")) {
              return CustomSourceType.LHTRANSLATION
          }

          // ── WordPress MangaThemesia ───────────────────────────────────────────
          // Identified by ts_reader.run, #chapterlist, or .bsx grid items
          if (lower.contains("ts_reader.run") ||
              lower.contains("id=\"chapterlist\"") ||
              lower.contains("class=\"bsx\"") ||
              lower.contains("/wp-content/themes/themesia") ||
              lower.contains("/wp-content/themes/manga") && lower.contains("ts_reader")) {
              return CustomSourceType.MANGATHEMESIA
          }

          // ── WordPress MangaStream / WPMangaStream ─────────────────────────────
          if (lower.contains("id=\"readerarea\"") ||
              lower.contains("class=\"eph-num\"") ||
              lower.contains("/wp-content/themes/mangastream") ||
              lower.contains("/wp-content/themes/komiku")) {
              return CustomSourceType.MANGASTREAM
          }

          // ── WordPress Madara ──────────────────────────────────────────────────
          // Broader WP-manga check comes after more specific WP themes
          if (lower.contains("wp-manga") ||
              lower.contains("madara") ||
              lower.contains("class=\"c-image-inner") ||
              lower.contains("madara_load_more") ||
              lower.contains("manga-chapters-holder")) {
              return CustomSourceType.MADARA
          }

          // ── MangaDex-compatible REST API ──────────────────────────────────────
          // If fetching /manga returns JSON with "result":"ok" it's MangaDex-compatible
          val apiHtml = runCatching { fetchHtml("${url.trimEnd('/')}/manga?limit=1") }.getOrNull() ?: ""
          if (apiHtml.contains("\"result\":\"ok\"") || apiHtml.contains("\"data\":")) {
              return CustomSourceType.MANGADEX_COMPATIBLE
          }

          return CustomSourceType.WEBVIEW
      }

      private fun fetchHtml(url: String): String {
          val req = Request.Builder()
              .url(url)
              .header("User-Agent", "Tsuki/1.0 (Android)")
              .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
              .get()
              .build()
          return httpClient.newCall(req).execute().use { resp ->
              resp.body?.string() ?: ""
          }
      }

      private val httpClient: OkHttpClient by lazy {
          OkHttpClient.Builder()
              .connectTimeout(12, TimeUnit.SECONDS)
              .readTimeout(15, TimeUnit.SECONDS)
              .followRedirects(true)
              .build()
      }
  }
  