package io.github.landwarderer.futon.customsource.data

  import io.github.landwarderer.futon.customsource.domain.CustomSourceType
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import java.util.concurrent.TimeUnit

  /**
   * Probes a site's homepage and fingerprints the HTML to determine which
   * CMS / parser should handle it.
   *
   * Strategy — we look for distinctive markers in this priority order:
   *  1. MangaSee / MangaLife — vm.Directory or vm.Chapters JS globals
   *  2. Guya reader — /api/series/ endpoint responds with JSON
   *  3. MangaFire style — .manga-poster grid + #chapter-images reader
   *  4. MangaPark — __NEXT_DATA__ JSON blob present
   *  5. MangaThemesia — ts_reader.run, .bsx container
   *  6. Madara — wp-manga, madara-cloned, WpMangaReader meta
   *  7. MangaStream — WPMangaStream, #readerarea, .eph-num
   *  8. FoolSlide2 — /read/ + /directory/ URL pattern
   *  9. Manganelo — manganelo / mangakakalot domain/path hints
   * 10. Zeroscans API — /api/comics JSON endpoint
   * 11. LHTranslation — row-content-chapter, reading-detail
   * 12. Genkan — /comics/ path + genkan meta
   * 13. MangaDex-compatible — /manga or /title REST API responds with JSON { result: "ok" }
   * 14. Fallback → WEBVIEW
   *
   * All detectable types (everything except WEBVIEW) work identically well
   * when the user chooses "Auto-detect" — there is no distinction between
   * types with or without "(Auto)" in their labels.
   */
  object CmsTypeDetector {

      fun detect(baseUrl: String): CustomSourceType {
          val clean = baseUrl.trimEnd('/')
          val html = fetchHtml(clean) ?: return CustomSourceType.WEBVIEW

          // ── MangaSee / MangaLife ──────────────────────────────────────────────
          if (html.contains("vm.Directory") || html.contains("vm.Chapters") || html.contains("vm.CurChapter")) {
              return CustomSourceType.MANGASEE
          }

          // ── Guya reader (JSON API) ────────────────────────────────────────────
          val guyaJson = fetchText("$clean/api/series/")
          if (guyaJson != null && guyaJson.trimStart().startsWith("{")) {
              return CustomSourceType.GUYA
          }

          // ── MangaFire style ───────────────────────────────────────────────────
          if (html.contains("manga-poster") && (html.contains("chapter-images") || html.contains("ep-item"))) {
              return CustomSourceType.MANGAFIRE
          }

          // ── MangaPark (Next.js __NEXT_DATA__) ────────────────────────────────
          if (html.contains("__NEXT_DATA__") && (html.contains("mangapark") || html.contains("/browse"))) {
              return CustomSourceType.MANGAPARK
          }

          // ── WordPress MangaThemesia ───────────────────────────────────────────
          if (html.contains("ts_reader.run") || html.contains(".bsx") || html.contains("mangathemesia")) {
              return CustomSourceType.MANGATHEMESIA
          }

          // ── WordPress Madara ──────────────────────────────────────────────────
          if (html.contains("wp-manga") || html.contains("madara") || html.contains("WpMangaReader")) {
              return CustomSourceType.MADARA
          }

          // ── WordPress MangaStream ─────────────────────────────────────────────
          if (html.contains("WPMangaStream") || html.contains("#readerarea") || html.contains("class="eph-num"")) {
              return CustomSourceType.MANGASTREAM
          }

          // ── FoolSlide2 ────────────────────────────────────────────────────────
          if (html.contains("foolslide") || (html.contains("/read/") && html.contains("/directory/"))) {
              return CustomSourceType.FOOLSLIDE2
          }

          // ── Manganelo / MangaKakalot ──────────────────────────────────────────
          if (html.contains("manganelo") || html.contains("mangakakalot") ||
              html.contains("chapmanganelo") || html.contains("class="story_item"")) {
              return CustomSourceType.MANGANELO
          }

          // ── Zeroscans / JSON REST API ─────────────────────────────────────────
          val zeroscansJson = fetchText("$clean/api/comics")
          if (zeroscansJson != null && (zeroscansJson.contains(""data"") || zeroscansJson.contains(""comics""))) {
              return CustomSourceType.ZEROSCANS_API
          }

          // ── LHTranslation / MangaDNA ──────────────────────────────────────────
          if (html.contains("row-content-chapter") || html.contains("reading-detail") || html.contains("lhtranslation")) {
              return CustomSourceType.LHTRANSLATION
          }

          // ── Genkan ────────────────────────────────────────────────────────────
          if (html.contains("/comics/") && html.contains("genkan")) {
              return CustomSourceType.GENKAN
          }

          // ── MangaDex-compatible REST API ──────────────────────────────────────
          val apiJson = fetchText("$clean/manga?limit=1")
              ?: fetchText("$clean/api/manga?limit=1")
          if (apiJson != null && apiJson.contains(""result"") && apiJson.contains(""ok"")) {
              return CustomSourceType.MANGADEX_COMPATIBLE
          }

          return CustomSourceType.WEBVIEW
      }

      // ── HTTP helpers ──────────────────────────────────────────────────────────

      private fun fetchHtml(url: String): String? = fetchText(url)

      private fun fetchText(url: String): String? {
          return runCatching {
              val req = Request.Builder()
                  .url(url)
                  .header("User-Agent", "Tsuki/1.0 (Android)")
                  .get()
                  .build()
              httpClient.newCall(req).execute().use { resp ->
                  if (!resp.isSuccessful) null else resp.body?.string()?.take(MAX_BYTES)
              }
          }.getOrNull()
      }

      /** Human-readable display name for a detected type (used in toasts). */
      fun displayName(type: CustomSourceType): String = type.label

      private const val MAX_BYTES = 65_536 // 64 KB — enough to see all <head> markers

      private val httpClient: OkHttpClient by lazy {
          OkHttpClient.Builder()
              .connectTimeout(10, TimeUnit.SECONDS)
              .readTimeout(15, TimeUnit.SECONDS)
              .followRedirects(true)
              .build()
      }
  }
  