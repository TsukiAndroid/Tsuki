/**
 * ManhwaRead.com — Tsuki JS Extension
 *
 * Site: https://manhwaread.com
 * Theme: WordPress + custom "mangomic" theme
 *
 * Contract (Tsuki JS extension v2 — HTML-first):
 *   getMangaListUrl(offset, query)  → URL string
 *   getMangaList(html, offset, query) → '{"items":[{"title","url","cover"}]}'
 *   getMangaDetails(html, url)      → '{"title","cover","status","description","genres","chapters":[…]}'
 *   getChapterPages(html, url)      → '{"pages":[{"index","url"}]}'
 *
 * Chapter images: encoded as base64 JSON in a <script> variable `chapterData`.
 * Image CDN base: chapterData.base  (e.g. "https://manread.xyz/12369")
 * Decode:  JSON.parse(atob(chapterData.data))  → [{src, w, h}, …]
 * Full URL: base + "/" + src  (un-escape "\/" → "/")
 */

var BASE_URL = "https://manhwaread.com";
var PAGE_SIZE = 20;

// ─── URL resolver (called by Kotlin runner to know what to fetch) ─────────────

function getMangaListUrl(offset, query) {
    var page = Math.floor((offset || 0) / PAGE_SIZE) + 1;
    if (query && query.trim().length > 0) {
        var encoded = encodeURIComponent(query.trim());
        return page > 1
            ? BASE_URL + "/manhwa/?s=" + encoded + "&paged=" + page
            : BASE_URL + "/manhwa/?s=" + encoded;
    }
    return page > 1
        ? BASE_URL + "/manhwa/page/" + page + "/"
        : BASE_URL + "/manhwa/";
}

// ─── Parsing helpers ──────────────────────────────────────────────────────────

function decodeHtml(str) {
    if (!str) return "";
    return str
        .replace(/&amp;/g, "&")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&quot;/g, '"')
        .replace(/&#039;/g, "'")
        .replace(/&apos;/g, "'")
        .replace(/&#(\d+);/g, function(_, n) { return String.fromCharCode(parseInt(n, 10)); });
}

function stripTags(str) {
    if (!str) return "";
    return str.replace(/<[^>]+>/g, "").trim();
}

function extractFirst(html, re) {
    var m = html.match(re);
    return m ? (m[1] || "") : "";
}

function extractAll(html, re) {
    var results = [];
    var m;
    var g = new RegExp(re.source, "g" + (re.flags || "").replace("g", ""));
    while ((m = g.exec(html)) !== null) {
        results.push(m[1] || "");
    }
    return results;
}

// ─── getMangaList ─────────────────────────────────────────────────────────────

function getMangaList(html, offset, query) {
    var items = [];

    // Each manga card: look for anchor tags with class manga-item__link
    // Pattern: href="URL" class="manga-item__link" or vice-versa
    var linkRe = /href="(\/manhwa\/[^"]+)"\s[^>]*class="[^"]*manga-item__link[^"]*"|class="[^"]*manga-item__link[^"]*"\s[^>]*href="(\/manhwa\/[^"]+)"/g;
    var seenUrls = {};
    var m;

    while ((m = linkRe.exec(html)) !== null) {
        var href = m[1] || m[2] || "";
        if (!href || seenUrls[href]) continue;
        seenUrls[href] = true;

        var url = BASE_URL + href;

        // Extract title from the anchor text (look ahead a bit in HTML)
        var anchorStart = m.index;
        var anchorEnd = html.indexOf("</a>", anchorStart);
        if (anchorEnd === -1) anchorEnd = anchorStart + 500;
        var anchorHtml = html.substring(anchorStart, anchorEnd + 4);

        var title = decodeHtml(stripTags(anchorHtml
            .replace(/<img[^>]+>/g, "")
            .replace(/<[^>]+>/g, " ")
        ).trim());

        // Cover image: search backwards from this anchor for the nearest manga-item block
        var blockStart = html.lastIndexOf('class="manga-item', anchorStart);
        if (blockStart === -1) blockStart = Math.max(0, anchorStart - 2000);
        var blockEnd = Math.min(html.length, anchorEnd + 200);
        var block = html.substring(blockStart, blockEnd);

        var imgMatch = block.match(/class="[^"]*manga-item__img-inner[^"]*"[^>]*src="([^"]+)"/);
        if (!imgMatch) {
            imgMatch = block.match(/src="([^"]*mancover\.xyz[^"]+)"/);
        }
        var cover = imgMatch ? imgMatch[1] : "";

        if (title.length > 0) {
            items.push({ title: title, url: url, cover: cover });
        }
    }

    return JSON.stringify({ items: items });
}

