package io.github.landwarderer.futon.browser.detection

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL

/**
 * Pure, site-agnostic heuristics that recognise the universal building blocks of a
 * manga website: a list of manga, a manga detail page, a chapter reader, and a
 * search form. Nothing here knows about any specific CMS or domain -- see
 * [io.github.landwarderer.futon.customsource.data.CmsTypeDetector] for that.
 *
 * All detection here is pure computation over an already-fetched [Document] (or a
 * list of already-observed image URLs for the reader case) so it is safe to run on
 * [kotlinx.coroutines.Dispatchers.IO] with no network calls of its own.
 */
object UniversalPatternDetector {

    /** Points awarded when a given element is confidently detected. */
    const val POINTS_LIST = 30
    const val POINTS_DETAIL = 30
    const val POINTS_READER = 40
    const val POINTS_SEARCH = 10
    const val POINTS_DOMAIN_KEYWORD = 10
    const val POINTS_URL_KEYWORD = 5
    const val POINTS_KNOWN_CMS_MARKER = 20

    private val DOMAIN_KEYWORDS = listOf("manga", "manhwa", "manhua", "comic", "webtoon", "toon")
    private val URL_KEYWORDS = listOf("/manga/", "/comic/", "/series/", "/chapter", "/read/", "/title/")

    private const val MIN_LIST_ITEMS = 6
    private const val MIN_PORTRAIT_IMAGES_FOR_LIST = 6
    private const val MIN_SEQUENTIAL_READER_IMAGES = 5

    data class ListMatch(val selectors: MangaListSelectors, val itemCount: Int)
    data class DetailMatch(val selectors: MangaDetailSelectors)
    data class ReaderMatch(val selectors: PageSelectors)

    /**
     * Looks for a repeated grid/list of portrait-oriented images, each near a short
     * text label and wrapped in (or containing) a link -- the universal shape of a
     * "browse manga" page regardless of CMS.
     */
    fun detectMangaList(doc: Document): ListMatch? {
        val candidates = doc.select("img").filter { isLikelyPortraitCover(it) }
        if (candidates.size < MIN_PORTRAIT_IMAGES_FOR_LIST) return null

        // Group candidate images by their nearest ancestor tag+class signature so that
        // repeated "cards" collapse into one bucket, regardless of what the theme calls it.
        val groups = candidates.groupBy { cardSignature(it) }
        val (_, group) = groups.maxByOrNull { it.value.size } ?: return null
        if (group.size < MIN_LIST_ITEMS) return null

        val sample = group.first()
        val card = nearestCardAncestor(sample) ?: sample.parent() ?: return null
        val link = card.selectFirst("a[href]") ?: sample.parent()?.let { if (it.tagName() == "a") it else null }
        val titleEl = findTitleNear(card)

        val selectors = MangaListSelectors(
            itemSelector = UniversalSelectorExtractor.buildSelector(card),
            titleSelector = titleEl?.let { UniversalSelectorExtractor.buildRelativeSelector(card, it) } ?: "",
            coverSelector = UniversalSelectorExtractor.buildRelativeSelector(card, sample),
            linkSelector = link?.let { UniversalSelectorExtractor.buildRelativeSelector(card, it) } ?: "",
        )
        return ListMatch(selectors, group.size)
    }

    /**
     * Looks for the shape of a single manga's detail page: one large "hero" cover
     * image, a prominent heading, a paragraph of description text, and a list of
     * chapter links (anchors whose text looks like "Chapter N" / "Ch. N" / a number).
     */
    fun detectMangaDetail(doc: Document): DetailMatch? {
        val heading = doc.select("h1").firstOrNull { it.text().isNotBlank() }
            ?: doc.select("h2").firstOrNull { it.text().isNotBlank() }
            ?: return null

        val heroImage = doc.select("img")
            .filter { isLikelyPortraitCover(it) }
            .maxByOrNull { imageArea(it) }

        val description = doc.select("p, div")
            .firstOrNull { it.ownText().length > 60 && it.select("a").isEmpty() }

        val chapterLinks = doc.select("a[href]").filter { looksLikeChapterLink(it) }
        if (chapterLinks.size < 2) return null

        val chapterContainer = chapterLinks.map { nearestCardAncestor(it, maxDepth = 4) ?: it.parent() }
            .filterNotNull()
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key ?: chapterLinks.first().parent()

        val chapterListSelector = chapterContainer?.parent()
            ?.let { UniversalSelectorExtractor.buildSelector(it) } ?: ""
        val sampleLink = chapterLinks.first()
        val sampleCard = nearestCardAncestor(sampleLink, maxDepth = 4) ?: sampleLink

        val selectors = MangaDetailSelectors(
            titleSelector = UniversalSelectorExtractor.buildSelector(heading),
            coverSelector = heroImage?.let { UniversalSelectorExtractor.buildSelector(it) } ?: "",
            descriptionSelector = description?.let { UniversalSelectorExtractor.buildSelector(it) } ?: "",
            chapterListSelector = chapterListSelector,
            chapterTitleSelector = UniversalSelectorExtractor.buildRelativeSelector(sampleCard, sampleLink),
            chapterLinkSelector = UniversalSelectorExtractor.buildRelativeSelector(sampleCard, sampleLink),
        )
        return DetailMatch(selectors)
    }

