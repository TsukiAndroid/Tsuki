/**
 * Tsuki JS Extension — example skeleton
 *
 * ## Contract (Tsuki JS Extension v2 — HTML-first)
 *
 * The Kotlin runner handles all HTTP. Your JS only parses pre-fetched HTML.
 *
 * Required exports:
 *
 *   getMangaListUrl(offset, query)
 *     → string  — URL the runner will GET for the browse/search page.
 *       offset is an integer (0, 20, 40, …), query is a string or null.
 *
 *   getMangaList(html, offset, query)
 *     → '{"items":[{"title":"…","url":"https://…","cover":"https://…"}]}'
 *       Parses the fetched HTML and returns a JSON string.
 *
 *   getMangaDetails(html, url)
 *     → '{"title":"…","cover":"…","status":"ongoing","description":"…",
 *          "genres":["Action","Drama"],
 *          "chapters":[{"url":"…","title":"Ch.1","number":1,"uploadDate":0}]}'
 *
 *   getChapterPages(html, url)
 *     → '{"pages":[{"index":0,"url":"https://cdn.example.com/001.jpg"}]}'
 */

var BASE_URL = "https://example.com";
var PAGE_SIZE = 20;

// ─── URL resolver ─────────────────────────────────────────────────────────────

function getMangaListUrl(offset, query) {
    var page = Math.floor((offset || 0) / PAGE_SIZE) + 1;
    if (query && query.trim().length > 0) {
        return BASE_URL + "/search?q=" + encodeURIComponent(query.trim()) + "&page=" + page;
    }
    return BASE_URL + "/manga?page=" + page;
}

// ─── List page parser ─────────────────────────────────────────────────────────

function getMangaList(html, offset, query) {
    // TODO: parse manga cards from html
    // Each item: { title: string, url: string (absolute), cover: string }
    var items = [];
    return JSON.stringify({ items: items });
}

// ─── Detail page parser ───────────────────────────────────────────────────────

function getMangaDetails(html, url) {
    // TODO: parse title, cover, status, description, genres, chapters
    var chapters = [
        // { url: "https://…/chapter-1/", title: "Chapter 1", number: 1, uploadDate: 0 }
    ];
    return JSON.stringify({
        title: "",
        cover: "",
        status: "ongoing",
        description: "",
        genres: [],
        chapters: chapters
    });
}

// ─── Chapter page parser ──────────────────────────────────────────────────────

function getChapterPages(html, url) {
    // TODO: extract image URLs from html
    var pages = [
        // { index: 0, url: "https://cdn.example.com/001.jpg" }
    ];
    return JSON.stringify({ pages: pages });
}

// ─── Helper utilities (copy as needed) ───────────────────────────────────────

function decodeHtml(str) {
    if (!str) return "";
    return str
        .replace(/&amp;/g, "&")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&quot;/g, '"')
        .replace(/&#039;/g, "'");
}

function stripTags(str) {
    if (!str) return "";
    return str.replace(/<[^>]+>/g, "").trim();
}

function extractFirst(html, pattern) {
    var m = html.match(pattern);
    return m ? (m[1] || "") : "";
}
