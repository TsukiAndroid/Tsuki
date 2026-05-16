package io.github.landwarderer.futon.browser.webview

/**
 * Detects manga chapter image URLs from a WebView page by injecting JS.
 */
object ChapterDetector {

    /** JS that collects all img src/data-src values from the page and returns them as JSON. */
    const val COLLECT_IMAGES_JS = """
        (function(){
            var imgs = document.querySelectorAll('img');
            var urls = [];
            imgs.forEach(function(img){
                var src = img.getAttribute('data-src') || img.getAttribute('src') || '';
                if(src && src.startsWith('http') && isLikelyMangaPage(src)){
                    urls.push(src);
                }
            });
            function isLikelyMangaPage(url){
                return /\.(jpg|jpeg|png|webp|gif)(\?|${'$'})/i.test(url);
            }
            return JSON.stringify(urls);
        })();
    """

    /** Returns true if the page URL looks like a chapter reader. */
    fun isChapterUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/chapter/") || lower.contains("/ch/") ||
            lower.contains("/read/") || lower.contains("/viewer/") ||
            lower.contains("/reading/") || lower.contains("-chapter-") ||
            Regex("""/(c|ch|chapter|chap|episode|ep)[_\-]?\d+""").containsMatchIn(lower)
    }

    /** Returns true if the page URL looks like a manga detail page. */
    fun isDetailUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("/manga/") || lower.contains("/comic/") ||
            lower.contains("/manhwa/") || lower.contains("/series/") ||
            lower.contains("/title/") || lower.contains("/book/")) &&
            !isChapterUrl(lower)
    }

    fun parseImageUrls(jsonArray: String?): List<String> {
        if (jsonArray.isNullOrBlank() || jsonArray == "null") return emptyList()
        return try {
            val arr = org.json.JSONArray(jsonArray.removePrefix("\"").removeSuffix("\""))
            (0 until arr.length()).map { arr.getString(it) }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
