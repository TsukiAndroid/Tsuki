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

// ─── URL resolver ────────────────────────────────────────────────────────────

function getMangaListUrl(offset, query) {
    var page = Math.floor((offset || 0) / PAGE_SIZE) + 1;
    if (query && query.trim().length > 0) {
        var encoded = encodeURIComponent(query.trim());
        if (page > 1) return BASE_URL + "/?s=" + encoded + "&post_type=wp-manga&paged=" + page;
        return BASE_URL + "/?s=" + encoded + "&post_type=wp-manga";
    }
    if (page > 1) return BASE_URL + "/manhwa/page/" + page + "/";
    return BASE_URL + "/manhwa/";
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function decodeHtml(str) {
    if (!str) return "";
    return str
        .replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
        .replace(/&quot;/g, '"').replace(/&#039;/g, "'").replace(/&apos;/g, "'")
        .replace(/&#(\d+);/g, function(_, n) { return String.fromCharCode(parseInt(n, 10)); });
}

function stripTags(str) { return str ? str.replace(/<[^>]+>/g, "").trim() : ""; }

function extractFirst(html, re) { var m = html.match(re); return m ? (m[1] || "") : ""; }

// ─── getMangaList ─────────────────────────────────────────────────────────────

function getMangaList(html, offset, query) {
    var items = [];
    var seenUrls = {};

    // Strategy 1: Madara/WP manga theme card elements
    var cardMatches = html.match(/<div[^>]+class="[^"]*(?:c-image-hover|tab-thumb|manga-thumb|comic-item)[^"]*"[\s\S]*?<a[^>]+href="([^"]+)"[^>]*>[\s\S]*?<img[^>]+(?:src|data-src|data-lazy-src)="([^"]+)"[^>]*>/gi) || [];
    cardMatches.forEach(function(block) {
        var urlM = block.match(/href="([^"]+)"/i);
        var imgM = block.match(/(?:data-lazy-src|data-src|src)="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/i);
        var titleM = block.match(/(?:alt|title)="([^"]{3,120})"/i);
        if (!urlM) return;
        var url = urlM[1];
        if (seenUrls[url] || url === BASE_URL || url === BASE_URL + "/") return;
        seenUrls[url] = true;
        var title = titleM ? decodeHtml(titleM[1].trim()) : "";
        var cover = imgM ? imgM[1] : "";
        if (title.length > 1) items.push({ title: title, url: url, cover: cover });
    });

    if (items.length > 0) return JSON.stringify({ items: items });

    // Strategy 2: .manga-item__link anchors
    var linkRe = /href="(\/(?:manhwa|manga|comic)\/[^"]+)"[^>]*class="[^"]*manga-item__link[^"]*"|class="[^"]*manga-item__link[^"]*"[^>]*href="(\/(?:manhwa|manga|comic)\/[^"]+)"/g;
    var m;
    while ((m = linkRe.exec(html)) !== null) {
        var href = m[1] || m[2] || "";
        if (!href || seenUrls[href]) continue;
        seenUrls[href] = true;
        var fullUrl = BASE_URL + href;
        var pos = m.index;
        var around = html.slice(pos, pos + 600);
        var t = extractFirst(around, /class="[^"]*(?:manga-title|post-title)[^"]*"[^>]*>([^<]{2,120})</) ||
                extractFirst(around, /alt="([^"]{2,120})"/) ||
                extractFirst(around, /<h[1-6][^>]*>([^<]{2,120})<\/h[1-6]>/i);
        if (!t || t.length < 2) continue;
        var cover = extractFirst(around, /(?:data-lazy-src|data-src|src)="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/i);
        items.push({ title: decodeHtml(t.trim()), url: fullUrl, cover: cover });
    }

    if (items.length > 0) return JSON.stringify({ items: items });

    // Strategy 3: broad anchor heuristic for any /manhwa/ or /manga/ link with image
    var anchorRe = /<a[^>]+href="((?:https?:\/\/[^"]*manhwaread[^\/]*|)\/(?:manhwa|manga)\/[^"#?]+)"[^>]*>/gi;
    while ((m = anchorRe.exec(html)) !== null) {
        var href2 = m[1];
        if (!href2 || seenUrls[href2]) continue;
        seenUrls[href2] = true;
        var fullUrl2 = href2.startsWith("http") ? href2 : BASE_URL + href2;
        var around2 = html.slice(m.index, m.index + 800);
        var title2 = extractFirst(around2, /(?:title|alt)="([^"]{2,120})"/) ||
                     extractFirst(around2, /<h[1-6][^>]*>([^<]{2,120})<\/h[1-6]>/i);
        if (!title2 || title2.length < 2) continue;
        var cover2 = extractFirst(around2, /(?:data-lazy-src|data-src|src)="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/i);
        items.push({ title: decodeHtml(title2.trim()), url: fullUrl2, cover: cover2 });
    }

    return JSON.stringify({ items: items });
}

// ─── getMangaDetails ──────────────────────────────────────────────────────────

function getMangaDetails(html, url) {
    var title = extractFirst(html, /<h1[^>]*class="[^"]*(?:manga-title|post-title|entry-title)[^"]*"[^>]*>([\s\S]*?)<\/h1>/i) ||
                extractFirst(html, /<h1[^>]*>([\s\S]{2,120}?)<\/h1>/i) ||
                extractFirst(html, /property="og:title"[^>]*content="([^"]+)"/i);
    title = decodeHtml(stripTags(title).trim());

    var cover = extractFirst(html, /class="[^"]*(?:summary_image|manga-poster|detail-img)[^"]*"[\s\S]{0,200}?<img[^>]+(?:data-lazy-src|data-src|src)="([^"]+)"/i) ||
                extractFirst(html, /property="og:image"[^>]*content="([^"]+)"/i);

    var statusRaw = extractFirst(html, /(?:Status)[\s\S]*?<span[^>]*>([^<]+)<\/span>/i) || "ongoing";
    var statusLow = statusRaw.toLowerCase();
    var status = /complet|finished/.test(statusLow) ? "completed" :
                 /hiatus|hold/.test(statusLow) ? "hiatus" :
                 /cancel|drop/.test(statusLow) ? "dropped" : "ongoing";

    var description = extractFirst(html, /<div[^>]+class="[^"]*(?:summary__content|description-summary|manga-description)[^"]*"[^>]*>([\s\S]{10,2000}?)<\/div>/i);
    description = decodeHtml(stripTags(description).trim());

    var genres = [];
    var gRe = /<a[^>]+rel="tag"[^>]*>([^<]+)<\/a>/gi;
    var gm;
    while ((gm = gRe.exec(html)) !== null) {
        var g = decodeHtml(gm[1].trim());
        if (g && g.length > 1 && g.length < 40) genres.push(g);
    }

    // Chapter list — Madara theme: li.wp-manga-chapter
    var chapters = [];
    var chapRe = /<li[^>]+class="[^"]*wp-manga-chapter[^"]*"[^>]*>[\s\S]*?<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
    var cm;
    while ((cm = chapRe.exec(html)) !== null) {
        var chapUrl = cm[1].trim();
        var chapTitle = decodeHtml(stripTags(cm[2]).trim());
        if (chapUrl && chapTitle) chapters.push({ url: chapUrl, title: chapTitle, date: "" });
    }

    if (chapters.length === 0) {
        var chapRe2 = /<a[^>]+href="([^"]+\/chapter[^"]+)"[^>]*>([^<]{2,80})<\/a>/gi;
        while ((cm = chapRe2.exec(html)) !== null) {
            chapters.push({ url: cm[1].trim(), title: decodeHtml(cm[2].trim()), date: "" });
        }
    }

    return JSON.stringify({ title: title, cover: cover, status: status, description: description, genres: genres, chapters: chapters });
}

