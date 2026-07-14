package io.github.landwarderer.futon.browser.detection

import org.jsoup.nodes.Element
import org.json.JSONObject

/**
 * Converts DOM [Element]s found by [UniversalPatternDetector] into CSS selector
 * strings, and assembles a completed [DetectionSession] into the same generic
 * parser-template JSON shape consumed by the app's `TemplateHtmlParser` (see
 * [io.github.landwarderer.futon.customsource.data.ParserTemplateValidator]).
 *
 * Kept separate from [UniversalPatternDetector] so "finding" an element and
 * "describing" it as a selector string are independently testable steps.
 */
object UniversalSelectorExtractor {

    /** Builds a CSS selector that identifies [el] within the whole document. */
    fun buildSelector(el: Element): String {
        val id = el.id()
        if (id.isNotBlank()) return "#$id"

        val classSelector = firstUsableClassSelector(el)
        if (classSelector != null) return classSelector

        return el.tagName()
    }

    /**
     * Builds a selector for [target] intended to be queried *relative to* [root]
     * (i.e. `root.select(this)`), used for selectors like "title inside card".
     */
    fun buildRelativeSelector(root: Element, target: Element): String {
        if (target == root) return ":scope"
        val classSelector = firstUsableClassSelector(target)
        if (classSelector != null) return classSelector
        // Fall back to a tag-based path from root to target.
        val path = mutableListOf<String>()
        var node: Element? = target
        while (node != null && node != root) {
            path.add(0, node.tagName())
            node = node.parent()
        }
        return if (path.isEmpty()) target.tagName() else path.joinToString(" > ")
    }

    private fun firstUsableClassSelector(el: Element): String? {
        val classes = el.classNames().filter { it.isNotBlank() }
        if (classes.isEmpty()) return null
        // Prefer a class name that looks intentional (not a hashed/utility class).
        val named = classes.firstOrNull { !it.matches(Regex("^[a-z0-9]{6,}$")) } ?: classes.first()
        return "${el.tagName()}.$named"
    }

    /**
     * Assembles a generic parser-template JSON string from a fully (or partially)
     * detected [DetectionSession]. Missing sections are simply omitted; the caller
     * is responsible for validating completeness before saving (see
     * `ParserTemplateValidator`).
     */
    fun buildParserTemplateJson(session: DetectionSession, baseUrl: String): String {
        val root = JSONObject()
        root.put("name", session.siteTitle.ifBlank { session.domain })
        root.put("version", 1)
        root.put("type", "UNIVERSAL_DETECTED")
        root.put("baseUrl", baseUrl)

        // "mangaList" and "pageList" are required sections per ParserTemplateValidator,
        // so they're always present (possibly empty) even if detection is incomplete;
        // callers must not persist an incomplete template (see MangaSiteDetector.createSource).
        val list = session.mangaListSelectors
        root.put(
            "mangaList",
            JSONObject().apply {
                put("itemSelector", list?.itemSelector.orEmpty())
                put("titleSelector", list?.titleSelector.orEmpty())
                put("coverSelector", list?.coverSelector.orEmpty())
                put("linkSelector", list?.linkSelector.orEmpty())
            },
        )

        session.mangaDetailSelectors?.let { detail ->
            root.put(
                "mangaDetail",
                JSONObject().apply {
                    put("titleSelector", detail.titleSelector)
                    put("coverSelector", detail.coverSelector)
                    put("descriptionSelector", detail.descriptionSelector)
                    put("chapterListSelector", detail.chapterListSelector)
                    put("chapterTitleSelector", detail.chapterTitleSelector)
                    put("chapterLinkSelector", detail.chapterLinkSelector)
                },
            )
        }

        val page = session.pageImageSelectors
        root.put(
            "pageList",
            JSONObject().apply {
                put("imageSelector", page?.imageSelector ?: "img")
                put("urlPattern", page?.urlPattern.orEmpty())
            },
        )

        session.searchSelectors?.let { search ->
            root.put(
                "search",
                JSONObject().apply {
                    put("endpoint", search.searchEndpoint)
                    put("param", search.searchParam)
                },
            )
        }

        return root.toString(2)
    }
}