// ─── getMangaDetails ──────────────────────────────────────────────────────────

function getMangaDetails(html, url) {
    // Title: <h1 class="… text-primary …">Title</h1>
    var title = decodeHtml(stripTags(
        extractFirst(html, /<h1[^>]*class="[^"]*text-primary[^"]*"[^>]*>([\s\S]*?)<\/h1>/)
    ));

    // Cover: first mancover.xyz image inside the summary section
    var summaryIdx = html.indexOf('id="mangaSummary"');
    var summaryEnd = html.indexOf('class="manga-titles"', summaryIdx);
    var summaryBlock = summaryIdx !== -1
        ? html.substring(summaryIdx, summaryEnd !== -1 ? summaryEnd + 500 : summaryIdx + 3000)
        : html;
    var cover = extractFirst(summaryBlock, /src="(https:\/\/mancover\.xyz\/[^"]+)"/);

    // Status: data-status="ongoing|completed|…"
    var status = extractFirst(html, /data-status="([^"]+)"/).toLowerCase();

    // Description: meta description tag
    var description = decodeHtml(
        extractFirst(html, /<meta[^>]+name="description"[^>]+content="([^"]+)"/) ||
        extractFirst(html, /<meta[^>]+content="([^"]+)"[^>]+name="description"/)
    );

    // Genres
    var genres = [];
    var genreRe = /href="\/genre\/[^"]+"[^>]*>([^<]+)</g;
    var gm;
    while ((gm = genreRe.exec(html)) !== null) {
        var g = gm[1].trim();
        if (g && genres.indexOf(g) === -1) genres.push(g);
    }

    // Chapters: <a href="/manhwa/slug/chapter-N/" class="chapter-item" …>
    //   <span class="… chapter-item__name …">Chapter 01</span>
    //   <span class="… chapter-item__date …">21/02/2026</span>
    var chapters = [];
    var chRe = /<a[^>]+href="(\/manhwa\/[^"]+)"[^>]*class="[^"]*chapter-item[^"]*"[\s\S]*?<span[^>]*class="[^"]*chapter-item__name[^"]*"[^>]*>([\s\S]*?)<\/span>[\s\S]*?<span[^>]*class="[^"]*chapter-item__date[^"]*"[^>]*>([\s\S]*?)<\/span>/g;
    var cm;
    while ((cm = chRe.exec(html)) !== null) {
        var chUrl = BASE_URL + cm[1];
        var chName = stripTags(cm[2]).trim();
        var chDate = stripTags(cm[3]).trim();
        var numMatch = chName.match(/(\d+(?:\.\d+)?)/);
        var number = numMatch ? parseFloat(numMatch[1]) : 0;
        var uploadDate = parseDateDDMMYYYY(chDate);
        chapters.push({ url: chUrl, title: chName, number: number, uploadDate: uploadDate });
    }

    return JSON.stringify({
        title: title,
        cover: cover,
        status: status,
        description: description,
        genres: genres,
        chapters: chapters
    });
}

function parseDateDDMMYYYY(str) {
    if (!str) return 0;
    var parts = str.split("/");
    if (parts.length !== 3) return 0;
    var d = parseInt(parts[0], 10);
    var mo = parseInt(parts[1], 10) - 1;
    var y = parseInt(parts[2], 10);
    if (isNaN(d) || isNaN(mo) || isNaN(y)) return 0;
    return new Date(y, mo, d).getTime();
}

// ─── getChapterPages ──────────────────────────────────────────────────────────

function getChapterPages(html, url) {
    // The chapter reader embeds a JS variable:
    //   var chapterData = {"data":"<base64>","base":"https://manread.xyz/12369"}
    //
    // Decoded data: [{src:"126384/mr_001.jpg", w:800, h:5000}, …]
    // Full image URL: base + "/" + src  (unescape "\/" → "/")

    var dataMatch = html.match(/var chapterData\s*=\s*(\{[^;]+\})/);
    if (!dataMatch) {
        return JSON.stringify({ pages: [], error: "chapterData variable not found" });
    }

    var chapterData;
    try {
        chapterData = JSON.parse(dataMatch[1]);
    } catch (e) {
        return JSON.stringify({ pages: [], error: "JSON.parse(chapterData) failed: " + e.message });
    }

    var base = chapterData.base || "";
    var pageList;
    try {
        pageList = JSON.parse(atob(chapterData.data));
    } catch (e) {
        return JSON.stringify({ pages: [], error: "atob/parse failed: " + e.message });
    }

    var pages = pageList.map(function(p, i) {
        var src = (p.src || "").replace(/\\\//g, "/");
        return { index: i, url: base + "/" + src };
    });

    return JSON.stringify({ pages: pages });
}
