package io.github.landwarderer.futon.browser.learning

import java.net.URI

/**
 * Holds HTML/JSON samples captured while the user browses a manga site.
 * Cleared when a parser is generated or the user dismisses the banner.
 */
class LearningSession {

    var domain: String = ""
    var siteName: String = ""

    private val _captured = mutableMapOf<PageType, CapturedPage>()

    val mangaListPage: CapturedPage? get() = _captured[PageType.MANGA_LIST]
    val mangaDetailPage: CapturedPage? get() = _captured[PageType.MANGA_DETAIL]
    val chapterReaderPage: CapturedPage? get() = _captured[PageType.CHAPTER_READER]

    val capturedTypes: Set<PageType> get() = _captured.keys

    val isReadyForGeneration: Boolean
        get() = PageType.MANGA_LIST in _captured &&
            PageType.MANGA_DETAIL in _captured &&
            PageType.CHAPTER_READER in _captured

    fun capture(type: PageType, url: String, html: String) {
        if (type == PageType.UNKNOWN) return
        if (domain.isEmpty()) {
            domain = runCatching { URI(url).host ?: "" }.getOrDefault("")
        }
        _captured[type] = CapturedPage(url = url, html = html.take(MAX_HTML_CHARS))
    }

    fun reset() {
        _captured.clear()
        domain = ""
        siteName = ""
    }

    /** Progress label for UI: 0‥3 */
    fun progress(): Int = _captured.size

    companion object {
        private const val MAX_HTML_CHARS = 40_000
    }
}

data class CapturedPage(
    val url: String,
    val html: String,
)
