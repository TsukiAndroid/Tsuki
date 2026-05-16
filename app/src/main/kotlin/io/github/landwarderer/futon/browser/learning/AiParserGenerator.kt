package io.github.landwarderer.futon.browser.learning

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Generates a ParserTemplate-compatible JSON from captured page HTML.
 * Uses Gemini API when an API key is available; falls back to heuristics.
 */
class AiParserGenerator {

    suspend fun generate(
        session: LearningSession,
        geminiApiKey: String?,
    ): String = withContext(Dispatchers.IO) {
        if (!geminiApiKey.isNullOrBlank()) {
            runCatching { generateWithGemini(session, geminiApiKey) }
                .onFailure { e -> Log.w(TAG, "Gemini failed, falling back to heuristics", e) }
                .getOrNull()
        } else null
    } ?: generateHeuristic(session)

    // ── Gemini ────────────────────────────────────────────────────────────────

    private suspend fun generateWithGemini(
        session: LearningSession,
        apiKey: String,
    ): String = withContext(Dispatchers.IO) {
        val prompt = buildGeminiPrompt(session)
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 4096)
                put("responseMimeType", "application/json")
            })
        }

        val url = java.net.URL(GEMINI_URL + apiKey)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000

        connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

        if (connection.responseCode != 200) {
            error("Gemini API error ${connection.responseCode}")
        }

        val responseText = connection.inputStream.bufferedReader().readText()
        val response = JSONObject(responseText)
        val candidates = response.getJSONArray("candidates")
        val content = candidates.getJSONObject(0).getJSONObject("content")
        val parts = content.getJSONArray("parts")
        parts.getJSONObject(0).getString("text")
    }

    private fun buildGeminiPrompt(session: LearningSession): String {
        val sb = StringBuilder()
        sb.appendLine("""
You are an expert at generating manga site parser configurations.
Analyse the HTML samples from a manga website and generate a JSON parser template.

The JSON must follow this exact schema:
{
  "name": "Site Name",
  "version": "1",
  "type": "html",
  "baseUrl": "https://example.com",
  "mangaList": {
    "selector": ".manga-card",
    "url": "a[href]",
    "title": ".manga-title",
    "cover": "img[src]"
  },
  "mangaDetail": {
    "title": "h1.manga-title",
    "cover": ".cover img[src]",
    "description": ".manga-description",
    "chapters": {
      "selector": ".chapter-list a",
      "url": "a[href]",
      "title": "a",
      "date": ".date"
    }
  },
  "chapterPages": {
    "selector": ".reader-content img",
    "imageUrl": "img[src]",
    "imageUrlAttr": "src"
  }
}

Site domain: ${session.domain}
        """.trimIndent())

        session.mangaListPage?.let {
            sb.appendLine("\n=== MANGA LIST PAGE (${it.url}) ===")
            sb.appendLine(it.html.take(12_000))
        }
        session.mangaDetailPage?.let {
            sb.appendLine("\n=== MANGA DETAIL PAGE (${it.url}) ===")
            sb.appendLine(it.html.take(12_000))
        }
        session.chapterReaderPage?.let {
            sb.appendLine("\n=== CHAPTER READER PAGE (${it.url}) ===")
            sb.appendLine(it.html.take(12_000))
        }

        sb.appendLine("\nReturn ONLY the JSON object, no explanation.")
        return sb.toString()
    }

    // ── Heuristic fallback ────────────────────────────────────────────────────

    fun generateHeuristic(session: LearningSession): String {
        val baseUrl = runCatching {
            val uri = URI(session.mangaListPage?.url ?: session.domain)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault("https://${session.domain}")

        val name = session.siteName.ifBlank {
            session.domain.removePrefix("www.").substringBefore(".")
                .replaceFirstChar { it.uppercase() }
        }

        // Detect common CSS selectors heuristically
        val listHtml = session.mangaListPage?.html ?: ""
        val detailHtml = session.mangaDetailPage?.html ?: ""
        val chapterHtml = session.chapterReaderPage?.html ?: ""

        val mangaCardSelector = detectSelector(listHtml, CARD_CANDIDATES)
        val titleSelector = detectSelector(detailHtml, TITLE_CANDIDATES)
        val coverSelector = detectSelector(detailHtml, COVER_CANDIDATES)
        val descSelector = detectSelector(detailHtml, DESC_CANDIDATES)
        val chapterSelector = detectSelector(detailHtml, CHAPTER_CANDIDATES)
        val pageImgSelector = detectSelector(chapterHtml, PAGE_IMG_CANDIDATES)
        val imgAttr = if (chapterHtml.contains("data-src=")) "data-src" else "src"

        return JSONObject().apply {
            put("name", name)
            put("version", "1")
            put("type", "html")
            put("baseUrl", baseUrl)
            put("mangaList", JSONObject().apply {
                put("selector", mangaCardSelector)
                put("url", "a[href]")
                put("title", ".manga-title, h3, h2, .title")
                put("cover", "img[src], img[data-src]")
            })
            put("mangaDetail", JSONObject().apply {
                put("title", titleSelector)
                put("cover", coverSelector)
                put("description", descSelector)
                put("chapters", JSONObject().apply {
                    put("selector", chapterSelector)
                    put("url", "a[href]")
                    put("title", "a, .chapter-title")
                    put("date", ".date, time, .chapter-time")
                })
            })
            put("chapterPages", JSONObject().apply {
                put("selector", pageImgSelector)
                put("imageUrl", "img")
                put("imageUrlAttr", imgAttr)
            })
        }.toString(2)
    }

    private fun detectSelector(html: String, candidates: List<String>): String {
        val htmlLower = html.lowercase()
        return candidates.firstOrNull { htmlLower.contains(it.lowercase().substringBefore("[")) }
            ?: candidates.first()
    }

    companion object {
        private const val TAG = "AiParserGenerator"
        private const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="

        private val CARD_CANDIDATES = listOf(
            ".manga-card", ".book-item", ".content-genres-item",
            ".manga-item", ".comic-item", ".c-image-hover",
            "div.manga", ".story-item", "article.manga",
        )
        private val TITLE_CANDIDATES = listOf(
            "h1.manga-title", ".manga-title h1", ".post-title h1",
            "h1.entry-title", ".series-title", "h2.heading",
        )
        private val COVER_CANDIDATES = listOf(
            ".manga-detail-img img", ".summary_image img",
            ".cover img", ".manga-poster img", ".manga-info img",
        )
        private val DESC_CANDIDATES = listOf(
            ".manga-description", ".description-summary",
            ".entry-content p", ".manga-summary", ".summary__content",
        )
        private val CHAPTER_CANDIDATES = listOf(
            ".chapter-list a", ".listing-chapters a",
            ".chapters a", "#chapters a", "ul.row-content-chapter a",
        )
        private val PAGE_IMG_CANDIDATES = listOf(
            ".reading-content img", ".reader-content img",
            ".chapter-img img", ".manga-page img",
            "#images img", ".js-page img",
        )
    }
}
