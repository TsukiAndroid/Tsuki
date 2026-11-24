package io.github.landwarderer.futon.browsersource.data

import android.webkit.WebResourceRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Detects likely chapter pages and extracts candidate manga pages from the
 * rendered DOM. This is deliberately heuristic: sites can opt into custom
 * extraction later without making generic browser reading depend on parsers.
 */
object BrowserSourceChapterDetector {

    /**
     * Extracts image URLs in document order. It understands common lazy-load
     * attributes, srcset/picture sources and reader-area CSS backgrounds.
     * It intentionally ignores blob/data URLs because the native reader's
     * HTTP loader cannot fetch those URLs outside the WebView process.
     */
    const val COLLECT_IMAGES_JS = """
        (function() {
            var found = [];
            var seen = Object.create(null);
            var imageAttrs = [
                'data-src', 'data-lazy-src', 'data-original', 'data-url',
                'data-cfsrc', 'data-echo', 'data-image', 'data-img',
                'data-srcset', 'srcset', 'src'
            ];
            var excluded = /(^|[\/_-])(logo|favicon|icon|avatar|banner|sprite|button|emoji|ad)([\/_-.]|$)/i;
            var readerRoots = document.querySelectorAll(
                '#reader, .reader, .reading-content, .chapter-content, .chapter-images, ' +
                '.pages, .viewer, .comic, .wp-manga-chapter-img, [data-reader]'
            );

            function absolute(value) {
                if (!value) return '';
                value = String(value).trim();
                if (!value || value.indexOf('data:') === 0 || value.indexOf('blob:') === 0 ||
                    value.indexOf('javascript:') === 0) return '';
                try {
                    var url = new URL(value, document.baseURI);
                    return (url.protocol === 'http:' || url.protocol === 'https:') ? url.href : '';
                } catch (_) { return ''; }
            }

            function add(value, element) {
                var url = absolute(value);
                if (!url || seen[url] || excluded.test(url.toLowerCase())) return;
                if (element) {
                    var w = element.naturalWidth || element.width || 0;
                    var h = element.naturalHeight || element.height || 0;
                    if (w > 0 && h > 0 && (w < 90 || h < 90)) return;
                }
                seen[url] = true;
                found.push(url);
            }

            function addSrcset(value, element) {
                if (!value) return;
                value.split(',').forEach(function(candidate) {
                    var url = candidate.trim().split(/\s+/)[0];
                    add(url, element);
                });
            }

            document.querySelectorAll('img').forEach(function(img) {
                imageAttrs.forEach(function(attr) {
                    var value = img.getAttribute(attr);
                    if (!value) return;
                    if (attr.indexOf('srcset') !== -1) addSrcset(value, img);
                    else add(value, img);
                });
                if (img.currentSrc) add(img.currentSrc, img);
            });

            document.querySelectorAll('picture source').forEach(function(source) {
                addSrcset(source.getAttribute('data-srcset') || source.getAttribute('srcset'), null);
            });

            // Background images are restricted to likely reader containers to
            // avoid collecting site-wide decorative images and advertisements.
            readerRoots.forEach(function(root) {
                root.querySelectorAll('*').forEach(function(element) {
                    var background = '';
                    try { background = getComputedStyle(element).backgroundImage || ''; } catch (_) {}
                    var matches = background.match(/url\((['"]?)(.*?)\1\)/g) || [];
                    matches.forEach(function(match) {
                        var value = match.replace(/^url\((['"]?)/, '').replace(/(['"]?)\)$/, '');
                        add(value, element);
                    });
                });
            });

            return JSON.stringify(found);
        })();
    """

    const val GET_PAGE_META_JS = """
        (function(){
            var og = {};
            og.title = document.querySelector('meta[property="og:title"]')?.getAttribute('content')
                        || document.title || '';
            og.image = document.querySelector('meta[property="og:image"]')?.getAttribute('content') || '';
            og.description = document.querySelector('meta[property="og:description"]')?.getAttribute('content') || '';
            return JSON.stringify(og);
        })();
    """

