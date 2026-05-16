package io.github.landwarderer.futon.browser.learning

/**
 * Classifies a page URL + HTML snippet into a [PageType].
 * Used by the learning system to categorise captured pages.
 */
object PageClassifier {

    private val CHAPTER_URL_PATTERNS = listOf(
        "/chapter/", "/ch/", "/read/", "/viewer/", "/reading/",
        "/manga-chapter", "/chap-", "-chapter-", "/c[0-9]",
    )

    private val DETAIL_URL_PATTERNS = listOf(
        "/manga/", "/comic/", "/manhwa/", "/manhua/",
        "/series/", "/title/", "/book/", "/webtoon/",
    )

    private val CHAPTER_HTML_SIGNALS = listOf(
        "chapter-img", "reader-content", "reading-content",
        "page-img", "manga-page", "js-page", "listImgs",
        "lstImages", "chapter_preloaded_images",
    )

    private val LIST_HTML_SIGNALS = listOf(
        "manga-list", "manga_list", "mangas-list",
        "comic-list", "series-list", "manga-card",
        "book-item", "content-genres-item",
        "manga-thumb", "c-image-hover",
    )

    private val DETAIL_HTML_SIGNALS = listOf(
        "manga-info", "manga_info", "comic-info",
        "series-detail", "post-content", "manga-detail",
        "detail-info", "chapterList", "chapter-list",
        "listing-chapters", "tabs-chapters",
    )

    fun classify(url: String, htmlSnippet: String): PageType {
        val urlLower = url.lowercase()
        val htmlLower = htmlSnippet.take(8_000).lowercase()

        // Chapter reader check (most specific first)
        if (CHAPTER_URL_PATTERNS.any { urlLower.contains(it) }) {
            if (CHAPTER_HTML_SIGNALS.any { htmlLower.contains(it) }) {
                return PageType.CHAPTER_READER
            }
            // URL pattern is strong signal even without HTML
            if (looksLikeChapterUrl(urlLower)) return PageType.CHAPTER_READER
        }

        // Manga detail check
        if (DETAIL_URL_PATTERNS.any { urlLower.contains(it) } &&
            !looksLikeMangaListUrl(urlLower)
        ) {
            if (DETAIL_HTML_SIGNALS.any { htmlLower.contains(it) }) {
                return PageType.MANGA_DETAIL
            }
        }

        // Manga list check
        val listSignalCount = LIST_HTML_SIGNALS.count { htmlLower.contains(it) }
        if (listSignalCount >= 2) return PageType.MANGA_LIST

        // Fallback via HTML signals alone
        if (CHAPTER_HTML_SIGNALS.count { htmlLower.contains(it) } >= 2) {
            return PageType.CHAPTER_READER
        }
        if (DETAIL_HTML_SIGNALS.count { htmlLower.contains(it) } >= 2) {
            return PageType.MANGA_DETAIL
        }

        return PageType.UNKNOWN
    }

    private fun looksLikeChapterUrl(url: String): Boolean {
        return Regex("""/(c|ch|chapter|chap|episode|ep)[_\-]?\d+""").containsMatchIn(url)
    }

    private fun looksLikeMangaListUrl(url: String): Boolean {
        return url.contains("/genre/") || url.contains("/tag/") ||
            url.contains("/category/") || url.endsWith("/manga") ||
            url.endsWith("/latest") || url.endsWith("/popular")
    }
}