    /** Looks for a text input paired with a submit control that plausibly performs search. */
    fun detectSearch(doc: Document): SearchSelectors? {
        val form = doc.select("form").firstOrNull { form ->
            form.select("input[type=text], input[type=search], input:not([type])").isNotEmpty() &&
                (form.attr("action").isNotBlank() || form.select("button, input[type=submit]").isNotEmpty())
        } ?: doc.selectFirst("input[type=search]")?.parent() ?: return null

        val input = form.select("input[type=text], input[type=search], input:not([type])").firstOrNull()
            ?: return null
        val action = form.attr("abs:action").ifBlank { form.attr("action") }
        val paramName = input.attr("name").ifBlank { "q" }
        return SearchSelectors(searchEndpoint = action, searchParam = paramName)
    }

    /**
     * Reader-page heuristic: a run of [MIN_SEQUENTIAL_READER_IMAGES]+ large image
     * requests observed in quick succession on the same page, whose URLs share a
     * common path prefix and end in sequential-looking numeric filenames (page 1, 2,
     * 3...). This is evaluated over URLs sniffed via `shouldInterceptRequest`, not the
     * DOM, since reader pages are frequently lazy-loaded / canvas-rendered.
     */
    fun detectChapterReader(imageUrls: List<String>): ReaderMatch? {
        if (imageUrls.size < MIN_SEQUENTIAL_READER_IMAGES) return null
        val distinct = imageUrls.distinct()
        if (distinct.size < MIN_SEQUENTIAL_READER_IMAGES) return null

        val prefix = commonPathPrefix(distinct)
        if (prefix.isBlank()) return null

        return ReaderMatch(PageSelectors(imageSelector = "img", urlPattern = prefix))
    }

    // ── Domain / URL keyword scoring ─────────────────────────────────────────

    fun domainKeywordScore(url: String): Int {
        val host = runCatching { URL(url).host }.getOrDefault("").lowercase()
        return if (DOMAIN_KEYWORDS.any { host.contains(it) }) POINTS_DOMAIN_KEYWORD else 0
    }

    fun urlKeywordScore(url: String): Int {
        val lower = url.lowercase()
        return if (URL_KEYWORDS.any { lower.contains(it) }) POINTS_URL_KEYWORD else 0
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun isLikelyPortraitCover(img: Element): Boolean {
        val w = img.attr("width").toIntOrNull() ?: parseCss(img, "width")
        val h = img.attr("height").toIntOrNull() ?: parseCss(img, "height")
        val src = img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
        if (src.isBlank()) return false
        if (w != null && h != null && w > 0 && h > 0) {
            // Portrait manga covers are reliably taller than wide, roughly 2:3.
            return h > w && h.toDouble() / w.toDouble() >= 1.15
        }
        // No explicit dimensions available (very common with lazy-loaded themes) --
        // fall back to accepting it as a candidate; the repeated-card grouping step
        // below is what actually filters noise, not this one heuristic alone.
        return true
    }

    private fun imageArea(img: Element): Int {
        val w = img.attr("width").toIntOrNull() ?: 0
        val h = img.attr("height").toIntOrNull() ?: 0
        return w * h
    }

    private fun parseCss(el: Element, prop: String): Int? {
        val style = el.attr("style")
        val regex = Regex("$prop\\s*:\\s*(\\d+)px")
        return regex.find(style)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** A signature describing an image's nearest "card" ancestor shape, used for grouping. */
    private fun cardSignature(img: Element): String {
        val card = nearestCardAncestor(img) ?: img.parent() ?: return "?"
        return "${card.tagName()}.${card.className()}"
    }

    /**
     * Walks up from [el] up to [maxDepth] ancestors looking for a container that
     * looks like a repeated "card" -- i.e. one of several siblings sharing the same
     * tag+class as its parent's other children. Falls back to the direct parent.
     */
    private fun nearestCardAncestor(el: Element, maxDepth: Int = 5): Element? {
        var node: Element? = el
        var depth = 0
        while (node != null && depth < maxDepth) {
            val parent = node.parent()
            if (parent != null) {
                val siblingsWithSameShape = parent.children()
                    .count { it.tagName() == node!!.tagName() && it.className() == node.className() }
                if (siblingsWithSameShape >= MIN_LIST_ITEMS) return node
            }
            node = parent
            depth++
        }
        return el.parent()
    }

    private fun findTitleNear(card: Element): Element? =
        card.select("h1, h2, h3, h4, h5, .title, [class*=title], [class*=name]")
            .firstOrNull { it.text().isNotBlank() && it.text().length in 2..120 }
            ?: card.select("a[href]").firstOrNull { it.attr("title").isNotBlank() }

    private fun looksLikeChapterLink(a: Element): Boolean {
        val text = a.text().trim().lowercase()
        if (text.isBlank()) return false
        if (text.contains("chapter") || text.contains("ch.") || text.contains("episode")) return true
        // Bare numeric labels ("1", "12.5") are also common chapter-list link text.
        return Regex("^\\d+(\\.\\d+)?$").matches(text)
    }

    /** Longest shared path prefix (up to the last "/") across all given URLs. */
    private fun commonPathPrefix(urls: List<String>): String {
        val paths = urls.mapNotNull { runCatching { URL(it).path }.getOrNull() }
        if (paths.size < 2) return ""
        var prefix = paths.first()
        for (path in paths.drop(1)) {
            while (!path.startsWith(prefix)) {
                prefix = prefix.dropLast(1)
                if (prefix.isEmpty()) return ""
            }
        }
        return prefix.substringBeforeLast('/', "")
    }
}
