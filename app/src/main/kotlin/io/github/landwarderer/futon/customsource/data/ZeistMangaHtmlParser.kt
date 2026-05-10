package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
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
 * Parser for sites built on the ZeistManga platform — Blogger-hosted manga sites
 * that expose a JSON feed at /feeds/posts/default/-/{category}?alt=json.
 *
 * Used by 51 sites including MangaSoul, LonerTL, MangaHub (Blogger), and many
 * Indonesian/Spanish/Arabic scanlation Blogger blogs.
 *
 * Strategy:
 *   List  → Blogger Atom JSON feed with pagination via start-index
 *   Detail → HTML scraping of individual blog post / series page
 *   Pages  → Images from post content or dedicated reader page
 */
class ZeistMangaHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    // Default Blogger manga category label — many sites use "Series" or "Manga"
    private val mangaCategory = "Series"
    private val pageSize = 20

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        val startIndex = offset + 1
        return runCatching {
            val url = buildString {
                append(baseUrl)
                append("/feeds/posts/default/-/")
                when {
                    !query.isNullOrBlank() -> {
                        append(mangaCategory)
                        append("?alt=json&orderby=published&max-results=")
                        append(pageSize + 1)
                        append("&start-index=")
                        append(startIndex)
                        append("&q=label:")
                        append(mangaCategory)
                        append("+")
                        append(java.net.URLEncoder.encode(query, "UTF-8"))
                    }
                    tag != null -> {
                        append(java.net.URLEncoder.encode(tag.key, "UTF-8"))
                        append("?alt=json&orderby=published&max-results=")
                        append(pageSize + 1)
                        append("&start-index=")
                        append(startIndex)
                    }
                    else -> {
                        append(mangaCategory)
                        append("?alt=json&orderby=published&max-results=")
                        append(pageSize + 1)
                        append("&start-index=")
                        append(startIndex)
                    }
                }
            }
            val json = getJson(url)
            val feed = json.optJSONObject("feed") ?: return@runCatching emptyList()
            if (!feed.toString().contains("\"entry\":")) return@runCatching emptyList()
            parseFeedEntries(feed.optJSONArray("entry") ?: return@runCatching emptyList())
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
            val doc = fetchDocument(pageUrl)

            // Status
            val statusText = (
                doc.selectFirst("div.y6x11p:contains(Status) .dt")
                    ?: doc.selectFirst("span.status")
                    ?: doc.selectFirst("li:contains(Status) span")
            )?.text()?.lowercase().orEmpty()
            val state = when {
                "ongoing" in statusText || "on going" in statusText || "en curso" in statusText -> MangaState.ONGOING
                "completed" in statusText || "completo" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                "cancelled" in statusText || "dropped" in statusText || "abandonado" in statusText -> MangaState.ABANDONED
                else -> null
            }

            // Chapters — often listed in another Blogger label feed or in HTML
            val chapters = fetchChaptersFromPage(doc, pageUrl, manga)

            manga.copy(
                description = doc.selectFirst("div.synops, div.synopsis, .description, [class*=desc]")
                    ?.text()?.trim(),
                state = state,
                chapters = chapters,
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val doc = fetchDocument(chapter.url)
            // Images can be in post content or in a reader div
            val images = doc.select(
                ".reading-content img, #reader img, .chapter-images img, " +
                    "div.post-body img, .entry-content img"
            ).ifEmpty { doc.select("img[src*=blogspot.com], img[src*=blogger.com]") }
            images.mapIndexedNotNull { index, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty() || url.contains("blogger.com/favicon") || url.contains("icon")) null
                else MangaPage(
                    id = chapter.id * 1000L + index,
                    url = url,
                    preview = null,
                    source = customSource,
                )
            }
        }.getOrElse { emptyList() }
    }

    fun getGenres(): Set<MangaTag> {
        return runCatching {
            // Fetch Blogger label list via feed
            val feedUrl = "$baseUrl/feeds/posts/default?alt=json&max-results=0"
            val json = getJson(feedUrl)
            val feed = json.optJSONObject("feed") ?: return@runCatching emptySet()
            val categories = feed.optJSONArray("category") ?: return@runCatching emptySet()
            val tags = mutableSetOf<MangaTag>()
            for (i in 0 until categories.length()) {
                val cat = categories.optJSONObject(i) ?: continue
                val term = cat.optString("term").takeIf { it.isNotEmpty() } ?: continue
                if (term.equals(mangaCategory, ignoreCase = true)) continue
                tags += MangaTag(title = term.toTitleCase(), key = term, source = customSource)
            }
            tags
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseFeedEntries(entries: JSONArray): List<Manga> {
        val result = mutableListOf<Manga>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val title = entry.optJSONObject("title")?.optString("\$t")?.takeIf { it.isNotEmpty() } ?: continue
            val links = entry.optJSONArray("link") ?: continue
            var href = ""
            for (j in 0 until links.length()) {
                val link = links.optJSONObject(j) ?: continue
                if (link.optString("rel") == "alternate") {
                    href = link.optString("href")
                    break
                }
            }
            if (href.isEmpty()) continue

            // Try to extract thumbnail from media$thumbnail or post content
            val coverUrl = entry.optJSONObject("media\$thumbnail")?.optString("url")
                ?.replace(Regex("""/s\d+-c/"""), "/w300/")
                ?.replace(Regex("""=s\d+-c$"""), "=w300")
                ?: run {
                    val content = entry.optJSONObject("content")?.optString("\$t")
                        ?: entry.optJSONObject("summary")?.optString("\$t") ?: ""
                    val img = Jsoup.parse(content).selectFirst("img")
                    img?.attr("src").orEmpty().fixProtocol()
                }

            result += Manga(
                id = href.hashCode().toLong(),
                title = title,
                altTitles = emptySet(),
                url = runCatching { java.net.URI(href).rawPath }.getOrElse { href },
                publicUrl = href,
                rating = 0f,
                contentRating = ContentRating.SAFE,
                coverUrl = coverUrl.fixProtocol(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                largeCoverUrl = coverUrl.fixProtocol(),
                description = null,
                chapters = null,
                source = customSource,
            )
        }
        return result
    }

    private fun fetchChaptersFromPage(
        doc: org.jsoup.nodes.Document,
        pageUrl: String,
        manga: Manga,
    ): List<MangaChapter> {
        // Look for chapter links in the series page
        val chapterLinks = doc.select(
            "#chapterList a, .chapter-list a, ul.chapter li a, " +
                "[class*=chapter-item] a, .list-chapter li a"
        )
        if (chapterLinks.isEmpty()) return emptyList()

        return chapterLinks.mapIndexedNotNull { i, a ->
            val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = a.text().trim().ifEmpty { "Chapter ${i + 1}" }
            val number = CHAPTER_RE.find(rawTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: (i + 1).toFloat()
            MangaChapter(
                id = href.hashCode().toLong(),
                title = rawTitle,
                number = number,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = customSource,
            )
        }.reversed()
    }

    private fun fetchDocument(url: String): org.jsoup.nodes.Document {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .get().build()
        return httpClient.newCall(req).execute().use { resp ->
            Jsoup.parse(resp.body?.string() ?: "", url)
        }
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get().build()
        return httpClient.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun String?.fixProtocol(): String = when {
        this == null || isEmpty() -> ""
        startsWith("//") -> "https:$this"
        else -> this
    }

    private fun String.toTitleCase(): String = split(" ")
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    companion object {
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_RE = Regex("""[Cc]hapter[s]?\s*([\d.]+)""")
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
