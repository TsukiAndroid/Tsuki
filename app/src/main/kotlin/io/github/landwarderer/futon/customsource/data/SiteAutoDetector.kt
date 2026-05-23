package io.github.landwarderer.futon.customsource.data

import android.util.Log
import io.github.landwarderer.futon.browser.learning.LearningSession
import io.github.landwarderer.futon.browser.learning.PageType
import io.github.landwarderer.futon.customsource.data.HtmlCleaner
import io.github.landwarderer.futon.customsource.data.JsRenderFetcher
import io.github.landwarderer.futon.customsource.data.SmartPageFetcher
import io.github.landwarderer.futon.customsource.data.GeminiSelectorAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Fetches a manga website's HTML and auto-detects CSS selectors needed to configure
 * it as a Universal Source. Uses a multi-strategy approach:
 *
 *  1. Fetch homepage → extract site name, search form, CMS signals
 *  2. Discover manga listing page (homepage OR /manga/ OR /manhwa/ etc.)
 *  3. Detect manga cards: known CMS patterns → WordPress article → structural DOM analysis
 *  4. Fetch manga detail page → detect title/cover/description/chapter selectors
 *  5. Fetch chapter reader page → detect page image selectors
 *
 * Works with Tailwind CSS sites, WordPress themes, and traditional CMS manga themes
 * (Madara, MangaThemesia, MadTheme, etc.).
 */
