package io.github.landwarderer.futon.customsource.data

  import io.github.landwarderer.futon.customsource.domain.CustomSourceType
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import java.util.concurrent.TimeUnit

  /**
   * Probes a site's homepage and fingerprints the HTML / API responses to pick the right parser.
   *
   * STRUCTURE-FIRST PRINCIPLE: Every detector uses API response shape or HTML structural
   * markers — never raw domain-name strings as the primary signal. This makes every parser
   * immortal: if comick.live goes down and komick.live appears with the same API shape,
   * the detector will recognise it correctly without any code change.
   *
   * Detection priority:
   *  0.  Known-domain fast path — instant, no network
   *  1.  MangaSee / MangaLife  — vm.Directory or vm.Chapters JS globals
   *  2.  MangaFire style       — structural: manga-poster + chapter-images
   *  3.  Guya reader           — /api/series/ returns Guya-structured JSON
   *  4.  MangaPark             — __NEXT_DATA__ + /browse path
   *  5.  MangaThemesia         — ts_reader.run, .bsx container
   *  6.  Madara                — wp-manga, WpMangaReader
   *  7.  MangaStream           — WPMangaStream, readerarea
   *  8.  FoolSlide2            — foolslide or /read/ + /directory/
   *  9.  Manganelo             — manganelo / mangakakalot structural markers
   *  10. Zeroscans API         — /api/comics JSON with slug+name fields
   *  11. LHTranslation         — row-content-chapter, reading-detail markers
   *  12. Genkan                — /comics/ + genkan marker
   *  13. Comix.to              — .list-story-item + chapImages/lstImages structural
   *  14. ComicK API            — any domain: API returns hid+slug fields (STRUCTURE ONLY)
   *  15. Bato.to               — structural: item-text + browse?sort= markers
   *  16. NineManga             — structural: detail_list + manga_detail markers
   *  17. MangaHost             — structural: .manga-card + kw-title markers
   *  18. MangaReader           — structural: manga-poster + sort-name + manga-detail
   *  19. FanFox / MangaFox     — structural: detail-info-right + detail-main-list
   *  20. TCBScans              — structural: entry-img + latest-chapter markers
   *  21. MangaNato             — structural: panel-story-chapter-list / panel-list-story
   *  22. ReaderFront           — /graphql returns data.works JSON (STRUCTURE ONLY)
   *  23. KissManga             — structural: lstImagesUrl in script + barContent
   *  24. Cubari                — structural: /read/api/ endpoint (STRUCTURE ONLY)
   *  25. MangaPill             — structural: js-page img class
   *  26. MangaHub              — structural: media-heading + manga-page + chapter-table
   *  27. MangaHere/Foxaholic   — structural: manga-list + detail-main-list
   *  28. MangaLib              — structural: lib.social API (hid+slug) or html markers
   *  29. Mangago               — structural: book_list + booklist_item
   *  30. MangaFreak            — structural: manga_search_item / reader_images
   *  31. MangaOwl              — structural: comic-item + story-chapter-item
   *  32. NetTruyen             — structural: ModuleContent + reading-detail + truyen-tranh
   *  33. TruyenQQ              — structural: book_avatar + listChapters + .html URLs
   *  34. MangaKatana           — structural: chapter-img + id=chapters
   *  35. ZeistManga            — structural: Atom feed at /feeds/posts/default/-/Series
   *  36. Keyoapp               — structural: series_tags_page / #chapters > a
   *  37. HeanCms               — structural: API returns series_slug/series_type (STRUCTURE ONLY)
   *  38. Iken CMS              — structural: API returns posts[].postTitle (STRUCTURE ONLY)
   *  39. PizzaReader           — structural: /api/comics with comics[].url+title
   *  40. WpComics              — structural: tim-truyen + div.items + box_tootip
   *  41. Mmrcms                — structural: /filterList endpoint + media-body
   *  42. Madtheme              — structural: book-item + score/madtheme + /search/?
   *  43. Mangabox              — structural: manga-list + content-genres-item
   *  44. Liliana               — structural: y6x11p + syn-target + /filter/
   *  45. Scan CMS              — structural: chapter-list + /manga (no wp-manga)
   *  46. FmReader              — structural: manga-list-4-list / chapter-image
   *  47. Gattsu                — structural: /page/ + chapters-list (no wp-manga)
   *  48. AnimeBootstrap        — structural: list-manga-item / sort_by= + /manga?page=
   *  49. MangaDex-compatible   — REST API returns { result: ok }
   *  50. Fallback              — WEBVIEW
   */
  object CmsTypeDetector {

      private val KNOWN_DOMAIN_TYPES: Map<String, CustomSourceType> = mapOf(
          "manhwaread.com"   to CustomSourceType.WEBVIEW,
          "manhwaread.net"   to CustomSourceType.WEBVIEW,
          "comix.to"         to CustomSourceType.COMIXTO,
      )

      fun detect(baseUrl: String): CustomSourceType {
          val clean = baseUrl.trimEnd('/')

          val host = runCatching {
              java.net.URI(clean).host?.lowercase()?.removePrefix("www.")
          }.getOrNull()
          KNOWN_DOMAIN_TYPES[host]?.let { return it }

          val html = fetchText(clean) ?: return CustomSourceType.WEBVIEW

          if (html.contains("vm.Directory") || html.contains("vm.Chapters") || html.contains("vm.CurChapter")) {
              return CustomSourceType.MANGASEE
          }

          if (isMangaFire(html, clean)) {
              return CustomSourceType.MANGAFIRE
          }

          if (isGuyaApi(clean)) {
              return CustomSourceType.GUYA
          }

          // MangaPark is broken upstream (@Broken in kotatsu-parsers-redo); fall back to WebView
          if (html.contains("__NEXT_DATA__") && html.contains("mangapark")) {
              return CustomSourceType.WEBVIEW
          }

          if (html.contains("ts_reader.run") || html.contains(".bsx") || html.contains("mangathemesia")) {
              return CustomSourceType.MANGATHEMESIA
          }

          if (html.contains("wp-manga") ||
              html.contains("madara") ||
              html.contains("WpMangaReader") ||
              html.contains("mangomic-core") ||
              html.contains("summary_image") ||
              html.contains("tab-summary") ||
              html.contains("wp-manga-chapter")) {
              return CustomSourceType.MADARA
          }

          if (html.contains("WPMangaStream") || html.contains("readerarea") || html.contains("eph-num")) {
              return CustomSourceType.MANGASTREAM
          }

          if (html.contains("foolslide") || (html.contains("/read/") && html.contains("/directory/"))) {
              return CustomSourceType.FOOLSLIDE2
          }

          if (html.contains("manganelo") || html.contains("mangakakalot") ||
              html.contains("chapmanganelo") || html.contains("story_item")) {
              return CustomSourceType.MANGANELO
          }

          val zeroscansJson = fetchText("$clean/api/comics")
          if (zeroscansJson != null &&
              (zeroscansJson.contains("\"slug\"") || zeroscansJson.contains("\"name\"")) &&
              !zeroscansJson.contains("\"url\"") && !zeroscansJson.contains("\"title\"")) {
              return CustomSourceType.ZEROSCANS_API
          }

          if (html.contains("row-content-chapter") || html.contains("reading-detail") || html.contains("lhtranslation")) {
              return CustomSourceType.LHTRANSLATION
          }

          if (html.contains("/comics/") && html.contains("genkan")) {
              return CustomSourceType.GENKAN
          }

          if (isComixTo(html, clean)) {
              return CustomSourceType.COMIXTO
          }

          // ComicK API: STRUCTURE ONLY — checks API response fields (hid, slug), NOT domain name.
          // This means any ComicK clone/mirror on any domain is detected automatically.
          if (isComicK(clean)) {
              return CustomSourceType.COMICK_API
          }

          // Bato.to structural: item-text + browse?sort= is the structure fingerprint;
          // domain name check is a fast-path shortcut only
          if (html.contains("bato.to", ignoreCase = true) || html.contains("batocomic", ignoreCase = true) ||
              html.contains("comiko", ignoreCase = true) ||
              (html.contains("item-text") && html.contains("browse?sort="))) {
              return CustomSourceType.WEBVIEW  // Bato.to @Broken upstream
          }

          if (html.contains("ninemanga", ignoreCase = true) || html.contains("detail_list") ||
              html.contains("manga_detail") || html.contains("page_select")) {
              return CustomSourceType.NINEMANGA
          }

          if (html.contains("mangahost", ignoreCase = true) || html.contains("leitor.net", ignoreCase = true) ||
              (html.contains("manga-card") && html.contains("kw-title"))) {
              return CustomSourceType.MANGAHOST
          }

          if (html.contains("mangareader", ignoreCase = true) ||
              (html.contains("manga-poster") && html.contains("sort-name") && html.contains("manga-detail"))) {
              return CustomSourceType.MANGAREADER
          }

          if (html.contains("fanfox", ignoreCase = true) || html.contains("mangafox", ignoreCase = true) ||
              (html.contains("detail-info-right") && html.contains("detail-main-list"))) {
              return CustomSourceType.MANGAFOX
          }

          if (html.contains("tcbscans", ignoreCase = true) ||
              (html.contains("entry-img") && html.contains("latest-chapter") && html.contains("chapter"))) {
              return CustomSourceType.TCBSCANS
          }

          if (html.contains("manganato", ignoreCase = true) || html.contains("mangabat", ignoreCase = true) ||
              html.contains("mangabuddy", ignoreCase = true) ||
              html.contains("panel-story-chapter-list") || html.contains("panel-list-story")) {
              return CustomSourceType.MANGANATO
          }

          if (isReaderFront(clean)) {
              return CustomSourceType.READERFRONT
          }

          if (html.contains("kissmanga", ignoreCase = true) || html.contains("readcomiconline", ignoreCase = true) ||
              html.contains("lstImagesUrl") ||
              (html.contains("barContent") && html.contains("listing"))) {
              return CustomSourceType.KISSMANGA
          }

          if (html.contains("cubari", ignoreCase = true) || isCubari(clean)) {
              return CustomSourceType.CUBARI
          }

          if (html.contains("mangapill", ignoreCase = true) ||
              (html.contains("js-page") && html.contains("data-src") && html.contains("chapters"))) {
              return CustomSourceType.MANGAPILL
          }

          if (html.contains("mangahub", ignoreCase = true) ||
              (html.contains("media-heading") && html.contains("manga-page") && html.contains("chapter-table"))) {
              return CustomSourceType.MANGAHUB
          }

          if (html.contains("mangahere", ignoreCase = true) ||
              (html.contains("manga-list") && html.contains("detail-main-list") &&
               !html.contains("fanfox") && !html.contains("mangafox"))) {
              return CustomSourceType.MANGAHERE
          }

          if (html.contains("mangalib", ignoreCase = true) ||
              html.contains("ranobelib", ignoreCase = true) ||
              html.contains("lib.social", ignoreCase = true) ||
              isMangaLib(clean)) {
              return CustomSourceType.MANGALIB
          }

          if (html.contains("mangago", ignoreCase = true) ||
              (html.contains("book_list") && html.contains("booklist_item"))) {
              return CustomSourceType.MANGAGO
          }

          if (html.contains("mangafreak", ignoreCase = true) ||
              html.contains("manga_search_item") ||
              (html.contains("/Manga/") && html.contains("/Search/") && html.contains("reader_images"))) {
              return CustomSourceType.MANGAFREAK
          }

          if (html.contains("mangaowl", ignoreCase = true) ||
              (html.contains("comic-item") && html.contains("story-chapter-item"))) {
              return CustomSourceType.MANGAOWL
          }

          if (html.contains("nettruyen", ignoreCase = true) ||
              (html.contains("ModuleContent") && html.contains("reading-detail") &&
               html.contains("truyen-tranh"))) {
              return CustomSourceType.NETTRUYEN
          }

          if (html.contains("truyenqq", ignoreCase = true) ||
              (html.contains("book_avatar") && html.contains("listChapters") &&
               html.contains(".html"))) {
              return CustomSourceType.TRUYENQQ
          }

          if (html.contains("mangakatana", ignoreCase = true) ||
              (html.contains("chapter-img") && html.contains("id=\"chapters\""))) {
              return CustomSourceType.MANGAKATANA
          }

          // ZeistManga: structural — Blogger Atom feed endpoint, NOT blogger.com HTML marker
          if (isZeistManga(html, clean)) {
              return CustomSourceType.ZEISTMANGA
          }

          if (html.contains("series_tags_page") ||
              (html.contains("div.grid") && html.contains("div.group") && html.contains("#chapters")) ||
              (html.contains("keyoapp") || html.contains("asuracomic"))) {
              return CustomSourceType.KEYOAPP
          }

          // HeanCms: STRUCTURE ONLY — checks API response for series_slug/series_type fields
          if (isHeanCms(clean)) {
              return CustomSourceType.HEANCMS
          }

          // Iken CMS: STRUCTURE ONLY — checks API response for posts[].postTitle shape
          if (isIkenCms(clean)) {
              return CustomSourceType.IKEN
          }

          if (isPizzaReader(clean)) {
              return CustomSourceType.PIZZAREADER
          }

          if (html.contains("tim-truyen", ignoreCase = true) ||
              (html.contains("div.items") && html.contains("box_tootip")) ||
              (html.contains("wpcomics", ignoreCase = true))) {
              return CustomSourceType.WPCOMICS
          }

          if (html.contains("filterList", ignoreCase = true) ||
              (html.contains("media-body") && html.contains("chapter-item")) ||
              isMmrcms(clean)) {
              return CustomSourceType.MMRCMS
          }

          if (html.contains("book-item") &&
              (html.contains("score") || html.contains("madtheme") || html.contains("/search/?")) &&
              !html.contains("wp-manga")) {
              return CustomSourceType.MADTHEME
          }

          if (html.contains("manga-list") && html.contains("content-genres-item") ||
              html.contains("topview") && html.contains("list-truyen-item-wrap")) {
              return CustomSourceType.MANGABOX
          }

          if (html.contains("y6x11p") || html.contains("syn-target") ||
              (html.contains("/filter/") && html.contains("latest-updated"))) {
              return CustomSourceType.LILIANA
          }

          if (html.contains("sushiscan", ignoreCase = true) ||
              html.contains("lelscans", ignoreCase = true) ||
              (html.contains("chapter-list") && html.contains("/manga") &&
               !html.contains("wp-manga") && !html.contains("madara"))) {
              return CustomSourceType.SCAN
          }

          if (html.contains("manga-list-4-list") || html.contains("chapter-image") ||
              html.contains("fmreader", ignoreCase = true)) {
              return CustomSourceType.FMREADER
          }

          if (html.contains("gattsu", ignoreCase = true) ||
              (html.contains("/page/") && html.contains("chapters-list") &&
               !html.contains("wp-manga"))) {
              return CustomSourceType.GATTSU
          }

          if (html.contains("animebootstrap", ignoreCase = true) ||
              html.contains("list-manga-item") ||
              (html.contains("sort_by=") && html.contains("/manga?page="))) {
              return CustomSourceType.ANIMEBOOTSTRAP
          }

          val apiJson = fetchText("$clean/manga?limit=1") ?: fetchText("$clean/api/manga?limit=1")
          if (apiJson != null && (apiJson.contains("\"result\":\"ok\"") || (apiJson.contains("result") && apiJson.contains("ok")))) {
              return CustomSourceType.MANGADEX_COMPATIBLE
          }

          return CustomSourceType.WEBVIEW
      }

      fun displayName(type: CustomSourceType): String = type.label

      // ── Comix.to fingerprint ──────────────────────────────────────────────────

      private fun isComixTo(html: String, baseUrl: String): Boolean {
          if (html.contains("comix.to", ignoreCase = true)) return true
          if (html.contains("list-story-item") && html.contains("story-item-wrap")) return true
          if (html.contains("chapImages") || html.contains("lstImages")) return true
          return false
      }

      // ── ComicK API fingerprint — STRUCTURE ONLY, domain-agnostic ─────────────
      //
      // ComicK's REST API always returns objects with "hid" (chapter hash ID) and
      // "slug" fields. Any site on any domain that returns these fields from a
      // /v1.0/comic/ or /api/v1.0/comic/ endpoint IS a ComicK-compatible source.
      // We deliberately do NOT check for the string "comick" in the HTML.

      private fun isComicK(baseUrl: String): Boolean {
          val hostPart = runCatching { java.net.URI(baseUrl).host?.removePrefix("www.") }.getOrNull()
          val candidateApis = buildList {
              // Self-hosted: /v1.0/comic/ directly on the site
              add("$baseUrl/v1.0/comic/?limit=1&tachiyomi=true")
              add("$baseUrl/api/v1.0/comic/?limit=1&tachiyomi=true")
              // api.{domain} subdomain pattern (common ComicK deployments)
              if (hostPart != null) {
                  add("https://api.$hostPart/v1.0/comic/?limit=1&tachiyomi=true")
              }
          }
          for (apiUrl in candidateApis) {
              val resp = fetchText(apiUrl) ?: continue
              // ComicK API always returns objects with "hid" (chapter hash) and "slug" fields
              if (resp.contains("\"hid\"") && resp.contains("\"slug\"")) return true
              if (resp.contains("\"title\"") && resp.contains("\"hid\"")) return true
          }
          return false
      }

      // ── ReaderFront fingerprint — STRUCTURE ONLY ──────────────────────────────

      private fun isReaderFront(baseUrl: String): Boolean {
          val gql = fetchText("$baseUrl/graphql?query={works{name}}")
              ?: fetchText("$baseUrl/api/graphql?query={works{name}}")
          return gql != null && gql.contains("works") && gql.contains("name")
      }

      // ── Cubari fingerprint — STRUCTURE ONLY ───────────────────────────────────

      private fun isCubari(baseUrl: String): Boolean {
          val apiResp = fetchText("$baseUrl/read/api/gist/series/")
              ?: fetchText("$baseUrl/read/api/guya/series/")
          return apiResp != null && apiResp.startsWith("{") && apiResp.contains("title")
      }

      // ── MangaLib API fingerprint ──────────────────────────────────────────────

      private fun isMangaLib(baseUrl: String): Boolean {
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

      // ── Guya API fingerprint — STRUCTURE ONLY ────────────────────────────────

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

      // ── ZeistManga fingerprint — STRUCTURE ONLY (Atom feed shape) ────────────
      //
      // We probe the Blogger Atom feed endpoint regardless of whether the HTML
      // mentions "blogger.com" or "blogspot.com". A site that migrated off Blogger
      // hosting but kept the Blogger backend will still expose this endpoint.

      private fun isZeistManga(html: String, baseUrl: String): Boolean {
          if (html.contains("feeds/posts/default")) return true
          val feedResp = fetchText("$baseUrl/feeds/posts/default/-/Series?alt=json&max-results=1")
              ?: fetchText("$baseUrl/feeds/posts/default/-/Manga?alt=json&max-results=1")
          if (feedResp != null && feedResp.contains("\"feed\"") && feedResp.contains("entry")) return true
          return false
      }

      // ── HeanCms fingerprint — STRUCTURE ONLY ─────────────────────────────────
      //
      // Tries both the api.{domain} subdomain pattern AND {domain}/api/ path
      // so self-hosted instances and subpath deployments are both detected.
      // Matches on series_slug or series_type fields in the JSON response.

      private fun isHeanCms(baseUrl: String): Boolean {
          val host = runCatching { java.net.URI(baseUrl).host ?: "" }.getOrElse { "" }
          if (host.isEmpty()) return false
          val queryPath = "/query?query_string=&series_type=Comic&perPage=1&page=1&order=desc&order_by=updated_at"
          val candidateUrls = listOf(
              "https://api.$host$queryPath",
              "$baseUrl/api$queryPath",
              "$baseUrl$queryPath",
          )
          for (url in candidateUrls) {
              val apiResp = fetchText(url) ?: continue
              if (apiResp.contains("series_slug") || apiResp.contains("series_type") ||
                  (apiResp.contains("data") && apiResp.contains("thumbnail"))) return true
          }
          return false
      }

      // ── Iken CMS fingerprint — STRUCTURE ONLY ────────────────────────────────
      //
      // Tries api.{domain} subdomain AND {domain}/api/ path. Matches on the
      // posts[].postTitle shape which is unique to Iken CMS.

      private fun isIkenCms(baseUrl: String): Boolean {
          val host = runCatching { java.net.URI(baseUrl).host ?: "" }.getOrElse { "" }
          if (host.isEmpty()) return false
          val queryPath = "/api/query?page=1&perPage=1"
          val candidateUrls = listOf(
              "https://api.$host$queryPath",
              "$baseUrl$queryPath",
          )
          for (url in candidateUrls) {
              val apiResp = fetchText(url) ?: continue
              if (apiResp.contains("posts") && apiResp.contains("postTitle")) return true
          }
          return false
      }

      // ── PizzaReader fingerprint — STRUCTURE ONLY ──────────────────────────────

      private fun isPizzaReader(baseUrl: String): Boolean {
          val apiResp = fetchText("$baseUrl/api/comics")
          return apiResp != null && apiResp.contains("\"comics\"") && apiResp.contains("\"url\"") &&
              apiResp.contains("\"title\"") && apiResp.contains("\"status\"")
      }

      // ── Mmrcms fingerprint — STRUCTURE ONLY ───────────────────────────────────

      private fun isMmrcms(baseUrl: String): Boolean {
          val resp = fetchText("$baseUrl/filterList?page=1&sortBy=name&asc=true")
          return resp != null && (resp.contains("media-body") || resp.contains("manga-item"))
      }

      private fun fetchText(url: String): String? = runCatching {
          val req = Request.Builder()
              .url(url)
              .header("User-Agent", BROWSER_UA)
              .get()
              .build()
          httpClient.newCall(req).execute().use { resp ->
              if (!resp.isSuccessful) null else resp.body?.string()?.take(MAX_BYTES)
          }
      }.getOrNull()

      private const val MAX_BYTES = 65_536
      private const val BROWSER_UA =
          "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

      private val httpClient: OkHttpClient by lazy {
          OkHttpClient.Builder()
              .connectTimeout(10, TimeUnit.SECONDS)
              .readTimeout(15, TimeUnit.SECONDS)
              .followRedirects(true)
              .build()
      }
  }
  