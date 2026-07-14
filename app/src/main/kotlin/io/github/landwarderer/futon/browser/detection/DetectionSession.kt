package io.github.landwarderer.futon.browser.detection

import java.util.concurrent.ConcurrentHashMap

/**
 * Selectors extracted from a detected manga LIST page (grid/list of manga cards).
 */
data class MangaListSelectors(
    val itemSelector: String = "",
    val titleSelector: String = "",
    val coverSelector: String = "",
    val linkSelector: String = "",
)

/**
 * Selectors extracted from a detected manga DETAIL page.
 */
data class MangaDetailSelectors(
    val titleSelector: String = "",
    val coverSelector: String = "",
    val descriptionSelector: String = "",
    val chapterListSelector: String = "",
    val chapterTitleSelector: String = "",
    val chapterLinkSelector: String = "",
)

/** Selectors for the chapter list, when detected separately from the detail page. */
data class ChapterSelectors(
    val selector: String = "",
    val titleSelector: String = "",
    val linkSelector: String = "",
)

/** Reader-page image selectors, derived from observed sequential image requests. */
data class PageSelectors(
    val imageSelector: String = "img",
    /** Common base path shared by the observed sequential page image URLs. */
    val urlPattern: String = "",
)

data class SearchSelectors(
    val searchEndpoint: String = "",
    val searchParam: String = "",
)

/**
 * Tracks what [UniversalPatternDetector] has learned about a single domain across
 * multiple page visits in the same browsing session. One session exists per domain
 * at a time; see [DetectionSessionStore].
 */
data class DetectionSession(
    val domain: String,
    var confidence: Int = 0,
    var mangaListDetected: Boolean = false,
    var mangaDetailDetected: Boolean = false,
    var chapterReaderDetected: Boolean = false,
    var searchDetected: Boolean = false,
    var mangaListSelectors: MangaListSelectors? = null,
    var mangaDetailSelectors: MangaDetailSelectors? = null,
    var chapterSelectors: ChapterSelectors? = null,
    var pageImageSelectors: PageSelectors? = null,
    var searchSelectors: SearchSelectors? = null,
    /** Distinct chapter-reader image URLs observed for this domain, most recent capped. */
    var capturedPageUrls: MutableList<String> = mutableListOf(),
    var siteTitle: String = "",
    var faviconUrl: String = "",
    /** The URL of the page where the manga list was detected (used for validation/preview). */
    var listPageUrl: String = "",
    /** True once the user has been shown the Level 3 "Add Source?" prompt this session. */
    var level3Shown: Boolean = false,
    /** True once the user tapped "Not now" -- suppresses further prompts this session. */
    var dismissedThisSession: Boolean = false,
    var lastUpdated: Long = System.currentTimeMillis(),
)

/** The three user-facing prompt tiers, derived from [DetectionSession.confidence]. */
enum class DetectionPromptLevel {
    NONE,      // < 40
    LEARNING,  // 40-69: silent monitoring icon
    HINT,      // 70-99: subtle banner
    ADD_SOURCE // 100+: full "Add Source?" prompt
}

fun DetectionSession.promptLevel(): DetectionPromptLevel = when {
    confidence >= 100 -> DetectionPromptLevel.ADD_SOURCE
    confidence >= 70 -> DetectionPromptLevel.HINT
    confidence >= 40 -> DetectionPromptLevel.LEARNING
    else -> DetectionPromptLevel.NONE
}

/**
 * In-memory store for [DetectionSession]s, keyed by domain.
 *
 * Sessions expire after [SESSION_TTL_MS] of inactivity and the store never holds
 * more than [MAX_SESSIONS] at once (oldest-by-[DetectionSession.lastUpdated] evicted
 * first) so a long browsing spree can't grow this unbounded. Sessions are lightweight
 * (a handful of strings + a capped URL list) so this cap is a safety net, not a
 * meaningful memory optimization on its own.
 */
object DetectionSessionStore {

    const val SESSION_TTL_MS = 30 * 60 * 1000L
    const val MAX_SESSIONS = 20

    private val sessions = ConcurrentHashMap<String, DetectionSession>()

    @Synchronized
    fun getOrCreate(domain: String): DetectionSession {
        pruneExpired()
        return sessions.getOrPut(domain) {
            evictOldestIfFull()
            DetectionSession(domain = domain)
        }
    }

    fun get(domain: String): DetectionSession? {
        val session = sessions[domain] ?: return null
        if (System.currentTimeMillis() - session.lastUpdated > SESSION_TTL_MS) {
            sessions.remove(domain)
            return null
        }
        return session
    }

    fun remove(domain: String) {
        sessions.remove(domain)
    }

    /** Called when the user closes the browser -- sessions do not need to outlive it. */
    fun clearAll() {
        sessions.clear()
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now - it.value.lastUpdated > SESSION_TTL_MS }
    }

    private fun evictOldestIfFull() {
        if (sessions.size < MAX_SESSIONS) return
        val oldest = sessions.entries.minByOrNull { it.value.lastUpdated } ?: return
        sessions.remove(oldest.key)
    }
}
