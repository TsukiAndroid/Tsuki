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
   *  25. MangaPill             — mangapill, js-page class on img elements
   *  26. MangaHub              — mangahub, manga-page + media-heading
   *  27. MangaHere/Foxaholic   — mangahere, .manga-list + .detail-main-list
   *  28. MangaLib              — mangalib/ranobelib + lib.social API
   *  29. Mangago               — mangago, #book_list + .booklist_item
   *  30. MangaFreak            — mangafreak, .manga_search_item
   *  31. MangaOwl              — mangaowl, .comic-item + #images
   *  32. NetTruyen             — nettruyen, .ModuleContent + .reading-detail
   *  33. TruyenQQ              — truyenqq, .book_avatar + .listChapters
   *  34. MangaKatana           — mangakatana, img.chapter-img + #chapters
   *  35. MangaDex-compatible  — REST API returns { result: ok }
   *  36. Fallback             — WEBVIEW
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

          // 10. Zeroscans / JSON API — must NOT match PizzaReader which also serves /api/comics.
          // Zeroscans responses contain "slug" and "name" per comic object.
          // PizzaReader responses contain "url" and "title" instead — exclude those.
          val zeroscansJson = fetchText("$clean/api/comics")
          if (zeroscansJson != null &&
              (zeroscansJson.contains("\"slug\"") || zeroscansJson.contains("\"name\"")) &&
              !zeroscansJson.contains("\"url\"") && !zeroscansJson.contains("\"title\"")) {
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

          // 25. MangaPill — distinctive js-page img class or domain name
          if (html.contains("mangapill", ignoreCase = true) ||
              (html.contains("js-page") && html.contains("data-src") && html.contains("chapters"))) {
              return CustomSourceType.MANGAPILL
          }

          // 26. MangaHub — domain name or media-heading + manga-page markers
          if (html.contains("mangahub", ignoreCase = true) ||
              (html.contains("media-heading") && html.contains("manga-page") && html.contains("chapter-table"))) {
              return CustomSourceType.MANGAHUB
          }

          // 27. MangaHere / Foxaholic CMS — checked AFTER MangaFox to avoid false-positives
          // MangaHere uses .manga-list (with hyphen) vs FanFox which uses .list-2
          if (html.contains("mangahere", ignoreCase = true) ||
              (html.contains("manga-list") && html.contains("detail-main-list") &&
               !html.contains("fanfox") && !html.contains("mangafox"))) {
              return CustomSourceType.MANGAHERE
          }

          // 28. MangaLib / RanobeLib / lib.social — Russian platform REST API
          if (html.contains("mangalib", ignoreCase = true) ||
              html.contains("ranobelib", ignoreCase = true) ||
              html.contains("lib.social", ignoreCase = true) ||
              isMangaLib(clean)) {
              return CustomSourceType.MANGALIB
          }

          // 29. Mangago — #book_list + .booklist_item or domain name
          if (html.contains("mangago", ignoreCase = true) ||
              (html.contains("book_list") && html.contains("booklist_item"))) {
              return CustomSourceType.MANGAGO
          }

          // 30. MangaFreak — distinctive .manga_search_item class or domain
          if (html.contains("mangafreak", ignoreCase = true) ||
              html.contains("manga_search_item") ||
              (html.contains("/Manga/") && html.contains("/Search/") && html.contains("reader_images"))) {
              return CustomSourceType.MANGAFREAK
          }

          // 31. MangaOwl — .comic-item cards + #images reader, or domain name
          if (html.contains("mangaowl", ignoreCase = true) ||
              (html.contains("comic-item") && html.contains("story-chapter-item"))) {
              return CustomSourceType.MANGAOWL
          }

          // 32. NetTruyen — .ModuleContent + truyen-tranh URL pattern (Vietnamese CMS)
          // Must come before TruyenQQ since both share some Vietnamese vocabulary
          if (html.contains("nettruyen", ignoreCase = true) ||
              (html.contains("ModuleContent") && html.contains("reading-detail") &&
               html.contains("truyen-tranh"))) {
              return CustomSourceType.NETTRUYEN
          }

          // 33. TruyenQQ — .book_avatar + .listChapters + .html URL convention
          if (html.contains("truyenqq", ignoreCase = true) ||
              (html.contains("book_avatar") && html.contains("listChapters") &&
               html.contains(".html"))) {
              return CustomSourceType.TRUYENQQ
          }

          // 34. MangaKatana — img.chapter-img + #chapters table, or domain name
          if (html.contains("mangakatana", ignoreCase = true) ||
              (html.contains("chapter-img") && html.contains("id=\"chapters\""))) {
              return CustomSourceType.MANGAKATANA
          }

          // 35. ZeistManga (Blogger-based) — Atom feed at /feeds/posts/default/-/Series?alt=json
          if (html.contains("blogger.com", ignoreCase = true) ||
              html.contains("blogspot.com", ignoreCase = true) ||
              isZeistManga(html, clean)) {
              return CustomSourceType.ZEISTMANGA
          }

          // 36. Keyoapp CMS — #series_tags_page + div.grid > div.group + #chapters
          if (html.contains("series_tags_page") ||
              (html.contains("div.grid") && html.contains("div.group") && html.contains("#chapters")) ||
              (html.contains("keyoapp") || html.contains("asuracomic"))) {
              return CustomSourceType.KEYOAPP
          }

          // 37. HeanCms — JSON API at api.{domain}/query with posts or series_type=Comic
          if (isHeanCms(clean)) {
              return CustomSourceType.HEANCMS
          }

          // 38. Iken CMS — JSON API at api.{domain}/api/query with posts[] response
          if (isIkenCms(clean)) {
              return CustomSourceType.IKEN
          }

          // 39. PizzaReader — /api/comics returns JSON with comics[] array
          if (isPizzaReader(clean)) {
              return CustomSourceType.PIZZAREADER
          }

          // 40. WpComics — Vietnamese WordPress CMS with /tim-truyen and div.items
          if (html.contains("tim-truyen", ignoreCase = true) ||
              (html.contains("div.items") && html.contains("box_tootip")) ||
              (html.contains("wpcomics", ignoreCase = true))) {
              return CustomSourceType.WPCOMICS
          }

          // 41. Mmrcms — /filterList endpoint + div.media Bootstrap cards
          if (html.contains("filterList", ignoreCase = true) ||
              (html.contains("media-body") && html.contains("chapter-item")) ||
              isMmrcms(clean)) {
              return CustomSourceType.MMRCMS
          }

          // 42. Madtheme — div.book-item + /search/ URL + meta score class
          if (html.contains("book-item") &&
              (html.contains("score") || html.contains("madtheme") || html.contains("/search/?")) &&
              !html.contains("wp-manga")) {
              return CustomSourceType.MADTHEME
          }

          // 43. Mangabox — /manga-list?type= URL pattern + .content-genres-item
          if (html.contains("manga-list") && html.contains("content-genres-item") ||
              html.contains("topview") && html.contains("list-truyen-item-wrap")) {
              return CustomSourceType.MANGABOX
          }

          // 44. Liliana CMS — /filter/{page}/ + y6x11p class + syn-target
          if (html.contains("y6x11p") || html.contains("syn-target") ||
              (html.contains("/filter/") && html.contains("latest-updated"))) {
              return CustomSourceType.LILIANA
          }

          // 45. Scan CMS — /manga listing + .chapter-list + no Madara markers
          if (html.contains("sushiscan", ignoreCase = true) ||
              html.contains("lelscans", ignoreCase = true) ||
              (html.contains("chapter-list") && html.contains("/manga") &&
               !html.contains("wp-manga") && !html.contains("madara"))) {
              return CustomSourceType.SCAN
          }

          // 46. FmReader — .manga-list-4-list or chapter-image class (specific to FmReader)
          if (html.contains("manga-list-4-list") || html.contains("chapter-image") ||
              html.contains("fmreader", ignoreCase = true)) {
              return CustomSourceType.FMREADER
          }

          // 47. Gattsu CMS — WordPress-derived with /page/ URLs and manga grid
          if (html.contains("gattsu", ignoreCase = true) ||
              (html.contains("/page/") && html.contains("chapters-list") &&
               !html.contains("wp-manga"))) {
              return CustomSourceType.GATTSU
          }

          // 48. AnimeBootstrap — /manga?page= + .list-manga-item or Bootstrap manga theme
          if (html.contains("animebootstrap", ignoreCase = true) ||
              html.contains("list-manga-item") ||
              (html.contains("sort_by=") && html.contains("/manga?page="))) {
              return CustomSourceType.ANIMEBOOTSTRAP
          }

          // 49. MangaDex-compatible REST API
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

      // ── MangaLib API fingerprint ──────────────────────────────────────────────

      private fun isMangaLib(baseUrl: String): Boolean {
          // Try the new unified API endpoint
          val apiResp = fetchText("https://api.lib.social/api/manga?page=1&site_id[]=1&fields[]=slug_url")
              ?: fetchText("https://api.mangalib.me/api/manga?page=1&fields[]=slug_url")
          return apiResp != null && apiResp.contains("slug_url") && apiResp.contains("data")
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

      // ── ZeistManga fingerprint ────────────────────────────────────────────────

      private fun isZeistManga(html: String, baseUrl: String): Boolean {
          if (html.contains("feeds/posts/default")) return true
          val feedResp = fetchText("$baseUrl/feeds/posts/default/-/Series?alt=json&max-results=1")
          if (feedResp != null && feedResp.contains("\"feed\"") && feedResp.contains("entry")) return true
          return false
      }

      // ── HeanCms fingerprint ───────────────────────────────────────────────────

      private fun isHeanCms(baseUrl: String): Boolean {
          val host = runCatching { java.net.URI(baseUrl).host ?: "" }.getOrElse { "" }
          if (host.isEmpty()) return false
          val apiResp = fetchText("https://api.$host/query?query_string=&series_type=Comic&perPage=1&page=1&order=desc&order_by=updated_at")
          return apiResp != null && (apiResp.contains("series_slug") || apiResp.contains("series_type") ||
              (apiResp.contains("data") && apiResp.contains("thumbnail")))
      }

      // ── Iken CMS fingerprint ──────────────────────────────────────────────────

      private fun isIkenCms(baseUrl: String): Boolean {
          val host = runCatching { java.net.URI(baseUrl).host ?: "" }.getOrElse { "" }
          if (host.isEmpty()) return false
          val apiResp = fetchText("https://api.$host/api/query?page=1&perPage=1")
          return apiResp != null && apiResp.contains("posts") && apiResp.contains("postTitle")
      }

      // ── PizzaReader fingerprint ───────────────────────────────────────────────

      private fun isPizzaReader(baseUrl: String): Boolean {
          val apiResp = fetchText("$baseUrl/api/comics")
          return apiResp != null && apiResp.contains("\"comics\"") && apiResp.contains("\"url\"") &&
              apiResp.contains("\"title\"") && apiResp.contains("\"status\"")
      }

      // ── Mmrcms fingerprint ────────────────────────────────────────────────────

      private fun isMmrcms(baseUrl: String): Boolean {
          val resp = fetchText("$baseUrl/filterList?page=1&sortBy=name&asc=true")
          return resp != null && (resp.contains("media-body") || resp.contains("manga-item"))
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
