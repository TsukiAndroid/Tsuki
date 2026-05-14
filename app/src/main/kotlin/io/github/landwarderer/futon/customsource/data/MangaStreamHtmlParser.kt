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
   * HTML scraper for sites built on the MangaStream / WPMangaStream WordPress theme.
   *
   * Despite sharing the "WP" prefix, MangaStream uses a completely different set
   * of selectors from Madara. It's widely used for manhwa/manhua sites
   * (Toonily, Manhwa18, ZeroScans web front-end, Komikindo, etc.).
   *
   * URL patterns:
   *   List   : {baseUrl}/manga/?page=N&order={order}
   *   Search : {baseUrl}/?s={query}
   *   Detail : {baseUrl}/manga/{slug}/
   *   Chapter: {baseUrl}/{slug}/chapter-N/
   *
   * Image resolution order: data-src -> data-lazy-src -> data-original -> src
   * User-Agent: full Chrome/Android string required to pass CDN hotlink protection.
   */
  class MangaStreamHtmlParser(
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
          doc.select(".listgenre a, .genre-list a, a[href*=/genre/]").forEach { a ->
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

          val title = doc.selectFirst(".infox h1, .entry-title, .series-title h1")
              ?.text()?.trim() ?: manga.title

          val coverImg = doc.selectFirst(".thumb img, .series-image img, .wp-post-image")
          val coverUrl = resolveImageUrl(coverImg, manga.coverUrl)

          val descEl = doc.selectFirst(".entry-content[itemprop=description], .synops, .summary")
          val description = descEl?.select("p")?.joinToString("\n") { it.text() }?.trim()
              ?: descEl?.text()?.trim()

          val statusText = doc.select(".infox .spe span").firstOrNull {
              it.text().contains("status", ignoreCase = true)
          }?.ownText()?.lowercase()
              ?: doc.selectFirst(".status")?.text()?.lowercase()
          val state = when {
              statusText == null -> MangaState.ONGOING
              "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
              "hiatus" in statusText || "on hold" in statusText -> MangaState.PAUSED
              "cancel" in statusText || "drop" in statusText -> MangaState.ABANDONED
              else -> MangaState.ONGOING
          }

          val chapters = loadChapterList(doc)

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
              "#readerarea img, .reader-area img, .chapter-content img, " +
              "img[class*=size-full], .img-responsive"
          )
          return images.mapIndexedNotNull { index, img ->
              val url = resolveImageUrl(img, "")
              if (url.isEmpty() || url.contains("data:image")) null
              else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
          }
      }

      private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
          val page = offset / PAGE_SIZE + 1
          val orderStr = when (order) {
              SortOrder.POPULARITY -> "popular"
              SortOrder.RATING     -> "rating"
              SortOrder.NEWEST     -> "latest"
              else                 -> "update"
          }
          return runCatching {
              parseListPage(fetchDocument("$baseUrl/manga/?page=$page&order=$orderStr"))
          }.getOrElse { emptyList() }
      }

      private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
          val page = offset / PAGE_SIZE + 1
          val orderStr = when (order) {
              SortOrder.POPULARITY -> "popular"
              SortOrder.RATING     -> "rating"
              SortOrder.NEWEST     -> "latest"
              else                 -> "update"
          }
          return runCatching {
              parseListPage(fetchDocument("$baseUrl/manga/?genre=$genreKey&page=$page&order=$orderStr"))
          }.getOrElse { emptyList() }
      }

      private fun searchManga(query: String, offset: Int): List<Manga> {
          val encoded = java.net.URLEncoder.encode(query, "UTF-8")
          val page = offset / PAGE_SIZE + 1
          return runCatching {
              parseListPage(fetchDocument("$baseUrl/?s=$encoded&paged=$page"))
          }.getOrElse { emptyList() }
      }

      private fun parseListPage(doc: Document): List<Manga> {
          val items = doc.select(".listupd .bs, .bslist .bs, .bsx, article.bs")
          return items.mapNotNull { parseListItem(it) }
      }

      private fun parseListItem(el: Element): Manga? {
          val anchor = el.selectFirst("a") ?: return null
          val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
          val title = el.selectFirst(".tt, h2, h3, .ntitle, .bigor .tt")?.text()?.trim()
              ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
              ?: return null
          val coverImg = el.selectFirst("img")
          val coverUrl = resolveImageUrl(coverImg, "")
          return buildManga(title, pageUrl, coverUrl)
      }

      private fun loadChapterList(doc: Document): List<MangaChapter> {
          val rows = doc.select("#chapterlist li, .eph-num li, .clstyle li")
          return rows.mapIndexedNotNull { i, li ->
              val a = li.selectFirst("a") ?: return@mapIndexedNotNull null
              val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
              val rawTitle = li.selectFirst(".chapternum")?.text()?.trim()
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
          val scripts = doc.select("script:not([src])").map { it.data() }
          for (script in scripts) {
              val match = IMAGES_VAR_RE.find(script) ?: continue
              return URL_RE.findAll(match.groupValues[1])
                  .map { it.groupValues[1].fixProtocol() }
                  .filter { it.startsWith("http") }
                  .toList()
          }
          return emptyList()
      }

      /**
       * Resolve best image URL from an img element by probing every known WP
       * lazy-load attribute in order of reliability.
       */
      private fun resolveImageUrl(img: Element?, fallback: String): String {
          if (img == null) return fallback
          val srcsetFirst = listOf(img.attr("data-srcset"), img.attr("srcset"))
              .firstOrNull { it.isNotEmpty() }
              ?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
          return listOf(
              img.attr("data-src"),
              img.attr("data-lazy-src"),
              img.attr("data-original"),
              img.attr("data-url"),
              srcsetFirst,
              img.attr("src"),
          ).firstOrNull { !it.isNullOrEmpty() && !it.contains("data:image") && !it.contains("placeholder") }
              ?.fixProtocol() ?: fallback
      }

      private fun fetchDocument(url: String): Document {
          val req = Request.Builder()
              .url(url)
              .header("User-Agent", USER_AGENT)
              .header("Referer", baseUrl)
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

      private fun String?.fixProtocol(): String = when {
          this == null -> ""
          startsWith("//") -> "https:$this"
          else -> this
      }

      companion object {
          private const val PAGE_SIZE = 24
          private const val USER_AGENT =
              "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
          private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")
          private val IMAGES_VAR_RE = Regex("""var\s+images\s*=\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
          private val URL_RE = Regex(""""(https?://[^"]+)"""")

          private val httpClient: OkHttpClient by lazy {
              OkHttpClient.Builder()
                  .connectTimeout(20, TimeUnit.SECONDS)
                  .readTimeout(30, TimeUnit.SECONDS)
                  .followRedirects(true)
                  .build()
          }
      }
  }
  