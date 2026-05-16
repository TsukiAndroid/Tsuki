/**
 * ManhwaRead.com - Tsuki JS Extension
 *
 * Site: https://manhwaread.com
 * Theme: WordPress + custom mangomic theme
 *
 * Contract (Tsuki JS extension v2 - HTML-first):
 *   getMangaListUrl(offset, query)  -> URL string
 *   getMangaList(html, offset, query) -> JSON string {items:[{title,url,cover}]}
 *   getMangaDetails(html, url)  -> JSON string
 *   getChapterPages(html, url)  -> JSON string {pages:[{index,url}]}
 *
 * Chapter images: encoded as base64 JSON in a script variable called chapterData.
 * Image CDN base: chapterData.base  e.g. https://manread.xyz/12369
 * Decode: JSON.parse(atob(chapterData.data)) -> [{src, w, h}, ...]
 * Full URL: base + '/' + src (un-escape \/ to /)
 */

var BASE_URL = 'https://manhwaread.com';
var PAGE_SIZE = 20;

// --- URL resolver ----------------------------------------------------------

function getMangaListUrl(offset, query) {
    var page = Math.floor((offset || 0) / PAGE_SIZE) + 1;
    if (query && query.trim().length > 0) {
        var encoded = encodeURIComponent(query.trim());
        if (page > 1) return BASE_URL + '/?s=' + encoded + '&post_type=wp-manga&paged=' + page;
        return BASE_URL + '/?s=' + encoded + '&post_type=wp-manga';
    }
    if (page > 1) return BASE_URL + '/manhwa/page/' + page + '/';
    return BASE_URL + '/manhwa/';
}

// --- Helpers ----------------------------------------------------------------