// ─── getChapterPages ─────────────────────────────────────────────────────────

function getChapterPages(html, url) {
    var pages = [];
    var seenImgs = {};

    // Strategy A: mangomic chapterData base64 encoding
    var dataM = html.match(/var\s+chapterData\s*=\s*(\{[\s\S]{10,8000}?\});/);
    if (dataM) {
        try {
            var cd = JSON.parse(dataM[1]);
            if (cd && cd.base && cd.data) {
                var decoded = JSON.parse(atob(cd.data));
                decoded.forEach(function(item, i) {
                    var src = (item.src || item.url || item.img || "").replace(/\\\/\//g, "/");
                    if (src) pages.push({ index: i, url: cd.base + "/" + src });
                });
            }
        } catch(e) {}
    }
    if (pages.length > 0) return JSON.stringify({ pages: pages });

    // Strategy B: chapter_preloaded_images JSON array
    var preloadM = html.match(/chapter_preloaded_images\s*=\s*(\[[\s\S]{10,20000}?\])/);
    if (preloadM) {
        try {
            var arr = JSON.parse(preloadM[1]);
            arr.forEach(function(item, i) {
                var src = item.src || item.url || item.img || "";
                if (src) pages.push({ index: i, url: src });
            });
        } catch(e) {}
    }
    if (pages.length > 0) return JSON.stringify({ pages: pages });

    // Strategy C: reading-content img tags (Madara/WP theme)
    var containerM = html.match(/<div[^>]+class="[^"]*(?:reading-content|chapter-content|page-break|reading-detail)[^"]*"[^>]*>([\s\S]{50,100000}?)<\/div>/i);
    var searchHtml = containerM ? containerM[1] : html;
    var imgRe = /(?:data-lazy-src|data-src|src)="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp)(?:\?[^"]*)?)"/gi;
    var im;
    while ((im = imgRe.exec(searchHtml)) !== null) {
        var imgUrl = im[1];
        if (!imgUrl || seenImgs[imgUrl]) continue;
        if (/logo|banner|avatar|icon|button/i.test(imgUrl)) continue;
        seenImgs[imgUrl] = true;
        pages.push({ index: pages.length, url: imgUrl });
    }

    return JSON.stringify({ pages: pages });
}