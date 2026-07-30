package io.github.landwarderer.futon.webviewsource.data

/**
 * Attempts to detect the chapter URL pattern from a single URL.
 *
 * Strategy: look for a number in the URL path that looks like a chapter number
 * (preceded by "chapter", "ch", "ep", "episode", "c", or a hyphen/slash).
 * Replace that number segment with {N}.
 *
 * Examples:
 *   https://manganato.com/manga-abc/chapter-47   →  .../chapter-{N}
 *   https://webtoons.com/en/action/title/ep-47/viewer → .../ep-{N}/viewer
 *   https://site.com/manga/title/c47             →  .../c{N}
 *
 * Returns null if no chapter-like number is found.
 */
object ChapterPatternDetector {

    private val CHAPTER_SEGMENT_RE = Regex(
        """(?i)(chapter|chap|ch|episode|ep|c)[_\-]?(\d+(?:\.\d+)?)""",
    )

    fun detect(url: String): String? {
        val match = CHAPTER_SEGMENT_RE.find(url) ?: return null
        val prefix = match.groupValues[1]
        val number = match.groupValues[2]
        val separator = url[match.range.first + prefix.length]
            .takeIf { it == '-' || it == '_' }?.toString() ?: ""
        return url.replace("$prefix$separator$number", "$prefix$separator{N}")
    }

    /**
     * Given a pattern string with {N}, constructs the URL for a specific chapter.
     * Returns null if the pattern is null.
     */
    fun buildUrl(pattern: String?, chapterNumber: Float): String? {
        pattern ?: return null
        val n = if (chapterNumber == chapterNumber.toLong().toFloat()) {
            chapterNumber.toLong().toString()
        } else {
            chapterNumber.toString()
        }
        return pattern.replace("{N}", n)
    }

    /**
     * Extracts the chapter number from a URL given a stored pattern.
     * Returns null if the pattern doesn't match.
     */
    fun extractChapter(url: String, pattern: String?): Float? {
        pattern ?: return extractChapterFallback(url)
        val regex = Regex(
            Regex.escape(pattern).replace(Regex.escape("{N}"), """(\d+(?:\.\d+)?)"""),
        )
        return regex.find(url)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /** Fallback: find any chapter-like number in the URL even without a pattern. */
    private fun extractChapterFallback(url: String): Float? =
        CHAPTER_SEGMENT_RE.find(url)?.groupValues?.get(2)?.toFloatOrNull()
}