    fun buildMarkReadJs(readUrls: Set<String>): String {
        val urlsJson = JSONArray(readUrls.toList()).toString()
        return """
            (function(){
                var readUrls = $urlsJson;
                var style = document.createElement('style');
                style.textContent = 'a.tsuki-read { opacity: 0.5; } ' +
                    'a.tsuki-read::after { content: " ✓"; color: #4caf50; font-size: 0.8em; }';
                (document.head || document.documentElement).appendChild(style);
                document.querySelectorAll('a[href]').forEach(function(a){
                    var href = a.href;
                    if(readUrls.some(function(u){ return href === u || href.endsWith(u); })) {
                        a.classList.add('tsuki-read');
                    }
                });
            })();
        """.trimIndent()
    }

    private val imageCountPerPage = ConcurrentHashMap<String, MutableSet<String>>()

    fun resetForPage(pageUrl: String) {
        imageCountPerPage.clear()
        imageCountPerPage[pageUrl] = ConcurrentHashMap.newKeySet()
    }

    /** Counts distinct image requests, avoiding duplicate requests inflating detection. */
    fun onResourceRequest(pageUrl: String, request: WebResourceRequest): Boolean {
        val uri = request.url
        val url = uri.toString().lowercase()
        if (uri.scheme != "https" && uri.scheme != "http") return false
        val path = uri.path.orEmpty().lowercase()
        if (!looksLikeImagePath(path)) return false
        if (excludedAssetPattern.containsMatchIn(path)) return false

        val urls = imageCountPerPage.getOrPut(pageUrl) { ConcurrentHashMap.newKeySet() }
        urls.add(url)
        return urls.size >= CHAPTER_IMAGE_THRESHOLD
    }

    fun isChapterUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/chapter/") || lower.contains("/ch/") ||
            lower.contains("/read/") || lower.contains("/viewer/") ||
            lower.contains("/reading/") || lower.contains("-chapter-") ||
            lower.contains("/chapters/") ||
            Regex("""/(c|ch|chapter|chap|episode|ep)[_\-]?\d+""").containsMatchIn(lower)
    }

    fun isDetailUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("/manga/") || lower.contains("/comic/") ||
            lower.contains("/manhwa/") || lower.contains("/series/") ||
            lower.contains("/title/") || lower.contains("/book/")) &&
            !isChapterUrl(lower)
    }

    fun parseImageUrls(jsonArray: String?): List<String> {
        if (jsonArray.isNullOrBlank() || jsonArray == "null") return emptyList()
        return runCatching {
            val value = org.json.JSONTokener(jsonArray).nextValue()
            val array = when (value) {
                is JSONArray -> value
                is String -> JSONArray(value)
                else -> return emptyList()
            }
            val seen = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                val raw = array.optString(i).trim()
                val uri = runCatching { URI(raw) }.getOrNull() ?: continue
                if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
                    seen.add(uri.toASCIIString())
                }
            }
            seen.toList()
        }.getOrDefault(emptyList())
    }

    fun parseMetaJson(json: String?): Triple<String, String, String> {
        if (json.isNullOrBlank() || json == "null") return Triple("", "", "")
        return runCatching {
            val value = org.json.JSONTokener(json).nextValue()
            val obj = if (value is JSONObject) value else JSONObject(value as? String ?: "{}")
            Triple(
                obj.optString("title", ""),
                obj.optString("image", ""),
                obj.optString("description", ""),
            )
        }.getOrDefault(Triple("", "", ""))
    }

    private fun looksLikeImagePath(path: String): Boolean =
        imageExtensionPattern.containsMatchIn(path) ||
            path.contains("/image/") || path.contains("/images/") ||
            path.contains("/chapter-page/") || path.contains("/uploads/pages/")

    private val imageExtensionPattern = Regex("""\.(jpg|jpeg|png|webp|gif|avif|bmp)$""", RegexOption.IGNORE_CASE)
    private val excludedAssetPattern = Regex("""(logo|favicon|icon|banner|avatar|sprite|emoji|advert)""", RegexOption.IGNORE_CASE)
    private const val CHAPTER_IMAGE_THRESHOLD = 3
}
