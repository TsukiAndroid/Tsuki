package io.github.landwarderer.futon.customsource.ui.visualpicker

/**
 * Server-side (Kotlin) companion to the JS selector generator.
 *
 * Validates and optionally refines the selector string produced by the
 * injected JavaScript before the app stores it. The primary generation
 * logic lives in `element_picker.js`; this class exists for post-processing
 * and generating the ParserTemplate JSON from captured selectors.
 */
object SelectorGenerator {

    /**
     * Returns true if the selector string looks usable (non-blank, no
     * obviously broken characters that would cause Jsoup to throw).
     */
    fun isUsable(selector: String): Boolean {
        if (selector.isBlank()) return false
        // Reject tag-only selectors for cover/title (too broad)
        if (selector.trim().lowercase() in setOf("a", "span", "p", "div", "img")) return false
        return true
    }

    /**
     * Given all captured selectors from a [PickerSession], produce a
     * ParserTemplate-compatible JSON string in the same schema that
     * [UniversalSourceViewModel.buildJson] generates.
     *
     * The produced JSON can be saved via [ParserTemplateRepository] and
     * linked to a new [CustomSource] of type [CustomSourceType.CUSTOM_TEMPLATE].
     */
    fun buildTemplateJson(
        siteName: String,
        siteUrl: String,
        capturedSelectors: Map<PickerStep, String>,
        listPath: String = "/",
    ): String {
        val itemSel    = capturedSelectors[PickerStep.CARD_CONTAINER]?.takeIf { isUsable(it) } ?: ""
        val titleSel   = capturedSelectors[PickerStep.MANGA_TITLE]?.takeIf    { isUsable(it) } ?: ""
        val coverSel   = capturedSelectors[PickerStep.COVER_IMAGE]?.takeIf    { isUsable(it) } ?: ""
        val chapterSel = capturedSelectors[PickerStep.CHAPTER_TITLE]?.takeIf  { isUsable(it) } ?: ""
        val pageSel    = capturedSelectors[PickerStep.PAGE_IMAGE]?.takeIf     { isUsable(it) } ?: ""

        // Detect path vs query pagination (same heuristic as UniversalSourceViewModel)
        val pagination = if (listPath.isNotBlank() && !listPath.contains('?') &&
            listPath.trimEnd('/').isNotEmpty()) "path" else "query"

        return buildString {
            append("{")
            append("\"name\":\"").append(escape(siteName)).append("\",")
            append("\"version\":\"1.0\",")
            append("\"type\":\"html\",")
            append("\"mangaList\":{")
            append("\"endpoint\":\"").append(escape(listPath)).append("\",")
            append("\"pagination\":\"").append(pagination).append("\",")
            append("\"pageParam\":\"page\",")
            append("\"itemSelector\":\"").append(escape(itemSel)).append("\",")
            append("\"titleSelector\":\"").append(escape(titleSel)).append(" a, ").append(escape(titleSel)).append("\",")
            append("\"coverSelector\":\"").append(escape(coverSel)).append("\",")
            append("\"searchEndpoint\":\"/\",")
            append("\"searchParam\":\"s\"")
            append("},")
            append("\"mangaDetails\":{")
            append("\"titleSelector\":\"h1, h2, .post-title\",")
            append("\"coverSelector\":\".summary_image img, .manga-thumbnail img, img.wp-post-image\",")
            append("\"descriptionSelector\":\".summary__content, .description-summary, p\",")
            append("\"chapterSelector\":\"").append(escape(chapterSel)).append("\"")
            append("},")
            append("\"chapterPages\":{")
            append("\"pageSelector\":\"").append(escape(pageSel)).append("\"")
            append("}")
            append("}")
        }
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
