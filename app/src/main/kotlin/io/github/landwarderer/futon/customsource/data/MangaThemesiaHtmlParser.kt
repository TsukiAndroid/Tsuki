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
   * HTML scraper for sites running the WordPress MangaThemesia theme.
   *
   * MangaThemesia is the most popular *active* manga WordPress theme and powers
   * hundreds of scanlation group sites (Reaper Scans, Asura Scans, Luminous Scans,
   * Flame Scans, etc.).  It uses different markup from the older Madara theme.
   *
   * URL patterns:
   *   List   : {baseUrl}/manga/?page=N&order={order}
   *   Search : {baseUrl}/?s={query}&post_type=wp-manga
   *   Detail : {baseUrl}/manga/{slug}/
   *   Chapter: {baseUrl}/manga/{slug}/{chapter-slug}/
   *
   * Image resolution order: data-src -> data-lazy-src -> data-original -> srcset[0] -> src
   * User-Agent: full Chrome/Android string — required to pass hotlink protection on
   * many MangaThemesia CDNs that block bot/app UA strings.
   */
  class MangaThemesiaHtmlParser(
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
          val doc = runCatching { fetchDocument("$baseUrl/manga/") }.getOrNull() ?: return emptySet()
          val tags = mutableSetOf<MangaTag>()
          doc.select("a[href*=/genre/], .genre-item a, .wp-genre a, .checkbox-genre a").forEach { a ->
              val href = a.attr("href").trimEnd('/')
              val key = href.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@forEach
              val title = a.text().trim().ifEmpty { return@forEach }
              tags += MangaTag(title = title, key = key, source = customSource)
          }
          if (tags.isNotEmpty()) return tags
          doc.select("a[href*=genre=]").forEach { a ->
              val key = a.attr("href").substringAfter("genre=").substringBefore("&")
                  .ifEmpty { return@forEach }
              val title = a.text().trim().ifEmpty { return@forEach }
              tags += MangaTag(title = title, key = key, source = customSource)
          }
          return tags
      }

      fun getDetails(manga: Manga): Manga {
          val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
          val doc = fetchDocument(pageUrl)

          val title = doc.selectFirst(".post-title h1, .entry-title, #series-title h1")
              ?.text()?.trim() ?: manga.title

          val coverImg = doc.selectFirst(".thumb img, .series-thumb img, .wp-post-image")
          val coverUrl = resolveImageUrl(coverImg, manga.coverUrl)

          val descEl = doc.selectFirst(
              ".entry-content[itemprop=description], .summary__content, .synopsis p, #series-desc"
          )
          val description = descEl?.select("p")?.joinToString("\n") { it.text() }?.trim()
              ?: descEl?.text()?.trim()

          val statusText = doc.selectFirst(".status, .imptdt:contains(Status) i")
              ?.text()?.lowercase()
              ?: doc.select(".imptdt").firstOrNull { it.text().contains("status", ignoreCase = true) }
                  ?.selectFirst("i")?.text()?.lowercase()
          val state = when {
              statusText == null -> MangaState.ONGOING
              "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
              "hiatus" in statusText || "on hold" in statusText -> MangaState.PAUSED
              "cancel" in statusText || "drop" in statusText -> MangaState.ABANDONED
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
          val scriptImages = extractImagesFromScript(doc)
          if (scriptImages.isNotEmpty()) return scriptImages.mapIndexed { i, url ->
              MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
          }
          val images = doc.select(
              "#readerarea img, .reading-content img, .reader-area img, " +
              ".chapter-content img, .text-left img[src], .page-break img"
          )
          return images.mapIndexedNotNull { index, img ->
              val url = resolveImageUrl(img, "")
              if (url.isEmpty()) null
              else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
          }
      }

      private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
          val page = offset / PAGE_SIZE + 1
          val orderParam = when (order) {
              SortOrder.POPULARITY -> "popular"
              SortOrder.RATING     -> "rating"
              SortOrder.NEWEST     -> "latest"
              else                 -> "update"
          }
          return runCatching {
              parseMangaListPage(fetchDocument("$baseUrl/manga/?page=$page&order=$orderParam"))
          }.getOrElse { emptyList() }
      }

      private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
          val page = offset / PAGE_SIZE + 1
          val orderParam = when (order) {
              SortOrder.POPULARITY -> "popular"
              SortOrder.RATING     -> "rating"
              SortOrder.NEWEST     -> "latest"
              else                 -> "update"
          }
          return runCatching {
              parseMangaListPage(fetchDocument("$baseUrl/manga/?genre=$genreKey&page=$page&order=$orderParam"))
          }.getOrElse { emptyList() }
      }

      private fun searchManga(query: String, offset: Int): List<Manga> {
          val encoded = java.net.URLEncoder.encode(query, "UTF-8")
          val page = offset / PAGE_SIZE + 1
          return runCatching {
              parseMangaListPage(fetchDocument("$baseUrl/?s=$encoded&post_type=wp-manga&paged=$page"))
          }.getOrElse { emptyList() }
      }

      private fun parseMangaListPage(doc: Document): List<Manga> {
          val items = doc.select(
              ".utao .uta, .bsx, div.animepost, .bs, .listupd .bs, .postbody .bs"
          ).ifEmpty { doc.select("article.bs, article.bsx") }
          return items.mapNotNull { parseMangaItem(it) }
      }

      private fun parseMangaItem(el: Element): Manga? {
          val anchor = el.selectFirst("a") ?: return null
          val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
          val title = el.selectFirst(".tt, h2, h3, .ntitle")?.text()?.trim()
              ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
              ?: return null
          val coverImg = el.selectFirst("img")
          val coverUrl = resolveImageUrl(coverImg, "")
          return buildManga(title, pageUrl, coverUrl)
      }

      private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
          val items = doc.select("#chapterlist ul li, .eph-num a, .chapter-list li a")
          return if (items.isNotEmpty()) {
              items.mapIndexedNotNull { i, el ->
                  val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapIndexedNotNull null
                  chapterFromAnchor(a, i)
              }.reversed()
          } else {
              doc.select("li.wp-manga-chapter a, .cl li a").mapIndexedNotNull { i, a ->
                  chapterFromAnchor(a, i)
              }.reversed()
          }
      }

      private fun chapterFromAnchor(anchor: Element, fallbackIndex: Int): MangaChapter? {
          val url = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
          val rawTitle = anchor.text().trim().ifEmpty {
              anchor.selectFirst(".chapternum")?.text()?.trim() ?: "Chapter ${fallbackIndex + 1}"
          }
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

      private fun extractImagesFromScript(doc: Document): List<String> {
          val scripts = doc.select("script:not([src])").map { it.data() }
          for (script in scripts) {
              val match = TS_READER_RE.find(script) ?: continue
              val json = match.groupValues[1]
              val imageUrls = mutableListOf<String>()
              var inImages = false
              for (token in JSON_STRING_RE.findAll(json)) {
                  val value = token.groupValues[1]
                  if (value == "images") { inImages = true; continue }
                  if (inImages && (value.startsWith("http") || value.startsWith("/"))) {
                      imageUrls += value.fixProtocol()
                  } else if (inImages && value.isNotEmpty() && !value.startsWith("http")) {
                      inImages = false
                  }
              }
              if (imageUrls.isNotEmpty()) return imageUrls
          }
          return emptyList()
      }

      /**
       * Resolve the best available URL from an img element, trying every known
       * WordPress lazy-load attribute in order of reliability.
       */
      private fun resolveImageUrl(img: Element?, fallback: String? = null): String {
          if (img == null) return fallback.orEmpty()
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
          private const val PAGE_SIZE = 20
          private const val USER_AGENT =
              "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
          private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")
          private val TS_READER_RE = Regex("""ts_reader\.run\s*\(\s*(\{.*?\})\s*\)""", RegexOption.DOT_MATCHES_ALL)
          private val JSON_STRING_RE = Regex(""""([^"\\]*)"""")

          private val httpClient: OkHttpClient by lazy {
              OkHttpClient.Builder()
                  .connectTimeout(20, TimeUnit.SECONDS)
                  .readTimeout(30, TimeUnit.SECONDS)
                  .followRedirects(true)
                  .build()
          }
      }
  }
  