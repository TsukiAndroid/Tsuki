package io.github.landwarderer.futon.customsource.data

  import io.github.landwarderer.futon.customsource.domain.CustomSourceType
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import java.util.concurrent.TimeUnit

  /**
   * Probes a site's homepage and fingerprints the HTML to pick the right parser.
   *
   * All types (except WEBVIEW) are detected equally — there is no distinction
   * between types that previously had "(Auto)" in their label and those that did not.
   *
   * Detection priority:
   *  1. MangaSee / MangaLife  — vm.Directory or vm.Chapters JS globals
   *  2. MangaFire style       — MOVED UP: checked before Guya to prevent false-positives
   *  3. Guya reader           — /api/series/ returns Guya-structured JSON (title+cover+chapters)
   *  4. MangaPark             — __NEXT_DATA__ + /browse path
   *  5. MangaThemesia         — ts_reader.run, .bsx container
   *  6. Madara                — wp-manga, WpMangaReader
   *  7. MangaStream           — WPMangaStream, readerarea
   *  8. FoolSlide2            — foolslide or /read/ + /directory/
   *  9. Manganelo             — manganelo / mangakakalot markers
   * 10. Zeroscans API         — /api/comics JSON endpoint
   * 11. LHTranslation         — row-content-chapter, reading-detail
   * 12. Genkan               — /comics/ + genkan marker
   * 13. MangaDex-compatible  — REST API returns { result: ok }
   * 14. Fallback             — WEBVIEW
   */
  object CmsTypeDetector {

      fun detect(baseUrl: String): CustomSourceType {
          val clean = baseUrl.trimEnd('/')
          val html = fetchText(clean) ?: return CustomSourceType.WEBVIEW

          // 1. MangaSee / MangaLife — distinctive JS globals
          if (html.contains("vm.Directory") || html.contains("vm.Chapters") || html.contains("vm.CurChapter")) {
              return CustomSourceType.MANGASEE
          }

          // 2. MangaFire style — checked BEFORE Guya so mangafire.to is not falsely detected
          // as Guya just because it happens to respond to /api/series/ with JSON.
          if (isMangaFire(html, clean)) {
              return CustomSourceType.MANGAFIRE
          }

          // 3. Guya reader — JSON API endpoint with Guya-specific structure.
          // Requires at least title+cover or title+chapters keys to avoid false-positives.
          if (isGuyaApi(clean)) {
              return CustomSourceType.GUYA
          }

          // 4. MangaPark — Next.js __NEXT_DATA__ blob
          if (html.contains("__NEXT_DATA__") && (html.contains("mangapark") || html.contains("/browse"))) {
              return CustomSourceType.MANGAPARK
          }

          // 5. WordPress MangaThemesia
          if (html.contains("ts_reader.run") || html.contains(".bsx") || html.contains("mangathemesia")) {
              return CustomSourceType.MANGATHEMESIA
          }

          // 6. WordPress Madara
          if (html.contains("wp-manga") || html.contains("madara") || html.contains("WpMangaReader")) {
              return CustomSourceType.MADARA
          }

          // 7. WordPress MangaStream — readerarea div or eph-num chapter list
          if (html.contains("WPMangaStream") || html.contains("readerarea") || html.contains("eph-num")) {
              return CustomSourceType.MANGASTREAM
          }

          // 8. FoolSlide2
          if (html.contains("foolslide") || (html.contains("/read/") && html.contains("/directory/"))) {
              return CustomSourceType.FOOLSLIDE2
          }

          // 9. Manganelo / MangaKakalot
          if (html.contains("manganelo") || html.contains("mangakakalot") ||
              html.contains("chapmanganelo") || html.contains("story_item")) {
              return CustomSourceType.MANGANELO
          }

          // 10. Zeroscans / JSON API
          val zeroscansJson = fetchText("$clean/api/comics")
          if (zeroscansJson != null && zeroscansJson.contains("comics")) {
              return CustomSourceType.ZEROSCANS_API
          }

          // 11. LHTranslation / MangaDNA
          if (html.contains("row-content-chapter") || html.contains("reading-detail") || html.contains("lhtranslation")) {
              return CustomSourceType.LHTRANSLATION
          }

          // 12. Genkan
          if (html.contains("/comics/") && html.contains("genkan")) {
              return CustomSourceType.GENKAN
          }

          // 13. MangaDex-compatible REST API
          val apiJson = fetchText("$clean/manga?limit=1") ?: fetchText("$clean/api/manga?limit=1")
          if (apiJson != null && apiJson.contains("result") && apiJson.contains("ok")) {
              return CustomSourceType.MANGADEX_COMPATIBLE
          }

          return CustomSourceType.WEBVIEW
      }

      /** Human-readable display name shown in the detection toast. */
      fun displayName(type: CustomSourceType): String = type.label

      // ── MangaFire fingerprint ─────────────────────────────────────────────────

      private fun isMangaFire(html: String, baseUrl: String): Boolean {
          // Strong self-identification
          if (html.contains("mangafire", ignoreCase = true)) return true
          // MangaFire card grid: .manga-poster covers + ep-item chapter rows or manga-list
          if (html.contains("manga-poster") &&
              (html.contains("chapter-images") || html.contains("ep-item") || html.contains("manga-list"))
          ) return true
          // Some MangaFire-style clones use .manga-poster + a filter/browse button
          if (html.contains("manga-poster") && html.contains("btn-filter")) return true
          return false
      }

      // ── Guya API fingerprint (specific, avoids false-positives) ──────────────

      private fun isGuyaApi(baseUrl: String): Boolean {
          val json = fetchText("$baseUrl/api/series/") ?: return false
          val trimmed = json.trimStart()
          // Must be a JSON object (not array)
          if (!trimmed.startsWith("{")) return false
          // Guya API root is a map of slug → series object containing title, cover, chapters
          // Require at least 2 of 3 Guya-specific keys
          val hasTitle = json.contains("\"title\"")
          val hasCover = json.contains("\"cover\"")
          val hasChapters = json.contains("\"chapters\"")
          val score = (if (hasTitle) 1 else 0) + (if (hasCover) 1 else 0) + (if (hasChapters) 1 else 0)
          return score >= 2
      }

      private fun fetchText(url: String): String? = runCatching {
          val req = Request.Builder()
              .url(url)
              .header("User-Agent", "Tsuki/1.0 (Android)")
              .get()
              .build()
          httpClient.newCall(req).execute().use { resp ->
              if (!resp.isSuccessful) null else resp.body?.string()?.take(MAX_BYTES)
          }
      }.getOrNull()

      private const val MAX_BYTES = 65_536 // 64 KB — enough to detect all markers

      private val httpClient: OkHttpClient by lazy {
          OkHttpClient.Builder()
              .connectTimeout(10, TimeUnit.SECONDS)
              .readTimeout(15, TimeUnit.SECONDS)
              .followRedirects(true)
              .build()
      }
  }
  