function decodeHtml(str) {
    if (!str) return '';
    return str
        .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"').replace(/&#039;/g, "'").replace(/&apos;/g, "'")
        .replace(/&#(\d+);/g, function(_, n) { return String.fromCharCode(parseInt(n, 10)); });
}

function stripTags(str) { return str ? str.replace(/<[^>]+>/g, '').trim() : ''; }

function extractFirst(html, re) { var m = html.match(re); return m ? (m[1] || '') : ''; }

// --- getMangaList -----------------------------------------------------------

function getMangaList(html, offset, query) {
    var items = [];
    var seenUrls = {};

    // Strategy 1: Madara/WP manga card elements - find anchor + image pairs
    var blockRe = /<a[^>]+href='([^']+)'[^>]*>[\s\S]*?<img[^>]+(?:src|data-src|data-lazy-src)='([^']+)'|<a[^>]+href="([^"]+)"[^>]*>[\s\S]*?<img[^>]+(?:src|data-src|data-lazy-src)="([^"]+)"/gi;
    var m;
    while ((m = blockRe.exec(html)) !== null) {
        var url = m[1] || m[3] || '';
        var img = m[2] || m[4] || '';
        if (!url || seenUrls[url]) continue;
        if (url === BASE_URL || url === BASE_URL + '/') continue;
        if (url.indexOf('/manhwa/') < 0 && url.indexOf('/manga/') < 0 && url.indexOf('/comic/') < 0) continue;
        seenUrls[url] = true;
        // find title nearby: look in the 200 chars after the anchor href
        var pos = m.index;
        var around = html.slice(pos, pos + 400);
        var title = extractFirst(around, /(?:alt|title)=['"]([^'"]{3,120})['"]/) ||
                    extractFirst(around, /<h[1-6][^>]*>([^<]{2,80})<\/h[1-6]>/i) ||
                    extractFirst(around, /class=['"][^'"]*(?:title|name)[^'"]*['"][^>]*>([^<]{2,80})<\/[a-z]+>/i);
        if (title && title.length > 1) items.push({ title: decodeHtml(title.trim()), url: url, cover: img });
    }
    if (items.length > 0) return JSON.stringify({ items: items });

    // Strategy 2: any /manhwa/ or /manga/ links with img siblings
    var linkRe = new RegExp('<a[^>]+href=[\x22\']((?:https?://[^\x22\']*manhwaread[^\x22\']*)?/(?:manhwa|manga|comic)/[^\x22\'#? ]+)[\x22\'][^>]*>', 'gi');
    while ((m = linkRe.exec(html)) !== null) {
        var href = m[1];
        if (!href || seenUrls[href]) continue;
        seenUrls[href] = true;
        var fullUrl = href.indexOf('http') === 0 ? href : BASE_URL + href;
        var around2 = html.slice(m.index, m.index + 600);
        var title2 = extractFirst(around2, /(?:alt|title)=['"]([^'"]{2,120})['"]/) ||
                     extractFirst(around2, /<h[1-6][^>]*>([^<]{2,120})<\/h[1-6]>/i);
        if (!title2 || title2.length < 2) continue;
        var cover2 = extractFirst(around2, /(?:data-lazy-src|data-src|src)=['"]([^'"]+\.(?:jpg|jpeg|png|webp)[^'"]*)['"]/i);
        items.push({ title: decodeHtml(title2.trim()), url: fullUrl, cover: cover2 || '' });
    }
    return JSON.stringify({ items: items });
}

// --- getMangaDetails --------------------------------------------------------

function getMangaDetails(html, url) {
    var title = extractFirst(html, /<h1[^>]*class=['"][^'"]*(?:manga-title|post-title|entry-title)[^'"]*['"][^>]*>([\s\S]*?)<\/h1>/i) ||
                extractFirst(html, /<h1[^>]*>([\s\S]{2,120}?)<\/h1>/i) ||
                extractFirst(html, /property=['"]og:title['"][^>]*content=['"]([^'"]+)['"]/i);
    title = decodeHtml(stripTags(title).trim());

    var cover = extractFirst(html, /class=['"][^'"]*(?:summary_image|manga-poster|detail-img)[^'"]*['"][\s\S]{0,200}?<img[^>]+(?:data-lazy-src|data-src|src)=['"]([^'"]+)['"]/i) ||
                extractFirst(html, /property=['"]og:image['"][^>]*content=['"]([^'"]+)['"]/i);

    var statusRaw = extractFirst(html, /Status[\s\S]*?<span[^>]*>([^<]+)<\/span>/i) || 'ongoing';
    var statusLow = statusRaw.toLowerCase();
    var status = /complet|finished/.test(statusLow) ? 'completed' :
                 /hiatus|hold/.test(statusLow) ? 'hiatus' :
                 /cancel|drop/.test(statusLow) ? 'dropped' : 'ongoing';

    var description = extractFirst(html, /<div[^>]*class=['"][^'"]*(?:summary__content|description-summary|manga-description)[^'"]*['"][^>]*>([\s\S]{10,2000}?)<\/div>/i);
    description = decodeHtml(stripTags(description).trim());

    var genres = [];
    var gRe = /<a[^>]+rel=['"]tag['"][^>]*>([^<]+)<\/a>/gi;
    var gm;
    while ((gm = gRe.exec(html)) !== null) {
        var g = decodeHtml(gm[1].trim());
        if (g && g.length > 1 && g.length < 40) genres.push(g);
    }

    // Chapter list: Madara theme li.wp-manga-chapter
    var chapters = [];
    var chapRe = /<li[^>]*class=['"][^'"]*wp-manga-chapter[^'"]*['"][^>]*>[\s\S]*?<a[^>]+href=['"]([^'"]+)['"][^>]*>([\s\S]*?)<\/a>/gi;
    var cm;
    while ((cm = chapRe.exec(html)) !== null) {
        var chapUrl = cm[1].trim();
        var chapTitle = decodeHtml(stripTags(cm[2]).trim());
        if (chapUrl && chapTitle) chapters.push({ url: chapUrl, title: chapTitle, date: '' });
    }
    // Fallback: any /chapter/ links
    if (chapters.length === 0) {
        var chapRe2 = /<a[^>]+href=['"]([^'"]+\/chapter[^'"]+)['"][^>]*>([^<]{2,80})<\/a>/gi;
        while ((cm = chapRe2.exec(html)) !== null) {
            chapters.push({ url: cm[1].trim(), title: decodeHtml(cm[2].trim()), date: '' });
        }
    }

    return JSON.stringify({ title: title, cover: cover || '', status: status, description: description, genres: genres, chapters: chapters });
}

// --- getChapterPages --------------------------------------------------------

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
                for (var i = 0; i < decoded.length; i++) {
                    var item = decoded[i];
                    var src = (item.src || item.url || item.img || '').replace(/\\\/\//g, '/');
                    if (src) pages.push({ index: pages.length, url: cd.base + '/' + src });
                }
            }
        } catch(e) {}
    }
    if (pages.length > 0) return JSON.stringify({ pages: pages });

    // Strategy B: chapter_preloaded_images JSON array
    var preloadM = html.match(/chapter_preloaded_images\s*=\s*(\[[\s\S]{10,20000}?\])/);
    if (preloadM) {
        try {
            var arr = JSON.parse(preloadM[1]);
            for (var j = 0; j < arr.length; j++) {
                var pItem = arr[j];
                var pSrc = pItem.src || pItem.url || pItem.img || '';
                if (pSrc) pages.push({ index: j, url: pSrc });
            }
        } catch(e) {}
    }
    if (pages.length > 0) return JSON.stringify({ pages: pages });

    // Strategy C: img tags in reading container
    var containerM = html.match(/<div[^>]*class=['"][^'"]*(?:reading-content|chapter-content|page-break|reading-detail)[^'"]*['"][^>]*>([\s\S]{50,100000}?)<\/div>/i);
    var searchHtml = containerM ? containerM[1] : html;
    var imgRe = /(?:data-lazy-src|data-src|src)=['"]([^'"]+\.(?:jpg|jpeg|png|webp)(?:\?[^'"]*)?)['"](?:[^>]|>)/gi;
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