class SiteAutoDetector(
    private val context: android.content.Context? = null,
    private val geminiApiKey: String? = null,
    private val onProgress: ((String) -> Unit)? = null,
) {

    enum class Confidence { HIGH, MEDIUM, LOW }

    data class DetectedFields(
        val siteName: String = "",
        val listPath: String = "",
        val searchPath: String = "",
        val cardSelector: String = "",
        val titleSelector: String = "",
        val coverSelector: String = "",
        val detailTitle: String = "",
        val description: String = "",
        val chapterSelector: String = "",
        val pageImageSelector: String = "",
        val paginationType: String = "page",   // "path" (WordPress /page/N/) or "page" (?page=N)
        val fieldConfidence: Map<String, Confidence> = emptyMap(),
        /**
         * The CMS theme fingerprinted from the site's HTML.
         * The ViewModel maps this to the appropriate [CustomSourceType] so that the
         * proven, battle-tested theme parser is used instead of the generic template parser.
         */
        val cmsType: CmsType = CmsType.UNKNOWN,
    )

    private data class CardResult(
        val cardSelector: String,
        val titleSelector: String,
        val coverSelector: String,
        val sampleDetailUrl: String?,
    )

    private data class DetailResult(
        val titleSelector: String,
        val coverSelector: String,
        val descriptionSelector: String,
        val chapterSelector: String,
    )

    /** CMS theme detected from the site's HTML fingerprint. Exposed in [DetectedFields]. */
    enum class CmsType {
        MADARA, MANGA_THEMESIA, MANGA_STREAM, KEYOAPP, MAD_THEME, MMRCMS,
        WORDPRESS_GENERIC, UNKNOWN
    }

    // ── Public entry point ────────────────────────────────────────────────────

    suspend fun detect(baseUrl: String): DetectedFields = withContext(Dispatchers.IO) {
        val normalUrl = normalizeUrl(baseUrl)
        val domain = extractDomain(normalUrl)

        onProgress?.invoke("\uD83CDF19 Fetching manga list page...")
        // Step 1: Fetch homepage (upgrade to WebView render when JS-only content detected)
          val rawHomeHtml = fetchHtml(normalUrl)
              ?: return@withContext DetectedFields(
                  siteName = domain.removePrefix("www.").substringBefore(".")
                      .replaceFirstChar { it.uppercaseChar() }
              )
          val homeHtml = if (context != null && JsRenderFetcher.isJsRendered(rawHomeHtml)) {
              JsRenderFetcher(context).fetch(normalUrl) ?: rawHomeHtml
          } else {
              rawHomeHtml
          }
          val homeDoc = Jsoup.parse(homeHtml, normalUrl)

          // Step 2: Site signals
          val siteName = extractSiteName(homeDoc, domain)
          val isWP = homeHtml.contains("wp-content") || homeHtml.contains("/wp-json/")
          val cmsType = detectCmsType(homeHtml)
          val cmsProgressMsg = when (cmsType) {
              CmsType.MADARA           -> "\uD83CDF19 Detected: WordPress Madara \u2014 routing to proven parser"
              CmsType.MANGA_THEMESIA   -> "\uD83CDF19 Detected: MangaThemesia \u2014 routing to proven parser"
              CmsType.MANGA_STREAM     -> "\uD83CDF19 Detected: MangaStream \u2014 routing to proven parser"
              CmsType.KEYOAPP         -> "\uD83CDF19 Detected: Keyoapp \u2014 routing to proven parser"
              CmsType.MAD_THEME       -> "\uD83CDF19 Detected: MadTheme \u2014 routing to proven parser"
              CmsType.MMRCMS          -> "\uD83CDF19 Detected: MMRCMS \u2014 routing to proven parser"
              CmsType.WORDPRESS_GENERIC -> "\uD83CDF19 Unknown WordPress site \u2014 analyzing with AI..."
              CmsType.UNKNOWN         -> "\uD83CDF19 Unknown site \u2014 analyzing with AI..."
          }
          onProgress?.invoke(cmsProgressMsg)
          val searchPath = detectSearchPath(homeDoc)

          // Steps 3-7: SmartPageFetcher discovers the correct manga-list page via HEAD-probed
          // common paths and nav-link scanning, then fetches a sample detail page and chapter page.
          // Every individual fetch is transparently upgraded to WebView rendering when
          // JS-only content is detected -- so comix.to-style sites work out of the box.
          onProgress?.invoke("\uD83CDF19 Looking for manga detail page...")
          val fetched  = SmartPageFetcher(context).fetch(normalUrl, homeHtml)
          val listHtml = fetched.listHtml
          val listUrl  = fetched.listUrl
          val listDoc  = if (listUrl == normalUrl) homeDoc else Jsoup.parse(listHtml, listUrl)
          val listPath = extractRelativePath(listUrl, normalUrl)

          // Step 4: Detect manga cards
          val cardResult = detectMangaCards(listDoc, listUrl, cmsType)

          // Step 5: Detail page -- prefer SmartPageFetcher result, fall back to card link / DOM scan
          val detailUrl  = fetched.detailUrl
              ?: cardResult?.sampleDetailUrl
              ?: findDetailUrlFallback(listDoc, normalUrl)
          val detailHtml = fetched.detailHtml
              ?: if (detailUrl != null) fetchHtml(detailUrl) else null
          val detailDoc  = if (detailHtml != null && detailUrl != null)
              Jsoup.parse(detailHtml, detailUrl) else null

          // Step 6: Detect detail selectors
          val detailResult = if (detailDoc != null) detectDetailSelectors(detailDoc, cmsType) else null

          // Step 7: Chapter page -- prefer SmartPageFetcher result, fall back to detail doc scan
          val chapterUrl  = fetched.chapterUrl
              ?: detailDoc?.let { findChapterUrl(it, normalUrl) }
          onProgress?.invoke("\uD83CDF19 Looking for chapter page...")
          val chapterHtml = fetched.chapterHtml
              ?: if (chapterUrl != null) fetchHtml(chapterUrl) else null
          val chapterDoc  = if (chapterHtml != null && chapterUrl != null)
              Jsoup.parse(chapterHtml, chapterUrl) else null

          // Step 8: Detect page images
          val pageImageSel = if (chapterDoc != null) detectPageImages(chapterDoc) else ""

          // Step 9: Feed learning session with HtmlCleaner-stripped HTML.
          // Scripts, styles, SVGs, comments, and inline attributes are removed and the result
          // is capped at 15,000 chars (middle section) so Gemini sees only content-rich markup.
          runCatching {
              val session = LearningSession()
              session.domain = domain
              session.capture(PageType.MANGA_LIST, listUrl, HtmlCleaner.cleanAndCap(listHtml))
              if (detailHtml != null && detailUrl != null)
                  session.capture(PageType.MANGA_DETAIL, detailUrl, HtmlCleaner.cleanAndCap(detailHtml))
              if (chapterHtml != null && chapterUrl != null)
                  session.capture(PageType.CHAPTER_READER, chapterUrl, HtmlCleaner.cleanAndCap(chapterHtml))
          }



        // Layer 2: Gemini AI analysis for unknown/generic CMS sites
        // Only invoked when CSS-only analysis would give low confidence.
        val geminiResult: GeminiSelectorAnalyzer.AnalysisResult? = if (
            (cmsType == CmsType.UNKNOWN || cmsType == CmsType.WORDPRESS_GENERIC) &&
            !geminiApiKey.isNullOrBlank()
        ) {
            runCatching {
                GeminiSelectorAnalyzer().analyze(
                    listHtml    = listHtml,
                    detailHtml  = detailHtml,
                    chapterHtml = chapterHtml,
                    listUrl     = listUrl,
                    domain      = domain,
                    apiKey      = geminiApiKey,
                    onProgress  = onProgress,
                )
            }.onFailure { Log.w(TAG, "Gemini layer failed, using CSS fallback", it) }.getOrNull()
        } else null

        // If Gemini succeeded and produced selectors, use its result directly.
        // Otherwise fall through to the CSS-based assembly below (Layer 3).
        if (geminiResult != null) {
            val conf = geminiResult.confidence
            val doneMsg = if (conf == "low") {
                val n = geminiResult.fields.run {
                    listOf(cardSelector, titleSelector, coverSelector, detailTitle,
                           description, chapterSelector, pageImageSelector)
                        .count { it.isNotEmpty() }
                }
                "\u26A0\uFE0F Done with low confidence. Please review $n fields."
            } else {
                val n = geminiResult.fields.run {
                    listOf(cardSelector, titleSelector, coverSelector, detailTitle,
                           description, chapterSelector, pageImageSelector)
                        .count { it.isNotEmpty() }
                }
                "\u2713 Done! $n fields detected. Parser ready."
            }
            onProgress?.invoke(doneMsg)
            return@withContext geminiResult.fields.copy(
                siteName = siteName.ifEmpty { geminiResult.fields.siteName },
                listPath = geminiResult.fields.listPath.ifEmpty { listPath },
                searchPath = geminiResult.fields.searchPath.ifEmpty { searchPath },
                paginationType = if (isWP) "path" else "page",
                cmsType = cmsType,
            )
        }
        // Step 10: Assemble result
        // Build multi-selector fallback strings — combine all patterns that match the listing
        // page, so the parser has fallbacks when the primary selector is too specific.
        val cardSel         = buildMultiSelector(listDoc, cardResult?.cardSelector)
        val titleSel        = cardResult?.titleSelector ?: ""
        val coverSel        = cardResult?.coverSelector ?: ""
        val detailTitleSel  = detailResult?.titleSelector ?: ""
        val descSel         = detailResult?.descriptionSelector ?: ""
        val chapSel         = detailResult?.chapterSelector ?: ""

        // WordPress path-based pagination (/page/N/) vs query-param (?page=N)
        val paginationType = when {
            isWP -> "path"
            cmsType == CmsType.MADARA || cmsType == CmsType.MANGA_THEMESIA ||
            cmsType == CmsType.MANGA_STREAM || cmsType == CmsType.MAD_THEME -> "path"
            else -> "page"
        }

        fun score(sel: String) = when {
            sel.isEmpty() -> Confidence.LOW
            sel.contains('.') || sel.contains('#') || sel.contains('[') -> Confidence.HIGH
            else -> Confidence.MEDIUM
        }

        DetectedFields(
            siteName          = siteName,
            listPath          = listPath.ifEmpty { "/" },
            searchPath        = searchPath,
            cardSelector      = cardSel,
            titleSelector     = titleSel,
            coverSelector     = coverSel,
            detailTitle       = detailTitleSel,
            description       = descSel,
            chapterSelector   = chapSel,
            pageImageSelector = pageImageSel,
            paginationType    = paginationType,
            cmsType           = cmsType,
            fieldConfidence   = mapOf(
                "cardSelector"      to score(cardSel),
                "titleSelector"     to score(titleSel),
                "coverSelector"     to score(coverSel),
                "detailTitle"       to score(detailTitleSel),
                "description"       to score(descSel),
                "chapterSelector"   to score(chapSel),
                "pageImageSelector" to score(pageImageSel),
                "listPath"   to if (listPath.isNotEmpty()) Confidence.HIGH else Confidence.LOW,
                "searchPath" to if (searchPath.isNotEmpty()) Confidence.HIGH else Confidence.LOW,
            ),
        )
        .also { f ->
            val n = listOf(f.cardSelector, f.titleSelector, f.coverSelector, f.detailTitle, f.description, f.chapterSelector, f.pageImageSelector).count { it.isNotEmpty() }
            val msg = if (f.fieldConfidence.values.count { it == Confidence.LOW } > 4) {
                "\u26A0\uFE0F Done with low confidence. Please review fields."
            } else {
                "\u2713 Done! $n fields detected. Parser ready."
            }
            onProgress?.invoke(msg)
        }
    }

    /**
     * Given the primary detected card selector, also finds all other common patterns
     * that return >= 2 elements on [doc] and combines them as a comma-separated CSS
     * selector string. This means the parser always has fallbacks even if one selector
     * stops matching after a site redesign.
     */
    private fun buildMultiSelector(doc: Document, primary: String?): String {
        val candidates = listOf(
            "div.page-item-detail", "div.c-tabs-item__content", ".c-image-hover",
            "div.bsx", "div.bs",
            "article.type-manga", "article.type-manhwa", "article.type-comic",
            "article.type-manhua", "article.type-webtoon",
            ".manga-item", ".manga-card", ".book-item", ".series-item", ".series-card",
            ".story-item", "div.manga__item", "li.wp-block-post",
        )
        val matched = mutableListOf<String>()
        if (!primary.isNullOrEmpty()) matched.add(primary)
        for (sel in candidates) {
            if (!primary.isNullOrEmpty() && sel == primary) continue
            if (doc.select(sel).size >= 2 && sel !in matched) matched.add(sel)
        }
        return matched.joinToString(", ").ifEmpty { primary.orEmpty() }
    }

    // ── URL helpers ───────────────────────────────────────────────────────────

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return url.trimEnd('/')
    }

    private fun extractDomain(url: String): String =
        runCatching { URI(url).host ?: "" }.getOrDefault("")

    private fun extractRelativePath(fullUrl: String, baseUrl: String): String =
        runCatching {
            val base = URI(baseUrl)
            val full = URI(fullUrl)
            if (base.host == full.host) full.path.ifEmpty { "/" } else "/"
        }.getOrDefault("/")

    // ── HTTP fetch ────────────────────────────────────────────────────────────

    private fun fetchHtml(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
        conn.setRequestProperty("Connection", "keep-alive")
        conn.setRequestProperty("Upgrade-Insecure-Requests", "1")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true

        if (conn.responseCode !in 200..299) {
            Log.w(TAG, "HTTP ${conn.responseCode} for $url")
            return@runCatching null
        }
        val stream = if (conn.contentEncoding == "gzip") GZIPInputStream(conn.inputStream)
                     else conn.inputStream
        stream.bufferedReader(Charsets.UTF_8).readText().take(300_000)
    }.onFailure { Log.w(TAG, "fetchHtml failed: $url", it) }.getOrNull()

    // ── Site metadata ─────────────────────────────────────────────────────────

    private fun extractSiteName(doc: Document, domain: String): String {
        val og = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim()
        if (!og.isNullOrEmpty()) return og
        val title = doc.title().trim()
            .substringBefore(" - ").substringBefore(" | ").substringBefore(" – ").trim()
        if (title.isNotEmpty() && title.length < 60) return title
        return domain.removePrefix("www.").substringBefore(".")
            .replaceFirstChar { it.uppercaseChar() }
    }

    // ── CMS detection ─────────────────────────────────────────────────────────

    private fun detectCmsType(html: String): CmsType = when {
        html.contains("wp-manga") || html.contains("WpMangaReader") ||
            html.contains("madara") || html.contains("wp-manga-chapter")   -> CmsType.MADARA
        html.contains("ts_reader.run") ||
            (html.contains(".bsx") && html.contains("anilist"))            -> CmsType.MANGA_THEMESIA
        html.contains("WPMangaStream") || html.contains("readerarea")      -> CmsType.MANGA_STREAM
        html.contains("series-card") && html.contains("series_tags_page")  -> CmsType.KEYOAPP
        html.contains("book-item") && html.contains("wp-content") &&
            html.contains("/search/")                                       -> CmsType.MAD_THEME
        html.contains("filterList") && html.contains("media-body")         -> CmsType.MMRCMS
        html.contains("wp-content") || html.contains("/wp-json/")          -> CmsType.WORDPRESS_GENERIC
        else                                                                -> CmsType.UNKNOWN
    }

    // ── Search path ───────────────────────────────────────────────────────────

    private fun detectSearchPath(doc: Document): String {
        val form = doc.select("form").firstOrNull { f ->
            f.select(
                "input[type=search], input[name=s], input[name=q]," +
                " input[name=keyword], input[name=query], input[name=search]"
            ).isNotEmpty()
        } ?: return ""

        val action = form.attr("action").trim().let { a ->
            when {
                a.isEmpty() || a == "#" || a.startsWith("javascript") -> "/"
                a.startsWith("http") ->
                    runCatching { "/${URI(a).path.trimStart('/')}" }.getOrDefault("/")
                else -> if (a.startsWith("/")) a else "/$a"
            }
        }
        val inputName = form.select(
            "input[type=search], input[name=s], input[name=q], input[name=keyword], input[name=query]"
        ).firstOrNull()?.attr("name")?.takeIf { it.isNotEmpty() } ?: "s"
        return "$action?$inputName="
    }

    // ── Manga listing page discovery ──────────────────────────────────────────

    private fun findMangaListPage(
        homeDoc: Document,
        homeHtml: String,
        baseUrl: String,
        isWP: Boolean,
    ): Pair<String, String> {
        // If the homepage already has enough known card elements, use it
        val knownSelectors = listOf(
            ".manga-item", ".book-item", "article.type-manga", ".c-image-hover",
            ".page-item-detail", ".bsx", ".series-card", ".manga-card",
            ".list-truyen-item-wrap", ".story-item",
        )
        for (sel in knownSelectors) {
            if (homeDoc.select(sel).size >= 4) return homeHtml to baseUrl
        }

        // WordPress: look for manga post-type archive link in the navigation menu
        if (isWP) {
            val wpArchiveSelectors = listOf(
                "li[class*=menu-item-object-manga] a[href]",
                "li[class*=menu-item-object-manhwa] a[href]",
                "li[class*=menu-item-object-comic] a[href]",
                "li[class*=menu-item-object-webtoon] a[href]",
                "li[class*=menu-item-object-manhua] a[href]",
                "li[class*=post_type_archive][class*=manga] a[href]",
                "li[class*=post_type_archive][class*=comic] a[href]",
            )
            val baseDomain = extractDomain(baseUrl)
            for (sel in wpArchiveSelectors) {
                val href = homeDoc.selectFirst(sel)?.absUrl("href") ?: continue
                if (extractDomain(href) != baseDomain) continue
                val html = fetchHtml(href) ?: continue
                Log.d(TAG, "WP manga archive found: $href")
                return html to href
            }
        }

        // Try common manga listing paths
        for (path in listOf("/manga/", "/manhwa/", "/comics/", "/webtoon/", "/series/", "/manhua/", "/list/")) {
            val testUrl = "$baseUrl$path"
            val html = fetchHtml(testUrl) ?: continue
            if (html.length > 5_000 && !html.contains("404") &&
                (html.contains("/manga/") || html.contains("/manhwa/") ||
                 html.contains("/chapter") || html.contains("chapter-"))
            ) {
                Log.d(TAG, "Manga list found at common path: $testUrl")
                return html to testUrl
            }
        }

        return homeHtml to baseUrl
    }

    // ── Card detection ────────────────────────────────────────────────────────

    private fun detectMangaCards(doc: Document, baseUrl: String, cmsType: CmsType): CardResult? {
        // Strategy 1: CMS-specific known patterns
        val cmsPatterns: List<Triple<String, String, String>> = when (cmsType) {
            CmsType.MADARA -> listOf(
                Triple("div.page-item-detail", ".post-title a, h3 a", "img"),
                Triple(".c-image-hover", ".post-title a, h3 a", "img"),
                Triple("div.manga__item", "h3 a, h2 a", "img"),
            )
            CmsType.MANGA_THEMESIA -> listOf(
                Triple(".bsx", "h2.tt, .tt", "img"),
                Triple(".bs", "h2, h3", "img"),
            )
            CmsType.MANGA_STREAM -> listOf(
                Triple(".utao .uta", "h4, h3", "img"),
                Triple(".utao", "h4", "img"),
            )
            CmsType.KEYOAPP -> listOf(
                Triple(".series-card", "h3, h2", "img"),
                Triple(".series-item", "h3, h2", "img"),
            )
            CmsType.MAD_THEME -> listOf(
                Triple(".book-item", ".title, h3, h2", ".book-img img, img"),
                Triple(".novel-item", ".novel-title", "img"),
            )
            CmsType.MMRCMS -> listOf(
                Triple(".media", ".media-heading a", "img"),
                Triple(".col-sm-6", "h6, h5", "img"),
            )
            CmsType.WORDPRESS_GENERIC, CmsType.UNKNOWN -> listOf(
                Triple("article.type-manga", ".entry-title a, h2 a, h1 a", "img.wp-post-image, img"),
                Triple("article.type-comic", ".entry-title a, h2 a", "img"),
                Triple(".manga-item", "h3 a, .title a, a", "img"),
                Triple("li.wp-block-post", "h2.wp-block-post-title a, h3 a", "img"),
            )
        }

        for ((cardSel, titleSel, coverSel) in cmsPatterns) {
            val cards = doc.select(cardSel)
            if (cards.size >= 2) {
                val sampleLink = cards.first()?.select("a[href]")?.firstOrNull()?.absUrl("href")
                Log.d(TAG, "CMS card match: $cardSel (${cards.size} cards)")
                return CardResult(cardSel, titleSel, coverSel, sampleLink)
            }
        }

        // Strategy 2: Generic WordPress / common patterns
        val genericPatterns = listOf(
            Triple("article.type-manga", ".entry-title a, h2 a", "img"),
            Triple("article.type-comic", ".entry-title a, h2 a", "img"),
            Triple("article.type-manhwa", ".entry-title a, h2 a", "img"),
            Triple("article[class*=type-manga]", ".entry-title a, h2 a", "img"),
            Triple(".manga-item", "h3 a, .title a", "img"),
            Triple(".comic-item", "h3 a, a", "img"),
            Triple(".book-item", ".title, h3", ".book-img img, img"),
            Triple(".story-item", "h3 a, h2 a", "img"),
            Triple(".novel-item", ".novel-title a", "img"),
            Triple("li.wp-block-post", "h2.wp-block-post-title a, h3 a", "img"),
        )
        for ((cardSel, titleSel, coverSel) in genericPatterns) {
            val cards = doc.select(cardSel)
            if (cards.size >= 2) {
                val sampleLink = cards.first()?.select("a[href]")?.firstOrNull()?.absUrl("href")
                return CardResult(cardSel, titleSel, coverSel, sampleLink)
            }
        }

        // Strategy 3: Structural DOM analysis (works for Tailwind / custom themes)
        return detectMangaCardsStructural(doc, baseUrl)
    }

    private fun detectMangaCardsStructural(doc: Document, baseUrl: String): CardResult? {
        val baseDomain = extractDomain(baseUrl)

        // Pass A: find <a href> elements that contain an image and point to detail pages
        val mangaAnchors = doc.select("a[href]").filter { anchor ->
            val href = anchor.absUrl("href")
            href.startsWith("http") &&
            anchor.select("img").isNotEmpty() &&
            looksLikeMangaDetailUrl(href, baseUrl)
        }

        if (mangaAnchors.size >= 4) {
            val grouped = mangaAnchors.groupBy { a ->
                val p = a.parent() ?: return@groupBy "none"
                val cls = p.classNames().firstOrNull { isSemanticClass(it) } ?: ""
                "${p.tagName()}|$cls"
            }.filter { it.value.size >= 3 }

            val best = grouped.maxByOrNull { it.value.size }
            if (best != null) {
                val sample = best.value.first()
                val cardSel = buildBestSelector(sample.parent() ?: sample)
                Log.d(TAG, "Structural (anchor): $cardSel (${best.value.size})")
                return CardResult(cardSel, "h3, h2, h4, span, a", "img", sample.absUrl("href"))
            }
        }

        // Pass B: find container elements (div/article/li) that wrap linked images
        val containers = doc.select("div, article, li, section").filter { el ->
            val links = el.select("a[href]")
            val imgs  = el.select("img")
            if (links.isEmpty() || imgs.isEmpty()) return@filter false
            if (el.children().size !in 1..10) return@filter false
            val textLen = el.select("h1,h2,h3,h4,h5,h6,span,p,a").sumOf { it.text().length }
            if (textLen < 3) return@filter false
            val href = links.firstOrNull()?.absUrl("href") ?: return@filter false
            looksLikeMangaDetailUrl(href, baseUrl) && extractDomain(href) == baseDomain
        }

        if (containers.size < 3) {
            Log.w(TAG, "Structural: only ${containers.size} candidate containers found")
            return null
        }

        val grouped = containers.groupBy { el ->
            val cls = el.classNames().firstOrNull { isSemanticClass(it) } ?: ""
            "${el.tagName()}|$cls"
        }.filter { it.value.size >= 3 }

        val best = grouped.maxByOrNull { it.value.size } ?: return null
        val sample = best.value.first()
        val cardSel = buildBestSelector(sample)
        val sampleLink = sample.select("a[href]").firstOrNull()?.absUrl("href")
        Log.d(TAG, "Structural (container): $cardSel (${best.value.size})")
        return CardResult(cardSel, "h3, h2, h4, span, a", "img", sampleLink)
    }

    // ── Selector builder ──────────────────────────────────────────────────────

    private fun buildBestSelector(el: Element?): String {
        if (el == null) return "div"
        // ID first
        val id = el.id()
        if (id.isNotEmpty() && !id.matches(Regex("^\\d+$"))) return "#$id"
        // Own semantic class
        val ownCls = el.classNames().firstOrNull { isSemanticClass(it) }
        if (ownCls != null) return "${el.tagName()}.$ownCls"
        // Walk ancestors for semantic anchor
        var parent = el.parent()
        var depth = 0
        while (parent != null && depth < 4) {
            val pid = parent.id()
            if (pid.isNotEmpty() && !pid.matches(Regex("^\\d+$"))) {
                return "#$pid > ${el.tagName()}"
            }
            val pCls = parent.classNames().firstOrNull { isSemanticClass(it) }
            if (pCls != null) return ".$pCls ${el.tagName()}"
            parent = parent.parent()
            depth++
        }
        return el.tagName()
    }

    /**
     * Returns true if the CSS class name is semantic (not a Tailwind utility).
     * Filters out responsive prefixes (sm:, md:), state prefixes (hover:, focus:),
     * numeric-suffix utilities (mt-4, px-2), and known single-word utilities.
     */
    private fun isSemanticClass(cls: String): Boolean {
        if (cls.length < 3) return false
        if (cls.contains(':')) return false          // Tailwind prefix variants
        if (cls.contains('[')) return false          // Arbitrary values: w-[300px]
        if (cls.matches(Regex("[a-z]+-\\d+(\\.\\d+)?$"))) return false  // mt-4, px-2
        val tailwindWords = setOf(
            "flex", "grid", "block", "inline", "hidden", "relative", "absolute", "fixed",
            "sticky", "static", "overflow", "truncate", "rounded", "shadow", "border",
            "outline", "grow", "shrink", "container", "italic", "bold", "underline",
            "space", "visible", "invisible", "items", "content", "self", "justify",
            "center", "start", "end", "between", "around", "evenly", "stretch",
            "baseline", "transform", "transition", "animate", "cursor", "pointer",
            "select", "resize", "appearance", "list", "float", "clear", "table", "row",
            "col", "sub", "super", "not", "odd", "even", "first", "last", "fill",
            "stroke", "ring", "divide", "object", "aspect", "leading", "tracking",
            "antialiased", "uppercase", "lowercase", "capitalize", "normal",
            "collapse", "separate", "auto", "none", "full", "screen", "min", "max",
            "dark", "group", "peer", "sr", "print", "will", "snap", "scroll", "wrap",
        )
        return cls !in tailwindWords
    }

    // ── Manga URL heuristic ───────────────────────────────────────────────────

    private fun looksLikeMangaDetailUrl(href: String, baseUrl: String): Boolean {
        if (!href.startsWith("http")) return false
        if (extractDomain(href) != extractDomain(baseUrl)) return false
        val path = runCatching { URI(href).path }.getOrNull() ?: return false
        val segments = path.split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        val skip = setOf(
            "search", "genre", "tag", "category", "author", "artist", "page", "login",
            "register", "account", "profile", "contact", "about", "privacy", "terms",
            "faq", "help", "index", "sitemap", "feed", "rss", "wp-content", "wp-admin",
            "wp-login.php", "cdn-cgi",
        )
        return segments.none { it in skip }
    }

    // ── Fallback detail URL ───────────────────────────────────────────────────

    private fun findDetailUrlFallback(doc: Document, baseUrl: String): String? {
        for (sel in listOf(
            ".manga-card a[href]", ".book-item a[href]", ".manga-item a[href]",
            ".c-image-hover a[href]", ".post-title a[href]", "article a[href]",
            ".item a[href]", "h3 a[href]", "h2 a[href]",
        )) {
            val href = doc.selectFirst(sel)?.absUrl("href") ?: continue
            if (looksLikeMangaDetailUrl(href, baseUrl)) return href
        }
        return doc.select("a[href]").firstNotNullOfOrNull { el ->
            val href = el.absUrl("href")
            val path = runCatching { URI(href).path }.getOrNull() ?: return@firstNotNullOfOrNull null
            if (path.split("/").filter { it.isNotEmpty() }.size >= 2 &&
                looksLikeMangaDetailUrl(href, baseUrl)
            ) href else null
        }
    }

    // ── Chapter URL ───────────────────────────────────────────────────────────

    private fun findChapterUrl(doc: Document, baseUrl: String): String? {
        for (sel in listOf(
            ".wp-manga-chapter a[href]", ".chapter-list a[href]", "#chapters a[href]",
            "ul.chapters li a[href]", ".chapter-item a[href]", ".listing-chapters a[href]",
            ".chapter-link[href]", ".chapter_list a[href]", ".chapter-row a[href]",
            ".eph-num a[href]", "li.chapter a[href]", ".volume-chapter a[href]",
        )) {
            val href = doc.selectFirst(sel)?.absUrl("href")
                ?.takeIf { it.startsWith("http") } ?: continue
            return href
        }
        return doc.select("a[href*=chapter], a[href*=chap-]")
            .firstOrNull { a ->
                val href = a.absUrl("href")
                href.startsWith("http") && extractDomain(href) == extractDomain(baseUrl)
            }?.absUrl("href")
    }

    // ── Detail page selectors ─────────────────────────────────────────────────

    private fun detectDetailSelectors(doc: Document, cmsType: CmsType): DetailResult {
        val (cmsTitle, cmsCover, cmsDesc, cmsChapter) = when (cmsType) {
            CmsType.MADARA -> listOf(
                ".post-title h1, .manga-title h1, h1.entry-title",
                ".summary_image img, .manga-thumbnail img, img.wp-post-image",
                ".summary__content p, .description-summary p",
                ".wp-manga-chapter a",
            )
            CmsType.MANGA_THEMESIA -> listOf(
                "h1.entry-title, .mangainfo h1",
                ".thumb img, .manga-thumbnail img",
                ".entry-content p, .synp p",
                ".eph-num a",
            )
            CmsType.MANGA_STREAM -> listOf(
                "h1.entry-title, h1",
                ".thumb img",
                ".entry-content p",
                "li.wp-manga-chapter a, .ept-links a",
            )
            CmsType.MAD_THEME -> listOf(
                "h1, .book-title",
                ".book-img img, .book-cover img, .book-thumbnail img",
                ".book-intro p, .summary p, .desc p",
                ".chapter-list .chapter a, .episode-item a",
            )
            CmsType.KEYOAPP -> listOf(
                "h1.series-title, h1",
                ".series-image img, .series-thumb img",
                ".summary p, .entry-content p",
                "#chapters > a",
            )
            else -> listOf("", "", "", "")
        }

        val title = validateOrFallback(doc, cmsTitle) { detectTitleSelector(doc) }
        val cover = validateOrFallback(doc, cmsCover) { detectCoverSelector(doc) }
        val desc  = validateOrFallback(doc, cmsDesc, minText = 20) { detectDescSelector(doc) }
        val chap  = validateOrFallback(doc, cmsChapter) { detectChapterSelector(doc) }

        return DetailResult(title, cover, desc, chap)
    }

    private fun validateOrFallback(
        doc: Document,
        candidate: String,
        minCount: Int = 1,
        minText: Int = 0,
        fallback: () -> String,
    ): String {
        if (candidate.isNotEmpty()) {
            val els = doc.select(candidate)
            if (els.size >= minCount && (minText == 0 || els.text().length >= minText)) {
                return candidate
            }
        }
        return fallback()
    }

    private fun detectTitleSelector(doc: Document): String {
        for (sel in listOf(
            "h1.entry-title", ".manga-title h1", ".post-title h1", ".series-title h1",
            ".book-title h1", ".manga-info h1", "h1",
        )) {
            if (doc.selectFirst(sel)?.text()?.length?.let { it > 2 } == true) return sel
        }
        return "h1"
    }

    private fun detectCoverSelector(doc: Document): String {
        for (sel in listOf(
            ".summary_image img", ".manga-thumbnail img", ".manga-cover img",
            ".book-cover img", ".series-thumbnail img",
            "img.wp-post-image", ".cover img", ".thumb img",
            ".manga-poster img", ".book-img img",
        )) {
            if (doc.selectFirst(sel) != null) return sel
        }
        return "img"
    }

    private fun detectDescSelector(doc: Document): String {
        for (sel in listOf(
            ".summary__content p", ".manga-summary p", ".entry-content > p",
            ".description-summary p", ".manga-desc p", ".book-intro p",
            ".summary p", ".synopsis p", ".desc p",
        )) {
            if (doc.select(sel).text().length > 20) return sel
        }
        return "p"
    }

    private fun detectChapterSelector(doc: Document): String {
        for (sel in listOf(
            ".wp-manga-chapter a", ".chapter-list a", ".listing-chapters a",
            "#chapters a", "ul.chapters li a", ".chapter-item a",
            "li.chapter a", ".chapter_list a", ".chapter-row a",
            ".volume-chapter a", ".episode-item a",
        )) {
            if (doc.select(sel).size >= 1) return sel
        }
        val chapterLinks = doc.select("a[href*=chapter], a[href*=chap-]")
        if (chapterLinks.size >= 1) {
            val parentSel = buildBestSelector(chapterLinks.first()?.parent())
            return "$parentSel a"
        }
        return "a[href*=chapter]"
    }

    // ── Chapter reader images ─────────────────────────────────────────────────

    private fun detectPageImages(doc: Document): String {
        for (sel in listOf(
            "#readerarea img", ".reading-content img", ".chapter-content img",
            ".manga-reading-area img", ".page-break img", ".reader-area img",
            "#viewer img", ".comic-reader img", "#chapter-images img",
            ".chapter-images img", ".chapter-image img", ".wp-manga-chapter-img",
        )) {
            if (doc.select(sel).size >= 2) return sel
        }
        val best = doc.select("div, section, main").maxByOrNull { it.select("img").size }
        if (best != null && best.select("img").size >= 2) {
            return "${buildBestSelector(best)} img"
        }
        return "img"
    }

    companion object {
        private const val TAG = "SiteAutoDetector"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.6367.118 Safari/537.36"
    }
}

// Destructure List<String> as 4 components
private operator fun List<String>.component4(): String = this[3]
