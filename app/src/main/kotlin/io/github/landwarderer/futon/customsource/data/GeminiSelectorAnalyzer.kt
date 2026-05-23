package io.github.landwarderer.futon.customsource.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URL

/**
 * Calls the Gemini AI API with a structured manga-site analysis prompt and
 * returns CSS selectors mapped to [SiteAutoDetector.DetectedFields].
 *
 * Every returned selector is validated against the real HTML via Jsoup so
 * the caller knows which selectors actually match content.
 * Never crashes on malformed Gemini JSON -- returns null on any failure.
 */
class GeminiSelectorAnalyzer {

    data class AnalysisResult(
        val fields: SiteAutoDetector.DetectedFields,
        /** Maps selector field key to whether it matched >= 1 element in the real HTML. */
        val verifiedSelectors: Map<String, Boolean>,
        val confidence: String,
        val notes: String,
    )

    suspend fun analyze(
        listHtml: String?,
        detailHtml: String?,
        chapterHtml: String?,
        listUrl: String,
        domain: String,
        apiKey: String,
        onProgress: ((String) -> Unit)? = null,
    ): AnalysisResult? = withContext(Dispatchers.IO) {
        runCatching {
            onProgress?.invoke("🧠 Sending to Gemini AI for analysis...")
            val prompt = buildPrompt(domain, listUrl, listHtml, detailHtml, chapterHtml)
            val raw    = callGemini(prompt, apiKey)
            val json   = JSONObject(stripFences(raw))

            onProgress?.invoke("✅ Verifying selectors against live HTML...")
            val verified = verifySelectors(json, listHtml, detailHtml, chapterHtml)

            val fields     = jsonToFields(json, domain)
            val confidence = json.optString("confidence", "low")
            val notes      = json.optString("notes", "")

            AnalysisResult(fields, verified, confidence, notes)
        }.onFailure { Log.w(TAG, "GeminiSelectorAnalyzer failed", it) }.getOrNull()
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private fun buildPrompt(
        domain: String,
        listUrl: String,
        listHtml: String?,
        detailHtml: String?,
        chapterHtml: String?,
    ): String = buildString {
        appendLine(SYSTEM_INSTRUCTION)
        appendLine()
        appendLine("SITE DOMAIN: $domain")
        appendLine("LIST PAGE URL: $listUrl")
        appendLine()
        appendLine("Return ONLY the following JSON object. No markdown. No explanation.")
        appendLine()
        appendLine(REQUEST_SCHEMA)
        appendLine()
        if (listHtml != null) {
            appendLine("=== MANGA LIST PAGE HTML ===")
            appendLine(HtmlCleaner.cleanAndCap(listHtml))
            appendLine()
        }
        if (detailHtml != null) {
            appendLine("=== MANGA DETAIL PAGE HTML ===")
            appendLine(HtmlCleaner.cleanAndCap(detailHtml))
            appendLine()
        }
        if (chapterHtml != null) {
            appendLine("=== MANGA CHAPTER PAGE HTML ===")
            appendLine(HtmlCleaner.cleanAndCap(chapterHtml))
            appendLine()
        }
    }

    // ── Gemini HTTP ───────────────────────────────────────────────────────────

    private fun callGemini(prompt: String, apiKey: String): String {
        // Safely encode the prompt as a JSON string value using JSONArray
        val promptJson = JSONArray().apply { put(prompt) }.toString()
            .removePrefix("[").removeSuffix("]")
        val body = buildString {
            append("""{"contents":[{"parts":[{"text":""")
            append(promptJson)
            append("""}]}],"generationConfig":{"temperature":0.05,""")
            append(""""maxOutputTokens":8192,"responseMimeType":"application/json"}}""")
        }
        val conn = URL(GEMINI_URL + apiKey).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout    = 90_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            error("Gemini API error ${conn.responseCode}: $err")
        }
        val response   = JSONObject(conn.inputStream.bufferedReader().readText())
        val candidates = response.getJSONArray("candidates")
        val content    = candidates.getJSONObject(0).getJSONObject("content")
        val parts      = content.getJSONArray("parts")
        return parts.getJSONObject(0).getString("text").trim()
    }

    private fun stripFences(raw: String): String =
        raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    // ── Selector validation ───────────────────────────────────────────────────

    private fun verifySelectors(
        json: JSONObject,
        listHtml: String?,
        detailHtml: String?,
        chapterHtml: String?,
    ): Map<String, Boolean> {
        val result     = mutableMapOf<String, Boolean>()
        val listDoc    = listHtml?.let   { Jsoup.parse(it) }
        val detailDoc  = detailHtml?.let { Jsoup.parse(it) }
        val chapterDoc = chapterHtml?.let { Jsoup.parse(it) }

        fun check(key: String, sel: String, doc: org.jsoup.nodes.Document?) {
            if (sel.isBlank() || doc == null) return
            result[key] = runCatching { doc.select(sel).isNotEmpty() }.getOrDefault(false)
        }

        val ml = json.optJSONObject("mangaList")
        val md = json.optJSONObject("mangaDetail")
        val cl = json.optJSONObject("chapterList")
        val pl = json.optJSONObject("pageList")

        check("itemSelector",   ml?.optString("itemSelector",       "") ?: "", listDoc)
        check("titleSelector",  ml?.optString("titleSelector",      "") ?: "", listDoc)
        check("coverSelector",  ml?.optString("coverSelector",      "") ?: "", listDoc)
        check("detailTitle",    md?.optString("titleSelector",      "") ?: "", detailDoc)
        check("detailCover",    md?.optString("coverSelector",      "") ?: "", detailDoc)
        check("descriptionSel", md?.optString("descriptionSelector","") ?: "", detailDoc)
        check("chapterSel",     cl?.optString("selector",           "") ?: "", detailDoc)
        check("pageImageSel",   pl?.optString("imageSelector",      "") ?: "", chapterDoc)

        return result
    }

