package io.github.landwarderer.futon.browsersource.data

import android.webkit.WebResourceRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Monitors resource requests from a WebView to detect when the user has
 * navigated to a chapter reader page, and extracts image URLs for the native
 * manga reader.
 *
 * Detection strategy (in priority order):
 *  1. URL pattern matching — URL path contains chapter keywords
 *  2. Image-count heuristic — 3+ large images loaded from the same domain
 *  3. DOM extraction via injected JavaScript
 */
object BrowserSourceChapterDetector {

    // ── JS that collects all candidate manga-page images from the DOM ─────────

    const val COLLECT_IMAGES_JS = """
        (function(){
            var results = [];
            var imgs = document.querySelectorAll('img');
            imgs.forEach(function(img){
                var src = img.getAttribute('data-src')
                    || img.getAttribute('data-lazy-src')
                    || img.getAttribute('data-original')
                    || img.getAttribute('src')
                    || '';
                if (!src || !src.startsWith('http')) return;
                if (!isMangaPage(src)) return;
                if (isAdOrLogo(src, img)) return;
                results.push(src);
            });
            function isMangaPage(url) {
                return /\.(jpg|jpeg|png|webp|gif)(\?|${'$'})/i.test(url);
            }
            function isAdOrLogo(url, img) {
                var lower = url.toLowerCase();
                if (/logo|favicon|icon|banner|ad[_\-]|avatar|sprite|button/.test(lower)) return true;
                var w = img.naturalWidth || img.width || 0;
                var h = img.naturalHeight || img.height || 0;
                if (w > 0 && h > 0 && (w < 100 || h < 100)) return true;
                return false;
            }
            return JSON.stringify(results);
        })();
    """

    /** JS to get the page's og:title and og:image meta tags. */
    const val GET_PAGE_META_JS = """
        (function(){
            var og = {};
            og.title = document.querySelector('meta[property="og:title"]')
                        ?.getAttribute('content')
                        || document.title
                        || '';
            og.image = document.querySelector('meta[property="og:image"]')
                        ?.getAttribute('content')
                        || '';
            og.description = document.querySelector('meta[property="og:description"]')
                        ?.getAttribute('content')
                        || '';
            return JSON.stringify(og);
        })();
    """

    /** JS to inject CSS that marks read chapter links with a visual indicator. */
    fun buildMarkReadJs(readUrls: Set<String>): String {
        val urlsJson = readUrls.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        return """
            (function(){
                var readUrls = [$urlsJson];
                var style = document.createElement('style');
                style.textContent = 'a.tsuki-read { opacity: 0.5; } ' +
                    'a.tsuki-read::after { content: " ✓"; color: #4caf50; font-size: 0.8em; }';
                document.head.appendChild(style);
                document.querySelectorAll('a[href]').forEach(function(a){
                    var href = a.href;
                    if(readUrls.some(function(u){ return href === u || href.endsWith(u); })){
                        a.classList.add('tsuki-read');
                    }
                });
            })();
        """.trimIndent()
    }

    // ── Image-count heuristic state ───────────────────────────────────────────

    /** Counts large image loads per URL session (reset on each page navigation). */
    private val imageCountPerPage = ConcurrentHashMap<String, AtomicInteger>()

    fun resetForPage(pageUrl: String) {
        imageCountPerPage.clear()
        imageCountPerPage[pageUrl] = AtomicInteger(0)
    }

    /**
     * Call from [shouldInterceptRequest]. Returns true when the heuristic
     * detects a chapter page based on image-count.
     */
    fun onResourceRequest(pageUrl: String, request: WebResourceRequest): Boolean {
        val url = request.url.toString().lowercase()
        if (!url.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)(\\?.*)?$"))) return false
        // Filter out icons/logos by URL pattern
        if (url.contains("logo") || url.contains("icon") || url.contains("favicon") ||
            url.contains("banner") || url.contains("avatar")
        ) return false

        val counter = imageCountPerPage.getOrPut(pageUrl) { AtomicInteger(0) }
        val count = counter.incrementAndGet()
        return count >= CHAPTER_IMAGE_THRESHOLD
    }

    // ── URL-pattern detection ─────────────────────────────────────────────────

    /** Returns true if [url] looks like a manga chapter reader page. */
    fun isChapterUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/chapter/") || lower.contains("/ch/") ||
            lower.contains("/read/") || lower.contains("/viewer/") ||
            lower.contains("/reading/") || lower.contains("-chapter-") ||
            lower.contains("/chapters/") ||
            Regex("""/(c|ch|chapter|chap|episode|ep)[_\-]?\d+""").containsMatchIn(lower)
    }

    /** Returns true if [url] looks like a manga detail page. */
    fun isDetailUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("/manga/") || lower.contains("/comic/") ||
            lower.contains("/manhwa/") || lower.contains("/series/") ||
            lower.contains("/title/") || lower.contains("/book/")) &&
            !isChapterUrl(lower)
    }

    // ── URL parsing ───────────────────────────────────────────────────────────

    fun parseImageUrls(jsonArray: String?): List<String> {
        if (jsonArray.isNullOrBlank() || jsonArray == "null") return emptyList()
        return try {
            val cleaned = jsonArray.trim().removePrefix("\"").removeSuffix("\"")
                .replace("\\\"", "\"")
            val arr = org.json.JSONArray(cleaned)
            (0 until arr.length()).map { arr.getString(it) }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseMetaJson(json: String?): Triple<String, String, String> {
        if (json.isNullOrBlank() || json == "null") return Triple("", "", "")
        return try {
            val cleaned = json.trim().removePrefix("\"").removeSuffix("\"")
                .replace("\\\"", "\"")
            val obj = org.json.JSONObject(cleaned)
            Triple(
                obj.optString("title", ""),
                obj.optString("image", ""),
                obj.optString("description", ""),
            )
        } catch (_: Exception) {
            Triple("", "", "")
        }
    }

    private const val CHAPTER_IMAGE_THRESHOLD = 3
}
