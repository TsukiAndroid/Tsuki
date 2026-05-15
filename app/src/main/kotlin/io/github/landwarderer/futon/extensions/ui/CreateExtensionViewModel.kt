package io.github.landwarderer.futon.extensions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.core.network.MangaHttpClient
import io.github.landwarderer.futon.extensions.data.ExtensionRepository
import io.github.landwarderer.futon.extensions.domain.Extension
import io.github.landwarderer.futon.extensions.domain.ExtensionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import javax.inject.Inject

sealed interface DetectState {
    data object Idle : DetectState
    data object Detecting : DetectState
    data class Detected(val cmsName: String, val description: String, val template: String) : DetectState
    data class Failed(val reason: String) : DetectState
}

@HiltViewModel
class CreateExtensionViewModel @Inject constructor(
    private val extensionRepository: ExtensionRepository,
    @MangaHttpClient private val okHttpClient: OkHttpClient,
) : ViewModel() {

    private val _detectState = MutableStateFlow<DetectState>(DetectState.Idle)
    val detectState: StateFlow<DetectState> = _detectState.asStateFlow()

    fun detectParser(baseUrl: String) {
        val url = baseUrl.trim().trimEnd('/')
        if (!url.startsWith("http")) {
            _detectState.value = DetectState.Failed("Enter a valid URL starting with https://")
            return
        }
        _detectState.value = DetectState.Detecting
        viewModelScope.launch {
            _detectState.value = withContext(Dispatchers.IO) {
                val html = fetchHtml(url)
                    ?: return@withContext DetectState.Failed("Could not reach $url — check the URL and your connection.")
                val (cmsName, desc, template) = identifyCms(html, url)
                DetectState.Detected(cmsName, desc, template)
            }
        }
    }

    fun resetDetect() {
        _detectState.value = DetectState.Idle
    }

    fun createExtension(
        name: String,
        lang: String,
        baseUrl: String,
        apiUrl: String,
        iconUrl: String,
        notes: String,
        scriptLanguage: ExtensionType,
        sourceType: String,
        contentTarget: String,
        detectedTemplate: String? = null,
    ) {
        viewModelScope.launch {
            val sourceCode = when {
                detectedTemplate != null -> detectedTemplate
                scriptLanguage == ExtensionType.JS -> genericJsTemplate(baseUrl)
                scriptLanguage == ExtensionType.DART -> dartTemplate(name, baseUrl)
                else -> ""
            }
            val extension = Extension(
                id = UUID.randomUUID().toString(),
                name = name,
                version = "1.0.0",
                author = "",
                description = "",
                baseUrl = baseUrl,
                apiUrl = apiUrl,
                language = lang.ifBlank { "en" },
                iconUrl = iconUrl,
                notes = notes,
                type = scriptLanguage,
                sourceType = sourceType,
                contentTarget = contentTarget,
                sourceCode = sourceCode,
                packageName = "",
                templateName = "",
                isEnabled = true,
                installedAt = System.currentTimeMillis(),
            )
            extensionRepository.save(extension)
        }
    }

    // ─── CMS detection ───────────────────────────────────────────────────────

    private data class CmsInfo(val name: String, val description: String, val template: String)

    private fun identifyCms(html: String, baseUrl: String): CmsInfo = when {
        html.contains("wp-manga") || html.contains("madara-") || html.contains("manga-chapters-head") ->
            CmsInfo(
                "Madara (WP-Manga)",
                "WordPress + Madara theme — used by 1,000+ manga sites",
                maDaraTemplate(baseUrl),
            )

        html.contains("panel-story-chapter-list") || html.contains("readmanganato") || html.contains("manganelo") ->
            CmsInfo(
                "Manganelo / Manganato",
                "Custom PHP CMS used by the manganelo / manganato family of sites",
                manganeloTemplate(baseUrl),
            )

        html.contains("listupd") || html.contains("komiktoon") || html.contains("asurascans") || html.contains("ts-main-image") ->
            CmsInfo(
                "MangaStream / ListUpd",
                "WordPress with MangaStream / ListUpd theme",
                mangaStreamTemplate(baseUrl),
            )

        html.contains("manga-item__link") || html.contains("manga-item__info") ->
            CmsInfo(
                "ManhwaRead-style",
                "Sites using the manga-item class structure (e.g. manhwaread.com)",
                manhwaReadTemplate(baseUrl),
            )

        html.contains("api.mangadex.org") || html.contains("mangadex.org/title") ->
            CmsInfo(
                "MangaDex",
                "MangaDex REST API — chapter and cover data come from the API, not HTML",
                mangadexTemplate(baseUrl),
            )

        html.contains("manga") && (html.contains("chapter") || html.contains("manhwa")) ->
            CmsInfo(
                "Generic Manga Site",
                "No specific CMS fingerprint found — a generic skeleton was generated. Selectors will need tweaking.",
                genericJsTemplate(baseUrl),
            )

        else ->
            CmsInfo(
                "Unknown / Custom",
                "No known manga CMS detected. A bare-bones skeleton was generated.",
                genericJsTemplate(baseUrl),
            )
    }

    // ─── Per-CMS JS templates ─────────────────────────────────────────────────

    private fun maDaraTemplate(baseUrl: String) = """
// Madara (WP-Manga) Extension — auto-generated
// CMS: WordPress + Madara theme
// Selectors may need minor tweaks per site.

function getMangaListUrl(offset, query) {
  if (query) return "$baseUrl/?s=" + encodeURIComponent(query) + "&post_type=wp-manga";
  var page = Math.floor(offset / 20) + 1;
  return "$baseUrl/manga/?page=" + page;
}

function getMangaList(html, offset, query) {
  var items = [];
  var re = /<div class="post-title"[^>]*>[\s\S]*?<a href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
  var imgRe = /<img[^>]+src="([^"]+)"[^>]*class="[^"]*img-responsive[^"]*"/g;
  var m, im;
  while ((m = re.exec(html)) !== null) {
    var url = m[1].trim();
    var title = m[2].replace(/<[^>]+>/g, "").trim();
    var cover = (im = imgRe.exec(html)) !== null ? im[1] : "";
    items.push({ url: url, title: title, cover: cover });
  }
  return JSON.stringify({ items: items });
}

function getMangaDetails(html, url) {
  var title = (html.match(/<div class="post-title"[^>]*>[\s\S]*?<h[12][^>]*>([\s\S]*?)<\/h[12]>/) || [])[1] || "";
  title = title.replace(/<[^>]+>/g, "").trim();
  var cover = (html.match(/<div class="summary_image">[\s\S]*?<img[^>]+src="([^"]+)"/) || [])[1] || "";
  var desc = (html.match(/<div class="summary__content[^"]*"[^>]*>([\s\S]*?)<\/div>/) || [])[1] || "";
  desc = desc.replace(/<[^>]+>/g, "").trim();
  var chapters = [];
  var chRe = /<li class="[^"]*wp-manga-chapter[^"]*"[^>]*>[\s\S]*?<a href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?<span class="chapter-release-date"[^>]*>([\s\S]*?)<\/span>/g;
  var cm;
  while ((cm = chRe.exec(html)) !== null) {
    chapters.push({
      url: cm[1].trim(),
      name: cm[2].replace(/<[^>]+>/g, "").trim(),
      date: cm[3].replace(/<[^>]+>/g, "").trim()
    });
  }
  return JSON.stringify({ url: url, title: title, cover: cover, description: desc, chapters: chapters });
}

function getChapterPages(html, url) {
  var pages = [];
  var re = /"url":"(https?:\\/\\/[^"]+\\.(?:jpg|jpeg|png|webp|gif))"/gi;
  var m;
  while ((m = re.exec(html)) !== null) {
    pages.push({ index: pages.length, url: m[1].replace(/\\\\/g, "") });
  }
  if (pages.length === 0) {
    var re2 = /<img[^>]+class="[^"]*wp-manga-chapter-img[^"]*"[^>]+src="([^"]+)"/gi;
    while ((m = re2.exec(html)) !== null) pages.push({ index: pages.length, url: m[1] });
  }
  return JSON.stringify({ pages: pages });
}
""".trimIndent()

    private fun mangaStreamTemplate(baseUrl: String) = """
// MangaStream / ListUpd Extension — auto-generated
// CMS: WordPress with MangaStream / ListUpd theme

function getMangaListUrl(offset, query) {
  if (query) return "$baseUrl/?s=" + encodeURIComponent(query);
  var page = Math.floor(offset / 18) + 1;
  return "$baseUrl/manga/?page=" + page;
}

function getMangaList(html, offset, query) {
  var items = [];
  var re = /<div class="bsx"[\s\S]*?<a href="([^"]+)"[^>]*title="([^"]+)"[\s\S]*?<img[^>]+src="([^"]+)"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    items.push({ url: m[1].trim(), title: m[2].trim(), cover: m[3].trim() });
  }
  return JSON.stringify({ items: items });
}

function getMangaDetails(html, url) {
  var title = (html.match(/<h1[^>]*class="[^"]*entry-title[^"]*"[^>]*>([\s\S]*?)<\/h1>/) || [])[1] || "";
  title = title.replace(/<[^>]+>/g, "").trim();
  var cover = (html.match(/<div class="thumb"[^>]*>[\s\S]*?<img[^>]+src="([^"]+)"/) || [])[1] || "";
  var desc = (html.match(/<div[^>]+itemprop="description"[^>]*>([\s\S]*?)<\/div>/) || [])[1] || "";
  desc = desc.replace(/<[^>]+>/g, "").trim();
  var chapters = [];
  var chRe = /<li[^>]+data-num="([^"]+)"[\s\S]*?<a href="([^"]+)">([\s\S]*?)<\/a>[\s\S]*?<span[^>]+class="[^"]*chapterdate[^"]*"[^>]*>([\s\S]*?)<\/span>/g;
  var cm;
  while ((cm = chRe.exec(html)) !== null) {
    chapters.push({
      url: cm[2].trim(),
      name: cm[3].replace(/<[^>]+>/g, "").trim(),
      date: cm[4].replace(/<[^>]+>/g, "").trim()
    });
  }
  return JSON.stringify({ url: url, title: title, cover: cover, description: desc, chapters: chapters });
}

function getChapterPages(html, url) {
  var pages = [];
  var re = /<img[^>]+class="[^"]*ts-main-image[^"]*"[^>]+src="([^"]+)"/gi;
  var m;
  while ((m = re.exec(html)) !== null) pages.push({ index: pages.length, url: m[1] });
  if (pages.length === 0) {
    var re2 = /<img[^>]+id="image-(\d+)"[^>]+src="([^"]+)"/gi;
    while ((m = re2.exec(html)) !== null) pages.push({ index: parseInt(m[1]), url: m[2] });
  }
  return JSON.stringify({ pages: pages });
}
""".trimIndent()

    private fun manganeloTemplate(baseUrl: String) = """
// Manganelo / Manganato Extension — auto-generated
// CMS: Custom PHP (manganelo family)

function getMangaListUrl(offset, query) {
  if (query) return "$baseUrl/search/story/" + encodeURIComponent(query.toLowerCase().replace(/ /g, "_"));
  var page = Math.floor(offset / 24) + 1;
  return "$baseUrl/manga-list/type-topview/ctg-all/state-all/page-" + page;
}

function getMangaList(html, offset, query) {
  var items = [];
  var re = /<h3 class="story_name"[\s\S]*?<a href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?<img[^>]+src="([^"]+)"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    items.push({ url: m[1].trim(), title: m[2].replace(/<[^>]+>/g, "").trim(), cover: m[3].trim() });
  }
  return JSON.stringify({ items: items });
}

function getMangaDetails(html, url) {
  var title = (html.match(/<h1 class="story-info-right"[^>]*>([\s\S]*?)<\/h1>/) || [])[1] || "";
  title = title.replace(/<[^>]+>/g, "").trim();
  var cover = (html.match(/<span class="info-image">[\s\S]*?<img[^>]+src="([^"]+)"/) || [])[1] || "";
  var desc = (html.match(/<div id="panel-story-info-description"[^>]*>[\s\S]*?<p>([\s\S]*?)<\/p>/) || [])[1] || "";
  desc = desc.replace(/<[^>]+>/g, "").trim();
  var chapters = [];
  var chRe = /<li class="a-h"[\s\S]*?<a href="([^"]+)"[^>]*class="[^"]*chapter-name[^"]*"[^>]*>([\s\S]*?)<\/a>[\s\S]*?<span class="chapter-time"[^>]*title="([^"]+)"/g;
  var cm;
  while ((cm = chRe.exec(html)) !== null) {
    chapters.push({ url: cm[1].trim(), name: cm[2].replace(/<[^>]+>/g, "").trim(), date: cm[3] });
  }
  return JSON.stringify({ url: url, title: title, cover: cover, description: desc, chapters: chapters });
}

function getChapterPages(html, url) {
  var pages = [];
  var re = /<img[^>]+class="[^"]*lazy[^"]*"[^>]+data-src="([^"]+)"/gi;
  var m;
  while ((m = re.exec(html)) !== null) pages.push({ index: pages.length, url: m[1] });
  if (pages.length === 0) {
    var re2 = /<img[^>]+src="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp))"/gi;
    while ((m = re2.exec(html)) !== null) pages.push({ index: pages.length, url: m[1] });
  }
  return JSON.stringify({ pages: pages });
}
""".trimIndent()

    private fun mangadexTemplate(baseUrl: String) = """
// MangaDex Extension — auto-generated
// Uses the MangaDex REST API. getMangaListUrl returns an API endpoint;
// getMangaList parses the JSON response (the runner passes it as "html").

function getMangaListUrl(offset, query) {
  if (query) return "https://api.mangadex.org/manga?title=" + encodeURIComponent(query) + "&limit=20&offset=" + offset;
  return "https://api.mangadex.org/manga?limit=20&offset=" + offset + "&order[followedCount]=desc";
}

function getMangaList(html, offset, query) {
  try {
    var data = JSON.parse(html);
    var items = (data.data || []).map(function(m) {
      var attrs = m.attributes || {};
      var title = attrs.title ? (attrs.title.en || Object.values(attrs.title)[0] || "") : "";
      return { url: "https://mangadex.org/title/" + m.id, title: title, cover: "" };
    });
    return JSON.stringify({ items: items });
  } catch (e) { return JSON.stringify({ items: [] }); }
}

function getMangaDetails(html, url) {
  // TODO: fetch /manga/{id} and /manga/{id}/feed via API
  return JSON.stringify({ url: url, title: "", chapters: [] });
}

function getChapterPages(html, url) {
  // TODO: fetch /at-home/server/{chapterId} via API for page URLs
  return JSON.stringify({ pages: [] });
}
""".trimIndent()

    private fun manhwaReadTemplate(baseUrl: String) = """
// ManhwaRead-style Extension — auto-generated
// CMS: manga-item class structure (e.g. manhwaread.com)

function getMangaListUrl(offset, query) {
  if (query) return "$baseUrl/manhwa/?s=" + encodeURIComponent(query);
  var page = Math.floor(offset / 24) + 1;
  return page <= 1 ? "$baseUrl/manhwa/" : "$baseUrl/manhwa/page/" + page + "/";
}

function getMangaList(html, offset, query) {
  var items = [];
  var re = /<a[^>]+class="[^"]*manga-item__link[^"]*"[^>]+href="([^"]+)"[\s\S]*?<img[^>]+src="([^"]+)"[\s\S]*?class="[^"]*manga-item__title[^"]*"[^>]*>([\s\S]*?)<\/[^>]+>/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    items.push({ url: m[1].trim(), title: m[3].replace(/<[^>]+>/g, "").trim(), cover: m[2].trim() });
  }
  return JSON.stringify({ items: items });
}

function getMangaDetails(html, url) {
  var title = (html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/) || [])[1] || "";
  title = title.replace(/<[^>]+>/g, "").trim();
  var cover = (html.match(/<div[^>]+class="[^"]*manga-cover[^"]*"[^>]*>[\s\S]*?<img[^>]+src="([^"]+)"/) || [])[1] || "";
  var desc = (html.match(/<div[^>]+class="[^"]*manga-description[^"]*"[^>]*>([\s\S]*?)<\/div>/) || [])[1] || "";
  desc = desc.replace(/<[^>]+>/g, "").trim();
  var chapters = [];
  var chRe = /<a[^>]+class="[^"]*chapter-item[^"]*"[^>]+href="([^"]+)"[^>]*>[\s\S]*?<span[^>]+class="[^"]*chapter-item__name[^"]*"[^>]*>([\s\S]*?)<\/span>[\s\S]*?<span[^>]+class="[^"]*chapter-item__date[^"]*"[^>]*>([\s\S]*?)<\/span>/g;
  var cm;
  while ((cm = chRe.exec(html)) !== null) {
    chapters.push({
      url: cm[1].trim(),
      name: cm[2].replace(/<[^>]+>/g, "").trim(),
      date: cm[3].replace(/<[^>]+>/g, "").trim()
    });
  }
  return JSON.stringify({ url: url, title: title, cover: cover, description: desc, chapters: chapters });
}

function getChapterPages(html, url) {
  var pages = [];
  var dataMatch = html.match(/var\s+chapterData\s*=\s*(\{[\s\S]*?\})\s*;/);
  if (dataMatch) {
    try {
      var cd = JSON.parse(dataMatch[1]);
      var decoded = atob(cd.data || "");
      var imgArr = JSON.parse(decoded);
      var base = (cd.base || "").replace(/\/+$/, "");
      imgArr.forEach(function(img, i) {
        pages.push({ index: i, url: base + "/" + (img.src || "").replace(/^\/+/, "") });
      });
    } catch (e) {}
  }
  if (pages.length === 0) {
    var re = /<img[^>]+src="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp|gif))"/gi;
    var m;
    while ((m = re.exec(html)) !== null) pages.push({ index: pages.length, url: m[1] });
  }
  return JSON.stringify({ pages: pages });
}
""".trimIndent()

    private fun genericJsTemplate(baseUrl: String) = """
// Generic Manga Extension — auto-generated skeleton
// Base URL: $baseUrl
// TODO: Update the CSS selectors below to match the site's actual HTML.

function getMangaListUrl(offset, query) {
  if (query) return "$baseUrl/?s=" + encodeURIComponent(query);
  var page = Math.floor(offset / 20) + 1;
  return page <= 1 ? "$baseUrl/" : "$baseUrl/page/" + page + "/";
}

function getMangaList(html, offset, query) {
  var items = [];
  // TODO: replace with real selectors from the site
  var re = /<a[^>]+href="($baseUrl\/[^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?<img[^>]+src="([^"]+)"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    items.push({ url: m[1], title: m[2].replace(/<[^>]+>/g, "").trim(), cover: m[3] });
  }
  return JSON.stringify({ items: items });
}

function getMangaDetails(html, url) {
  var title = (html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/) || [])[1] || "";
  title = title.replace(/<[^>]+>/g, "").trim();
  var cover = (html.match(/<meta property="og:image" content="([^"]+)"/) || [])[1] || "";
  var desc  = (html.match(/<meta name="description" content="([^"]+)"/) || [])[1] || "";
  var chapters = [];
  // TODO: add chapter parsing regex
  return JSON.stringify({ url: url, title: title, cover: cover, description: desc, chapters: chapters });
}

function getChapterPages(html, url) {
  var pages = [];
  // TODO: adjust to match the site's page image structure
  var re = /<img[^>]+src="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp|gif))"/gi;
  var m;
  while ((m = re.exec(html)) !== null) pages.push({ index: pages.length, url: m[1] });
  return JSON.stringify({ pages: pages });
}
""".trimIndent()

    private fun dartTemplate(name: String, baseUrl: String) = """
// $name — Dart extension
// Base URL: $baseUrl

String getMangaList(int offset, String? query) { return '{"items":[]}'; }
String getMangaDetails(String url) { return '{"url":"${'$'}url","title":"","chapters":[]}'; }
String getChapterPages(String url) { return '{"pages":[]}'; }
""".trimIndent()

    // ─── HTTP helper ──────────────────────────────────────────────────────────

    private fun fetchHtml(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
        okHttpClient.newCall(request).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }.getOrNull()
}