    // ── JSON -> DetectedFields ────────────────────────────────────────────────

    private fun jsonToFields(
        json: JSONObject,
        domain: String,
    ): SiteAutoDetector.DetectedFields {
        val ml = json.optJSONObject("mangaList")
        val md = json.optJSONObject("mangaDetail")
        val cl = json.optJSONObject("chapterList")
        val pl = json.optJSONObject("pageList")

        val listPath   = ml?.optString("endpoint",       "") ?: ""
        val cardSel    = ml?.optString("itemSelector",   "") ?: ""
        val titleSel   = ml?.optString("titleSelector",  "") ?: ""
        val coverSel   = ml?.optString("coverSelector",  "") ?: ""
        val searchEp   = ml?.optString("searchEndpoint", "") ?: ""
        val searchParm = ml?.optString("searchParam",    "") ?: ""
        val searchPath = if (searchEp.isNotBlank() && searchParm.isNotBlank())
            "$searchEp?$searchParm=" else searchEp

        val detailTitle = md?.optString("titleSelector",       "") ?: ""
        val descSel     = md?.optString("descriptionSelector", "") ?: ""
        val chapSel     = cl?.optString("selector",            "") ?: ""
        val pageImgSel  = pl?.optString("imageSelector",       "") ?: ""

        val confidence = json.optString("confidence", "low")

        fun score(sel: String) = when {
            sel.isEmpty() -> SiteAutoDetector.Confidence.LOW
            sel.contains('.') || sel.contains('#') || sel.contains('[') ->
                if (confidence == "high") SiteAutoDetector.Confidence.HIGH
                else SiteAutoDetector.Confidence.MEDIUM
            else -> SiteAutoDetector.Confidence.MEDIUM
        }

        return SiteAutoDetector.DetectedFields(
            siteName = domain.removePrefix("www.").substringBefore(".")
                .replaceFirstChar { it.uppercaseChar() },
            listPath          = listPath.ifEmpty { "/" },
            searchPath        = searchPath,
            cardSelector      = cardSel,
            titleSelector     = titleSel,
            coverSelector     = coverSel,
            detailTitle       = detailTitle,
            description       = descSel,
            chapterSelector   = chapSel,
            pageImageSelector = pageImgSel,
            cmsType           = SiteAutoDetector.CmsType.UNKNOWN,
            fieldConfidence = mapOf(
                "cardSelector"      to score(cardSel),
                "titleSelector"     to score(titleSel),
                "coverSelector"     to score(coverSel),
                "detailTitle"       to score(detailTitle),
                "description"       to score(descSel),
                "chapterSelector"   to score(chapSel),
                "pageImageSelector" to score(pageImgSel),
                "listPath"   to if (listPath.isNotEmpty()) SiteAutoDetector.Confidence.HIGH else SiteAutoDetector.Confidence.LOW,
                "searchPath" to if (searchPath.isNotEmpty()) SiteAutoDetector.Confidence.HIGH else SiteAutoDetector.Confidence.LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "GeminiSelectorAnalyzer"
        private const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key="

        private val SYSTEM_INSTRUCTION = """
You are an expert HTML analyst for a manga reader Android app called Tsuki.
The app uses Jsoup (Java HTML parser) to scrape manga websites.
You will be given cleaned HTML of up to 3 pages:
1. Manga LIST page (browse/catalog)
2. Manga DETAIL page (single manga + chapter list)
3. Manga CHAPTER page (reader with images)

Rules for selectors:
- ONLY standard CSS selectors (tag, .class, #id, [attr], descendant, >)
- NO JavaScript pseudo-selectors, NO XPath
- Prefer class selectors over tag selectors
- Prefer MULTIPLE selectors joined with commas as fallbacks
- For cover images: select the img element directly, not its parent
- For links: select the a element directly
- If a field cannot be determined return empty string
- Never guess or hallucinate
- Return ONLY raw JSON, no markdown, no explanation
        """.trimIndent()

        private val REQUEST_SCHEMA = """
{
  "mangaList": {
    "endpoint": "path to manga list e.g. /manga",
    "pagination": "path or page",
    "pageParam": "e.g. page",
    "itemSelector": "repeating container for one manga card",
    "titleSelector": "title element inside card",
    "coverSelector": "img element inside card",
    "linkSelector": "a element inside card",
    "searchEndpoint": "e.g. / or /search",
    "searchParam": "e.g. s or q"
  },
  "mangaDetail": {
    "titleSelector": "manga title on detail page",
    "coverSelector": "img cover on detail page",
    "descriptionSelector": "synopsis element",
    "authorSelector": "author element",
    "statusSelector": "status element",
    "tagsSelector": "genre/tag links"
  },
  "chapterList": {
    "selector": "repeating chapter row element",
    "titleSelector": "chapter name inside row",
    "linkSelector": "a linking to chapter",
    "dateSelector": "upload date inside row",
    "isAjax": "true if chapter list loads via AJAX",
    "ajaxAction": "e.g. manga_get_chapters if isAjax true"
  },
  "pageList": {
    "imageSelector": "img elements for manga pages in reader",
    "isScripted": "true if images are in JS variable not DOM"
  },
  "genres": {
    "endpoint": "path to genres page",
    "selector": "each genre link element"
  },
  "confidence": "high / medium / low",
  "notes": "any important notes about this site"
}
        """.trimIndent()
    }
}