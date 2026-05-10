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
   *  1.  MangaSee / MangaLife  — vm.Directory or vm.Chapters JS globals
   *  2.  MangaFire style       — checked before Guya to prevent false-positives
   *  3.  Guya reader           — /api/series/ returns Guya-structured JSON
   *  4.  MangaPark             — __NEXT_DATA__ + /browse path
   *  5.  MangaThemesia         — ts_reader.run, .bsx container
   *  6.  Madara                — wp-manga, WpMangaReader
   *  7.  MangaStream           — WPMangaStream, readerarea
   *  8.  FoolSlide2            — foolslide or /read/ + /directory/
   *  9.  Manganelo             — manganelo / mangakakalot markers
   *  10. Zeroscans API         — /api/comics JSON endpoint
   *  11. LHTranslation         — row-content-chapter, reading-detail
   *  12. Genkan                — /comics/ + genkan marker
   *  13. Comix.to              — comix.to markers OR .list-story-item + chapImages
   *  14. ComicK API            — api.comick.io responds to /v1.0/comic/
   *  15. Bato.to               — bato.to / batocomic / comiko markers
   *  16. NineManga             — ninemanga, .detail_list, .manga_detail
   *  17. MangaHost             — mangahost, leitor.net, .manga-card
   *  18. MangaReader           — mangareader.to, .manga-poster + .sort-name
   *  19. FanFox / MangaFox     — fanfox, mangafox, .list-2 .item
   *  20. TCBScans              — tcbscans, .entry-img + scanlation static
   *  21. MangaNato             — manganato, mangabat, .panel-story-chapter-list
   *  22. ReaderFront           — /graphql returns data.works JSON
   *  23. KissManga             — kissmanga, lstImagesUrl in script
   *  24. Cubari                — cubari, /read/api/ endpoint
   *  25. MangaDex-compatible  — REST API returns { result: ok }
   *  26. Fallback             — WEBVIEW
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
          if (isMangaFire(html, clean)) {
              return CustomSourceType.MANGAFIRE
          }

          // 3. Guya reader — JSON API endpoint with Guya-specific structure
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

          // 13. Comix.to
          if (isComixTo(html, clean)) {
              return CustomSourceType.COMIXTO
          }

          // 14. ComicK API — api.comick.io or self-hosted ComicK instance
          if (isComicK(html, clean)) {
              return CustomSourceType.COMICK_API
          }

          // 15. Bato.to / Batocomic / Comiko
          if (html.contains("bato.to", ignoreCase = true) || html.contains("batocomic", ignoreCase = true) ||
              html.contains("comiko", ignoreCase = true) ||
              (html.contains("item-text") && html.contains("browse?sort="))) {
              return CustomSourceType.BATO
          }

          // 16. NineManga
          if (html.contains("ninemanga", ignoreCase = true) || html.contains("detail_list") ||
              html.contains("manga_detail") || html.contains("page_select")) {
              return CustomSourceType.NINEMANGA
          }

          // 17. MangaHost / Leitor.net
          if (html.contains("mangahost", ignoreCase = true) || html.contains("leitor.net", ignoreCase = true) ||
              (html.contains("manga-card") && html.contains("kw-title"))) {
              return CustomSourceType.MANGAHOST
          }

          // 18. MangaReader.to style
          if (html.contains("mangareader", ignoreCase = true) ||
              (html.contains("manga-poster") && html.contains("sort-name") && html.contains("manga-detail"))) {
              return CustomSourceType.MANGAREADER
          }

          // 19. FanFox / MangaFox
          if (html.contains("fanfox", ignoreCase = true) || html.contains("mangafox", ignoreCase = true) ||
              (html.contains("detail-info-right") && html.contains("detail-main-list"))) {
              return CustomSourceType.MANGAFOX
          }

          // 20. TCBScans / static scanlation sites
          if (html.contains("tcbscans", ignoreCase = true) ||
              (html.contains("entry-img") && html.contains("latest-chapter") && html.contains("chapter"))) {
              return CustomSourceType.TCBSCANS
          }

          // 21. MangaNato / MangaBat
          if (html.contains("manganato", ignoreCase = true) || html.contains("mangabat", ignoreCase = true) ||
              html.contains("mangabuddy", ignoreCase = true) ||
              html.contains("panel-story-chapter-list") || html.contains("panel-list-story")) {
              return CustomSourceType.MANGANATO
          }

          // 22. ReaderFront GraphQL
          if (isReaderFront(clean)) {
              return CustomSourceType.READERFRONT
          }

          // 23. KissManga / MangaKiss family
          if (html.contains("kissmanga", ignoreCase = true) || html.contains("readcomiconline", ignoreCase = true) ||
              html.contains("lstImagesUrl") ||
              (html.contains("barContent") && html.contains("listing"))) {
              return CustomSourceType.KISSMANGA
          }

          // 24. Cubari.moe
          if (html.contains("cubari", ignoreCase = true) || isCubari(clean)) {
              return CustomSourceType.CUBARI
          }

          // 25. MangaDex-compatible REST API
          val apiJson = fetchText("$clean/manga?limit=1") ?: fetchText("$clean/api/manga?limit=1")
          if (apiJson != null && apiJson.contains("result") && apiJson.contains("ok")) {
              return CustomSourceType.MANGADEX_COMPATIBLE
          }

          return CustomSourceType.WEBVIEW
      }

      /** Human-readable display name shown in the detection toast. */
      fun displayName(type: CustomSourceType): String = type.label

      // ── Comix.to fingerprint ──────────────────────────────────────────────────

      private fun isComixTo(html: String, baseUrl: String): Boolean {
          if (html.contains("comix.to", ignoreCase = true)) return true
          if (html.contains("list-story-item") && html.contains("story-item-wrap")) return true
          if (html.contains("chapImages") || html.contains("lstImages")) return true
          return false
      }

      // ── ComicK fingerprint ────────────────────────────────────────────────────

      private fun isComicK(html: String, baseUrl: String): Boolean {
          if (html.contains("comick", ignoreCase = true)) return true
          val apiResp = fetchText("https://api.comick.io/v1.0/comic/?limit=1")
          if (apiResp != null && apiResp.contains("title") && apiResp.contains("hid")) return true
          val selfApi = fetchText("$baseUrl/api/v1.0/comic/?limit=1")
          if (selfApi != null && selfApi.contains("title") && selfApi.contains("hid")) return true
          return false
      }

      // ── ReaderFront fingerprint ───────────────────────────────────────────────

      private fun isReaderFront(baseUrl: String): Boolean {
          val gql = fetchText("$baseUrl/graphql?query={works{name}}")
              ?: fetchText("$baseUrl/api/graphql?query={works{name}}")
          return gql != null && gql.contains("works") && gql.contains("name")
      }

      // ── Cubari fingerprint ────────────────────────────────────────────────────

      private fun isCubari(baseUrl: String): Boolean {
          val apiResp = fetchText("$baseUrl/read/api/gist/series/")
              ?: fetchText("$baseUrl/read/api/guya/series/")
          return apiResp != null && apiResp.startsWith("{") && apiResp.contains("title")
      }

      // ── MangaFire fingerprint ─────────────────────────────────────────────────

      private fun isMangaFire(html: String, baseUrl: String): Boolean {
          if (html.contains("mangafire", ignoreCase = true)) return true
          if (html.contains("manga-poster") &&
              (html.contains("chapter-images") || html.contains("ep-item") || html.contains("manga-list"))
          ) return true
          if (html.contains("manga-poster") && html.contains("btn-filter")) return true
          return false
      }

      // ── Guya API fingerprint (specific, avoids false-positives) ──────────────

      private fun isGuyaApi(baseUrl: String): Boolean {
          val json = fetchText("$baseUrl/api/series/") ?: return false
          val trimmed = json.trimStart()
          if (!trimmed.startsWith("{")) return false
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
