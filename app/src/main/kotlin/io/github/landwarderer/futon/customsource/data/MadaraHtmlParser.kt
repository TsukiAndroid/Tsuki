package io.github.landwarderer.futon.customsource.data

  import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
  import okhttp3.FormBody
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import org.jsoup.Jsoup
  import org.jsoup.nodes.Document
  import org.jsoup.nodes.Element
  import org.koitharu.kotatsu.parsers.model.ContentRating
  import org.koitharu.kotatsu.parsers.model.Manga
  import org.koitharu.kotatsu.parsers.model.MangaChapter
  import org.koitharu.kotatsu.parsers.model.MangaListFilter
  import org.koitharu.kotatsu.parsers.model.MangaPage
  import org.koitharu.kotatsu.parsers.model.MangaState
  import org.koitharu.kotatsu.parsers.model.MangaTag
  import org.koitharu.kotatsu.parsers.model.SortOrder
  import java.util.concurrent.TimeUnit

  /**
   * HTML scraper for sites built on the WordPress Madara manga theme.
   *
   * Madara is the most widely-deployed manga CMS, powering hundreds of sites.
   * All selector patterns follow the canonical Madara markup with cascading
   * fallbacks so sites that override default templates still work.
   *
   * Hotlink protection: Madara CDNs typically require a valid browser
   * User-Agent AND Referer matching the site domain. Both are injected by
   * [MangaSourceHeaderInterceptor] for cover images, and by [fetchDocument]
   * for page fetches. Do not use a bot-like UA anywhere in this parser.
   *
   * Image attribute resolution order (widest compatibility):
   *   data-src → data-lazy-src → data-srcset (first URL) → srcset (first URL) → src
   * This covers: standard WP lazy-load, Jetpack lazy-load, EWWW Image Optimizer,
   * ShortPixel, Smush, and every other popular WP image optimisation plugin.
   */
  class MadaraHtmlParser(
      private val customSource: CustomMangaSource,
  ) {

      private val baseUrl get() = customSource.source.cleanBaseUrl

      // ── Public API ────────────────────────────────────────────────────────────

      fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
          val query = filter?.query?.trim()
          val tag = filter?.tags?.firstOrNull()
          return when {
              !query.isNullOrBlank() -> searchManga(query, offset)
              tag != null -> browseByGenre(tag.key, offset, order)
              else -> latestManga(offset, order)
          }
      }

      fun getGenres(): Set<MangaTag> {
          // Candidate URLs tried in priority order. Genre-specific paths first,
          // then common content-type slugs, then the site root (homepage sidebars
          // often carry genre checkboxes in Madara themes).
          val candidateUrls = listOf(
              "$baseUrl/genre/",
              "$baseUrl/manga-genre/",
              "$baseUrl/manhwa/",
              "$baseUrl/manga/",
              "$baseUrl/manhwa-list/",
              "$baseUrl/manhua/",
              "$baseUrl/webtoon/",
              "$baseUrl/comic/",
              baseUrl,
          )
          val genreCheckboxSel = ".checkbox-manga-genre .checkbox, .manga-genres .checkbox, " +
              ".c-checkbox-list .checkbox, a[href*=manga-genre/], a[href*=/genre/], " +
              "a.genre, a[href*=genre], .genre-item a, .cat-item a[href*=genre]"
          var doc: Document? = null
          for (url in candidateUrls) {
              val candidate = runCatching { fetchDocument(url) }.getOrNull() ?: continue
              if (candidate.select(genreCheckboxSel).isNotEmpty()) {
                  doc = candidate
                  break
              }
          }
          if (doc == null) {
              for (url in candidateUrls) {
                  doc = runCatching { fetchDocument(url) }.getOrNull()
                  if (doc != null) break
              }
          }
          doc ?: return emptySet()
          val tags = mutableSetOf<MangaTag>()
          doc.select(".checkbox-manga-genre .checkbox, .manga-genres .checkbox, .c-checkbox-list .checkbox").forEach { el ->
              val input = el.selectFirst("input") ?: return@forEach
              val key = (input.attr("value").takeIf { it.isNotEmpty() }
                  ?: input.attr("data-value")).trim().ifEmpty { return@forEach }
              val title = el.selectFirst("label")?.text()?.trim()?.ifEmpty { null } ?: key
              tags += MangaTag(title = title, key = key, source = customSource)
          }
          if (tags.isNotEmpty()) return tags
          doc.select("a[href*=manga-genre/], a[href*=/genre/]").forEach { a ->
              val href = a.attr("href").trimEnd('/')
              val key = href.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@forEach
              val title = a.text().trim().ifEmpty { return@forEach }
              tags += MangaTag(title = title, key = key, source = customSource)
          }
          return tags
      }

      fun getDetails(manga: Manga): Manga {
          val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
          val doc = fetchDocument(pageUrl)

          val title = doc.selectFirst("div.post-title h1, div.post-title h3")
              ?.text()?.trim() ?: manga.title

          val coverImg = doc.selectFirst("div.summary_image img, div.tab-summary img")
          val coverUrl = resolveImageUrl(coverImg, manga.coverUrl)

          val description = doc.selectFirst(
                "div.summary__content, div.description-summary, " +
                ".post-content_item .summary-content, #editdescription, " +
                ".comic_content, div.manga-about, div.description, " +
                ".desc, .entry-content"
            )?.let { el ->
                el.select("p").joinToString("\n") { it.text() }.trim()
                    .ifEmpty { el.ownText().trim().ifEmpty { el.text().trim() } }
            }?.takeIf { it.isNotBlank() }

          val statusText = doc.select("div.post-status .summary-content").getOrNull(1)
              ?.text()?.lowercase()?.trim()
          val state = when {
              statusText == null -> MangaState.ONGOING
              "complet" in statusText -> MangaState.FINISHED
              "hiatus" in statusText || "on hold" in statusText -> MangaState.PAUSED
              "cancel" in statusText || "abandon" in statusText -> MangaState.ABANDONED
              else -> MangaState.ONGOING
          }

          val chapters = loadChapterList(doc, pageUrl)

          return Manga(
              id = manga.id,
              title = title,
              altTitles = manga.altTitles,
              url = manga.url,
              publicUrl = manga.publicUrl,
              rating = manga.rating,
              contentRating = manga.contentRating,
              coverUrl = coverUrl,
              tags = manga.tags,
              state = state,
              authors = manga.authors,
              largeCoverUrl = coverUrl,
              description = description,
              chapters = chapters,
              source = customSource,
          )
      }

      fun getPages(chapter: MangaChapter): List<MangaPage> {
          val doc = fetchDocument(chapter.url)
          val images = doc.select(
              "div.page-break img, div.reading-content img, .wp-manga-chapter-img img, " +
              "#reader img, [class*=reader] img, [class*=chapter-image] img"
          )
          return images.mapIndexedNotNull { index, img ->
              val url = resolveImageUrl(img, "")
              if (url.isEmpty()) null
              else MangaPage(
                  id = (chapter.id * 1000L + index),
                  url = url,
                  preview = null,
                  source = customSource,
              )
          }
      }

      // ── List fetching ─────────────────────────────────────────────────────────

      private fun latestManga(offset: Int, order: SortOrder?): List<Manga> {
          val page = offset / PAGE_SIZE
          val orderParam = when (order) {
              SortOrder.POPULARITY -> "trending"
              SortOrder.RATING -> "rating"
              SortOrder.NEWEST -> "new-manga"
              else -> "latest"
          }
          val ajaxOrder = when (orderParam) {
              "trending" -> "meta_value_num" to "_wp_manga_views"
              "rating" -> "meta_value_num" to "_wp_manga_average_rating"
              "new-manga" -> "date" to ""
              else -> "modified" to ""
          }
          val ajaxResult = runCatching { fetchListAjax(page, ajaxOrder.first, ajaxOrder.second) }.getOrNull()
          if (!ajaxResult.isNullOrEmpty()) return ajaxResult

          val paged = page + 1
          val slugsToTry = listOf("manga", "manhwa", "manhua", "webtoon", "comic")
          for (slug in slugsToTry) {
              val result = runCatching {
                  parseMangaListPage(fetchDocument("$baseUrl/$slug/?m_orderby=$orderParam&paged=$paged"))
              }.getOrNull()
              if (!result.isNullOrEmpty()) return result
          }
          return runCatching {
              parseMangaListPage(fetchDocument("$baseUrl/?m_orderby=$orderParam&paged=$paged"))
          }.getOrElse { emptyList() }
      }

      private fun searchManga(query: String, offset: Int): List<Manga> {
          val encoded = java.net.URLEncoder.encode(query, "UTF-8")
          val page = offset / PAGE_SIZE + 1
          val url = "$baseUrl/?s=$encoded&post_type=wp-manga&paged=$page"
          return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
      }

      private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
            val page = offset / PAGE_SIZE + 1
            val orderParam = when (order) {
                SortOrder.POPULARITY -> "trending"
                SortOrder.RATING -> "rating"
                SortOrder.NEWEST -> "new-manga"
                else -> "latest"
            }
            // Madara forks differ on the content-type base slug: /manhwa/, /manga/, /manhua/, etc.
            // Try each candidate so genre filtering works across all variants (manhwaread uses /manhwa/).
            val slugsToTry = listOf("manhwa", "manga", "manhwa-list", "manhua", "webtoon", "comic", "")
            for (slug in slugsToTry) {
                val base = if (slug.isEmpty()) baseUrl else "$baseUrl/$slug"
                val url = "$base/?genre=$genreKey&m_orderby=$orderParam&paged=$page"
                val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
                if (!result.isNullOrEmpty()) return result
            }
            // Some forks use a direct genre path: /genre/SLUG/
            return runCatching {
                parseMangaListPage(fetchDocument("$baseUrl/genre/$genreKey/?m_orderby=$orderParam&paged=$page"))
            }.getOrElse { emptyList() }
        }

      private fun fetchListAjax(page: Int, orderby: String, metaKey: String): List<Manga> {
          val bodyBuilder = FormBody.Builder()
              .add("action", "madara_load_more")
              .add("page", page.toString())
              .add("template", "madara-core/content/content-archive")
              .add("vars[orderby]", orderby)
              .add("vars[template]", "archive")
              .add("vars[sidebar]", "full")
              .add("vars[post_type]", "wp-manga")
              .add("vars[posts_per_page]", PAGE_SIZE.toString())
          if (metaKey.isNotEmpty()) {
              bodyBuilder.add("vars[meta_key]", metaKey)
          }
          val request = Request.Builder()
              .url("$baseUrl/wp-admin/admin-ajax.php")
              .post(bodyBuilder.build())
              .header("User-Agent", USER_AGENT)
              .header("Referer", baseUrl)
              .build()
          val resp = ajaxClient.newCall(request).execute()
          val html = resp.use { it.body?.string() ?: return emptyList() }
          return parseMangaListItems(Jsoup.parse(html, baseUrl))
      }

      private fun parseMangaListPage(doc: Document): List<Manga> = parseMangaListItems(doc)

      private fun parseMangaListItems(doc: Document): List<Manga> {
          val items = doc.select("div.page-item-detail, div.c-tabs-item__content")
              .ifEmpty { doc.select(".c-image-inner").map { it.parent() ?: it } }
              .ifEmpty { doc.select(".manga-item, .comics-item, li.manga-item, .bs, .bsx") }
              .ifEmpty { doc.select("article.manga, div.manga-entry, .item-thumb") }
          return items.mapNotNull { parseMangaItem(it) }
      }

      private fun parseMangaItem(el: Element): Manga? {
          val anchor = el.selectFirst(".post-title a, h3.h5 a, h3 a, h5 a, a.manga-item__link") ?: return null
          val title = anchor.text().trim().takeIf { it.isNotEmpty() } ?: return null
          val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
          val coverImg = el.selectFirst("img")
          val coverUrl = resolveImageUrl(coverImg, "")
          return buildManga(title, pageUrl, coverUrl)
      }

      // ── Chapter fetching ──────────────────────────────────────────────────────

      private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
            // Strategy 0: Mangomic / custom Madara forks inline chapters as <a data-id> elements
            // directly inside a .chapters-list container — no AJAX endpoint exists on these sites.
            // Check this FIRST to avoid a wasted network round-trip on every detail page.
            val directAnchors = doc.select(
                ".chapters-list a.chapter-item, .chapters-list a[data-id], " +
                ".chapter-list a.chapter-item, .chapter-list a[data-id]"
            )
            if (directAnchors.isNotEmpty()) {
                return directAnchors.mapIndexedNotNull { i, a -> chapterFromAnchor(a, i) }
            }

            // Strategy 1: Modern Madara 2.x+ — POST to /ajax/chapters/ (no postId needed).
            // Sites published after ~2022 (e.g. manhwaread.com) use this endpoint.
            val newEndpoint = runCatching { fetchChaptersNewEndpoint(pageUrl) }.getOrNull()
            if (!newEndpoint.isNullOrEmpty()) return newEndpoint

            // Strategy 2: Classic Madara — hidden input carries the postId, AJAX to admin-ajax.php.
            val postId = doc.selectFirst(
                "input#manga-chapters-holder, .rating-post-id, [id=manga-chapters-holder], " +
                "[class*=chapters-holder][data-id]"
            )?.attr("data-id")

            if (!postId.isNullOrEmpty()) {
                val ajax = runCatching { fetchChaptersAjax(postId, pageUrl) }.getOrNull()
                if (!ajax.isNullOrEmpty()) return ajax
            }

            // Strategy 3: Chapters already embedded in the DOM.
            val inline = doc.select("li.wp-manga-chapter")
                .mapIndexedNotNull { i, el -> chapterFromElement(el, i) }
            if (inline.isNotEmpty()) return inline.reversed()

            // Strategy 4: Alternate containers used by some Madara forks.
            return doc.select(
                ".listing-chapters_wrap li, .eph-num, .version-chap li, " +
                "ul.row-content-chapter li, #chapterlist li"
            ).mapIndexedNotNull { i, el -> chapterFromElement(el, i) }.reversed()
        }

        /** Modern Madara 2.x chapter endpoint — does not require the post ID. */
        private fun fetchChaptersNewEndpoint(mangaUrl: String): List<MangaChapter> {
            val url = mangaUrl.trimEnd('/') + "/ajax/chapters/"
            val request = Request.Builder()
                .url(url)
                .post(FormBody.Builder().build())
                .header("User-Agent", USER_AGENT)
                .header("Referer", mangaUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val resp = runCatching { ajaxClient.newCall(request).execute() }.getOrNull()
                ?: return emptyList()
            if (!resp.isSuccessful) { resp.close(); return emptyList() }
            val html = resp.use { it.body?.string() } ?: return emptyList()
            if (html.isBlank() || html.trim() == "0") return emptyList()
            return Jsoup.parse(html, mangaUrl)
                .select("li.wp-manga-chapter")
                .mapIndexedNotNull { i, el -> chapterFromElement(el, i) }
                .reversed()
        }

      private fun fetchChaptersAjax(postId: String, referer: String): List<MangaChapter> {
          val body = FormBody.Builder()
              .add("action", "manga_get_chapters")
              .add("manga", postId)
              .build()
          val request = Request.Builder()
              .url("$baseUrl/wp-admin/admin-ajax.php")
              .post(body)
              .header("User-Agent", USER_AGENT)
              .header("Referer", referer)
              .build()
          val resp = ajaxClient.newCall(request).execute()
          val html = resp.use { it.body?.string() ?: return emptyList() }
          return Jsoup.parse(html, baseUrl)
              .select("li.wp-manga-chapter")
              .mapIndexedNotNull { i, el -> chapterFromElement(el, i) }
              .reversed()
      }

      private fun chapterFromElement(el: Element, fallbackIndex: Int): MangaChapter? {
          val anchor = el.selectFirst("a") ?: return null
          val url = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
          val rawTitle = anchor.text().trim().ifEmpty { "Chapter ${fallbackIndex + 1}" }
          val number = CHAPTER_NUMBER_RE.find(rawTitle)?.groupValues?.getOrNull(1)
              ?.toFloatOrNull() ?: (fallbackIndex + 1).toFloat()
          return MangaChapter(
              id = url.hashCode().toLong(),
              title = rawTitle,
              number = number,
              volume = 0,
              url = url,
              scanlator = null,
              uploadDate = 0L,
              branch = null,
              source = customSource,
          )
      }


        /** Parses a chapter where the element itself is the anchor (mangomic-core / custom Madara forks). */
        private fun chapterFromAnchor(anchor: Element, fallbackIndex: Int): MangaChapter? {
            val url = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
            val rawTitle = (
                anchor.selectFirst(".chapter-item__name")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: anchor.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: anchor.ownText().trim().takeIf { it.isNotEmpty() }
            ) ?: "Chapter ${fallbackIndex + 1}"
            val number = CHAPTER_NUMBER_RE.find(rawTitle)?.groupValues?.getOrNull(1)
                ?.toFloatOrNull() ?: (fallbackIndex + 1).toFloat()
            return MangaChapter(
                id = url.hashCode().toLong(),
                title = rawTitle,
                number = number,
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = customSource,
            )
        }

      // ── Helpers ───────────────────────────────────────────────────────────────

      /**
       * Resolves the best available image URL from an img element.
       * Tries every lazy-load attribute in priority order so the real
       * cover is returned even when multiple WP image plugins are active.
       */
      private fun resolveImageUrl(img: Element?, fallback: String? = null): String {
          if (img == null) return fallback.orEmpty()
          // srcset: take the first (smallest) URL, which is usually the thumbnail
          val srcsetFirst = listOf(img.attr("data-srcset"), img.attr("srcset"))
              .firstOrNull { it.isNotEmpty() }
              ?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
          return listOf(
              img.attr("data-src"),
              img.attr("data-lazy-src"),
              img.attr("data-original"),
              img.attr("data-url"),
              srcsetFirst ?: "",
              img.attr("src"),
          ).firstOrNull { !it.isNullOrEmpty() && !it.contains("data:image") && !it.contains("placeholder") }
              ?.fixProtocol() ?: fallback.orEmpty()
      }

      private fun fetchDocument(url: String): Document {
          val request = Request.Builder()
              .url(url)
              .header("User-Agent", USER_AGENT)
              .header("Referer", baseUrl)
              .get()
              .build()
          return httpClient.newCall(request).execute().use { resp ->
              Jsoup.parse(resp.body?.string() ?: "", url)
          }
      }

      private fun buildManga(title: String, pageUrl: String, coverUrl: String): Manga {
          val relativePath = runCatching {
              val uri = java.net.URI(pageUrl)
              uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
          }.getOrElse { pageUrl }
          return Manga(
              id = pageUrl.hashCode().toLong(),
              title = title,
              altTitles = emptySet(),
              url = relativePath,
              publicUrl = pageUrl,
              rating = 0f,
              contentRating = ContentRating.SAFE,
              coverUrl = coverUrl,
              tags = emptySet(),
              state = MangaState.ONGOING,
              authors = emptySet(),
              largeCoverUrl = coverUrl,
              description = null,
              chapters = null,
              source = customSource,
          )
      }

      private fun String?.fixProtocol(): String {
          if (this == null) return ""
          if (startsWith("//")) return "https:$this"
          return this
      }

      companion object {
          private const val PAGE_SIZE = 16
          private const val USER_AGENT =
              "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
          private val CHAPTER_NUMBER_RE = Regex("""[Cc]hapter[s]?\s*([\d.]+)""")

          private val httpClient: OkHttpClient by lazy {
              OkHttpClient.Builder()
                  .connectTimeout(15, TimeUnit.SECONDS)
                  .readTimeout(20, TimeUnit.SECONDS)
                  .followRedirects(true)
                  .build()
          }

          private val ajaxClient: OkHttpClient by lazy {
              OkHttpClient.Builder()
                  .connectTimeout(8, TimeUnit.SECONDS)
                  .readTimeout(12, TimeUnit.SECONDS)
                  .followRedirects(true)
                  .build()
          }
      }
  }
  