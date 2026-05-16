package io.github.landwarderer.futon.customsource.data

    import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
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
     * Parser for comix.to and sites using the same CMS layout.
     *
     * "Foolproof" design — tries every known URL pattern and every known CSS
     * selector variant in sequence, falling back to progressively broader
     * heuristics so the parser still works if the site rearranges its layout.
     *
     * URL patterns tried (in order):
     *   Browse : /, /?page=N, /comic-genre/all/?page=N, /category/all/?page=N,
     *            /manga/?page=N, /manga-list/?page=N, /latest/?page=N,
     *            /comics/?page=N, /advanced-search?page=N
     *   Search : /search-comic?q=, /?s=, /search?keyword=, /search?q=
     *   Detail : resolved from the list anchor href
     *   Chapter: resolved from the detail page chapter list
     *
     * CSS selector cascade (broadest last):
     *   1. .list-story-item, .story-item, .manga-item — canonical comix.to
     *   2. .book-item, article.manga, .itemupdate     — classic variants
     *   3. .content-homepage-item, li[class*=item]    — homepage widgets
     *   4. .card, .comic-card, .series-card           — Bootstrap variants
     *   5. article[class], div[class*=manga], div[class*=comic] — ultra-broad
     *   6. Nuclear: any <a> with a child <img> and a plausible title nearby
     */
    class ComixToHtmlParser(
        private val customSource: CustomMangaSource,
    ) {
        private val baseUrl get() = customSource.source.cleanBaseUrl

        fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
            val query = filter?.query?.trim()
            val tag = filter?.tags?.firstOrNull()
            return when {
                !query.isNullOrBlank() -> searchManga(query, offset)
                tag != null -> browseByGenre(tag.key, offset, order)
                else -> browseList(offset, order)
            }
        }

        fun getGenres(): Set<MangaTag> {
            val genreUrls = listOf(
                "$baseUrl/comic-genre/",
                "$baseUrl/genre/",
                "$baseUrl/genres/",
                "$baseUrl/category/",
            )
            for (url in genreUrls) {
                val doc = runCatching { fetchDocument(url) }.getOrNull() ?: continue
                val tags = mutableSetOf<MangaTag>()
                doc.select(
                    ".panel-body a[href*=genre], .genres-list a, a[href*=comic-genre], " +
                    "a[href*=/genre/], a[href*=/category/], .genre-list a, .tag-list a"
                ).forEach { a ->
                    val href = a.attr("href").trimEnd('/')
                    val key = href.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@forEach
                    val title = a.text().trim().ifEmpty { return@forEach }
                    if (title.length > 1 && !title.contains("All", ignoreCase = true)) {
                        tags += MangaTag(title = title, key = key, source = customSource)
                    }
                }
                if (tags.isNotEmpty()) return tags
            }
            return emptySet()
        }

        fun getDetails(manga: Manga): Manga {
            val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
            return runCatching {
                val doc = fetchDocument(pageUrl)

                val title = doc.selectFirst(
                    ".detail-inf h1, .detail-info h1, .story-title, h1.title-detail, " +
                    ".series-title, .manga-title, h1[class*=title], .entry-title, h1"
                )?.text()?.trim() ?: manga.title

                val coverImg = doc.selectFirst(
                    ".detail-info-cover img, .story-cover img, .book-thumbnail img, " +
                    ".manga-cover img, .series-cover img, .thumb img, " +
                    ".info-image img, [class*=cover] img, [class*=thumb] img"
                )
                val coverUrl = resolveImageUrl(coverImg, manga.coverUrl ?: "")

                val descEl = doc.selectFirst(
                    ".detail-content p, .story-summary p, .summary-content, " +
                    ".description p, .synopsis, .manga-description, [class*=desc] p"
                )
                val description = descEl?.text()?.trim()

                val statusText = doc.select(
                    ".detail-info-right p, .story-detail-right p, .info-status, .series-status"
                ).firstOrNull { it.text().contains("Status", ignoreCase = true) }
                    ?.text()?.lowercase()
                    ?: doc.selectFirst(".status span, span.status, [class*=status]")?.text()?.lowercase()
                val state = when {
                    statusText == null -> MangaState.ONGOING
                    "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                    "hiatus" in statusText || "on hold" in statusText -> MangaState.PAUSED
                    "cancel" in statusText || "drop" in statusText -> MangaState.ABANDONED
                    else -> MangaState.ONGOING
                }

                val chapters = loadChapterList(doc, pageUrl)

                manga.copy(
                    title = title,
                    coverUrl = coverUrl,
                    largeCoverUrl = coverUrl,
                    description = description,
                    state = state,
                    chapters = chapters,
                )
            }.getOrElse { manga }
        }

        fun getPages(chapter: MangaChapter): List<MangaPage> {
            return runCatching {
                val doc = fetchDocument(chapter.url)

                val scriptImages = extractImagesFromScript(doc)
                if (scriptImages.isNotEmpty()) return@runCatching scriptImages.mapIndexed { i, url ->
                    MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
                }

                val images = doc.select(
                    ".reading-detail img, .reading-content img, " +
                    ".page-chapter img, img.chapter-img, " +
                    "#vungdoc img, .container-chapter-reader img, " +
                    ".chapter-images img, .comic-page img, " +
                    "[class*=reader] img, [class*=chapter] img"
                )
                images.mapIndexedNotNull { index, img ->
                    val url = resolveImageUrl(img, "")
                    if (url.isEmpty() || url.contains("data:image") || url.contains("loading")) null
                    else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
                }
            }.getOrElse { emptyList() }
        }

        // ── Private ───────────────────────────────────────────────────────────────

        private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
            val page = offset / PAGE_SIZE + 1
            val sort = when (order) {
                SortOrder.POPULARITY -> "topview"
                SortOrder.NEWEST     -> "newest"
                SortOrder.RATING     -> "topview"
                else                 -> "latest"
            }
            val urls = buildList {
                if (page == 1) add("$baseUrl/")
                add("$baseUrl/?page=$page")
                add("$baseUrl/comic-genre/all/?page=$page&sort=$sort")
                add("$baseUrl/category/all/?page=$page")
                add("$baseUrl/manga/?page=$page&sort=$sort")
                add("$baseUrl/manga-list/?page=$page&sort=$sort")
                add("$baseUrl/manga-list.html?page=$page")
                add("$baseUrl/latest/?page=$page")
                add("$baseUrl/comics/?page=$page")
                add("$baseUrl/advanced-search?sort=$sort&page=$page")
            }
            for (url in urls) {
                val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
                if (!result.isNullOrEmpty()) return result
            }
            return emptyList()
        }

        private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
            val page = offset / PAGE_SIZE + 1
            val urls = listOf(
                "$baseUrl/comic-genre/$genreKey/?page=$page",
                "$baseUrl/genre/$genreKey/?page=$page",
                "$baseUrl/category/$genreKey/?page=$page",
                "$baseUrl/manga/?genre=$genreKey&page=$page",
            )
            for (url in urls) {
                val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
                if (!result.isNullOrEmpty()) return result
            }
            return emptyList()
        }

        private fun searchManga(query: String, offset: Int): List<Manga> {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val page = offset / PAGE_SIZE + 1
            val urls = listOf(
                "$baseUrl/search-comic?q=$encoded&page=$page",
                "$baseUrl/?s=$encoded&post_type=comics&paged=$page",
                "$baseUrl/search?keyword=$encoded&page=$page",
                "$baseUrl/search?q=$encoded&page=$page",
                "$baseUrl/search?s=$encoded&page=$page",
            )
            for (url in urls) {
                val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
                if (!result.isNullOrEmpty()) return result
            }
            return emptyList()
        }

        private fun parseMangaListPage(doc: Document): List<Manga> {
            val items = doc.select(
                ".list-story-item, .story-item, .manga-item, " +
                ".book-item, article.manga, .itemupdate"
            ).ifEmpty {
                doc.select(".content-homepage-item, li[class*=item]")
            }.ifEmpty {
                doc.select(".card.manga-card, .comic-card, .series-card, .manga-card")
            }.ifEmpty {
                doc.select("article[class], .grid-item, .list-item")
            }.ifEmpty {
                doc.select("div[class*=manga], div[class*=comic], div[class*=series], article")
                    .filter { it.selectFirst("a[href]") != null && it.selectFirst("img") != null }
            }

            val result = items.mapNotNull { parseMangaItem(it) }
            if (result.isNotEmpty()) return result

            return doc.select("a[href]").filter { a ->
                val href = a.attr("href")
                (href.contains("/comic/") || href.contains("/manga/") || href.contains("/series/")) &&
                a.selectFirst("img") != null
            }.mapNotNull { a ->
                val pageUrl = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val img = a.selectFirst("img") ?: return@mapNotNull null
                val coverUrl = resolveImageUrl(img, "")
                val title = a.selectFirst("h3, h2, h4, .title, .name, [class*=title]")?.text()?.trim()
                    ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
                    ?: img.attr("alt").trim().takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                buildManga(title, pageUrl, coverUrl)
            }
        }

        private fun parseMangaItem(el: Element): Manga? {
            val anchor = el.selectFirst(
                "a[href*=/comic/], a[href*=/manga/], a[href*=/series/], " +
                "h3 a, h2 a, .title a, [class*=title] a, a[href]"
            ) ?: return null
            val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
            if (pageUrl == baseUrl || pageUrl == "$baseUrl/") return null
            val title = el.selectFirst("h3, h2, .story-title, .title, .name, [class*=title]")?.text()?.trim()
                ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: anchor.text().trim().takeIf { it.isNotEmpty() }
                ?: return null
            if (title.length < 2) return null
            val coverImg = el.selectFirst("img")
            val coverUrl = resolveImageUrl(coverImg, "")
            return buildManga(title, pageUrl, coverUrl)
        }

        private fun loadChapterList(doc: Document, mangaUrl: String): List<MangaChapter> {
            val rows = doc.select(
                ".list-chapter li, .chapter-list li, " +
                ".row.chapter-li, ul.list-chapter a, " +
                "[class*=chapter-list] li, [class*=chapter-item], " +
                "#chapterlist li, .chapters li"
            )
            return rows.mapIndexedNotNull { i, el ->
                val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapIndexedNotNull null
                val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val rawTitle = el.selectFirst(".chapter-name, .chapter-text, .chapternum")?.text()?.trim()
                    ?: a.text().trim().ifEmpty { "Chapter ${i + 1}" }
                val number = CHAPTER_NUMBER_RE.find(rawTitle)?.groupValues?.getOrNull(1)
                    ?.toFloatOrNull() ?: (i + 1).toFloat()
                MangaChapter(
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
            }.reversed()
        }

        private fun extractImagesFromScript(doc: Document): List<String> {
            val scriptContent = doc.select("script").joinToString("\n") { it.html() }
            val match = CHAP_IMAGES_RE.find(scriptContent) ?: return emptyList()
            val arrayText = match.groupValues[1]
            return URL_RE.findAll(arrayText).map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.contains(".jpg") || it.contains(".png") || it.contains(".webp") }
                .toList()
        }

        private fun resolveImageUrl(img: Element?, fallback: String): String {
            if (img == null) return fallback
            val raw = sequenceOf("data-src", "data-lazy-src", "data-original", "src")
                .map { img.attr(it) }
                .firstOrNull { it.isNotEmpty() }
                ?: fallback
            return raw.fixProtocol()
        }

        /**
         * Fetches a URL with full browser-like headers so the server does not
         * block the request as a bot. Sends Accept, Accept-Language,
         * Accept-Encoding, Sec-Fetch headers, and Referer.
         */
        private fun fetchDocument(url: String): Document {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Referer", baseUrl)
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Cache-Control", "max-age=0")
                .get().build()
            return httpClient.newCall(req).execute().use { resp ->
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
            private const val PAGE_SIZE = 24
            private const val USER_AGENT =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
            private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")
            private val CHAP_IMAGES_RE = Regex(
                """(?:chapImages|lstImages|imgArr|var\s+images)\s*=\s*(\[.*?])""",
                RegexOption.DOT_MATCHES_ALL
            )
            private val URL_RE = Regex(""""(https?://[^"]+)"""")

            private val httpClient: OkHttpClient by lazy {
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    // Enable automatic gzip decompression
                    .addInterceptor { chain ->
                        val req = chain.request().newBuilder()
                            .header("Accept-Encoding", "gzip, deflate, br")
                            .build()
                        chain.proceed(req)
                    }
                    .build()
            }
        }
    }
  