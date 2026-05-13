You are an expert web scraper and HTML analyst. Your task is to generate a JSON parser file for the Tsuki Android manga reader app. The parser tells the app how to scrape a manga website — what CSS selectors to use, where to find pages, chapters, covers, etc.

## Target website
[REPLACE WITH THE WEBSITE URL, e.g. https://example.com]

---

## Your job

Visit the website and inspect its HTML structure carefully, then fill in every field of the JSON template below. Rules:

- All CSS selectors must be valid Jsoup/CSS selectors (no XPath, no JavaScript).
- Prefer the most **specific** selector that still works across multiple pages (avoid IDs that change per manga).
- Every `<img>` cover/page: check for `data-src`, `data-lazy-src`, and `src` — the app tries all three in that order, so just give the selector for the `<img>` element itself.
- If a field does not apply to this site, use `""` (empty string) — **do not remove the key**.
- The finished JSON must be valid — no trailing commas, no JavaScript comments.

---

## Auto-detection fields (important — fill ALL three)

When a user adds a site, the app runs a detection pipeline to find the right parser automatically. To make your template match automatically, you must fill in one of the three detection strategies below. The app checks them in priority order:

### Strategy 0 — `domains` (instant, no network, highest priority)
Use this when the template is written for **one specific site** (or a small set of known mirrors). List the exact hostnames (without `www.`) the template covers.
```json
"domains": ["example.com", "mirror.example.com"]
```
- The app strips `www.` before comparing, so `example.com` matches both `example.com` and `www.example.com`.
- If a template declares `domains`, the app ONLY uses domain matching for that template — fingerprints and endpoint probes are NOT checked.

### Strategy 1 — `fingerprints` (fast, one HTTP request shared)
Use this when the template covers a **CMS family** (i.e. any site built on the same platform). List 2–4 HTML substrings that are unique to that CMS and appear in the homepage HTML of every site using it.
```json
"fingerprints": ["unique-css-class", "cms-specific-string"]
```
- All listed strings must match (AND logic).
- The homepage is fetched once and shared across all fingerprint-bearing templates.
- If `fingerprints` is present, `endpointProbes` is NOT checked for that template.

### Strategy 2 — `endpointProbes` (precise, one request per probe)
Use this for **API-driven sites** whose homepage carries no CMS markers. Each probe hits a specific API path and checks the response body for a substring.
```json
"endpointProbes": [
  { "path": "/api/comics",    "contains": "\"slug\""     },
  { "path": "/api/v1/series", "contains": "\"chapters\"" }
]
```
- All probes must pass (AND logic).
- `path` can be a root-relative path (`/api/...`) or a full URL.

**Rule:** Pick the highest-priority strategy that applies. Most sites need only one. A single-site template → use `domains`. A CMS family template → use `fingerprints`. An API-only site with no HTML markers → use `endpointProbes`. For unused strategies, use `[]` (empty array).

---

## Pages to inspect

### Page 1 — Browse / Latest page
Visit the main manga list page (e.g. /manga, /latest, /, /series). Find:
- The URL path of this page → `mangaList.endpoint`
- The query-string key used to go to page 2 (e.g. `?page=2` → `"page"`, `?p=2` → `"p"`) → `mangaList.pageParam`
- Right-click one manga card → Inspect → find the repeating container element that wraps title + cover + link → `mangaList.itemSelector`
- Inside that container, the element whose text is the manga title → `mangaList.titleSelector`
- Inside that container, the `<img>` element for the cover → `mangaList.coverSelector`
- Inside that container, the `<a href>` that links to the manga's detail page → `mangaList.linkSelector`

### Page 2 — Search results
Type anything in the search box and submit. Observe the URL. Find:
- The path of the search results URL (e.g. `/` if it's `/?s=naruto`, or `/search` if it's `/search?q=naruto`) → `mangaList.searchEndpoint`
- The query-string key for the search term (e.g. `s`, `q`, `keyword`) → `mangaList.searchParam`
- Confirm the manga card structure is the same as on the browse page (if so, `itemSelector` / `titleSelector` / `coverSelector` / `linkSelector` stay the same).

### Page 3 — Manga detail page
Click any manga to open its detail page. Find:
- The element containing the **full manga title** → `mangaDetail.titleSelector`
- The `<img>` element for the **cover image** → `mangaDetail.coverSelector`
- The element containing the **plot summary / description** → `mangaDetail.descriptionSelector`
- The element for each **chapter row** in the chapter list → `chapterList.selector`
- Inside each chapter row, the element whose text is the **chapter name** → `chapterList.titleSelector`
- Inside each chapter row, the `<a href>` that opens the **chapter reader** → `chapterList.linkSelector`

#### WordPress AJAX chapter lists (common on Madara/WP sites)
If the chapter list is NOT present in the initial HTML (it loads after the page, via JavaScript) AND the page source contains `admin-ajax.php` or `manga-chapters-holder`:
- Set `chapterList.action` to the action name from the AJAX call (open DevTools → Network tab → look for an XHR POST to `admin-ajax.php`, check the `action` form field — usually `"manga_get_chapters"` or `"wp_manga_chapter_ajax"`).
- Set `chapterList.endpoint` to `"/wp-admin/admin-ajax.php"`.
- Otherwise (chapter list is in the HTML), leave both `action` and `endpoint` as `""`.

### Page 4 — Chapter reader page
Click any chapter to open the reader. Find:
- The `<img>` elements that are the actual **manga page images** → `pageList.imageSelector`
- These images typically have `class="wp-manga-chapter-img"` or `class="chapter-img"` or are inside `div.reading-content`, `div#images`, `div.chapter-images`, etc.

### Page 5 — Genre / Tag page (optional)
Look for a genres or categories page (linked in the navigation, usually `/genre`, `/category`, `/tags`). Find:
- The URL path → `genres.endpoint`
- The element for each genre item (must contain an `<a href>` whose last URL segment is the genre's slug) → `genres.selector`
- If there is no genres page, leave both as `""`.

---

## WordPress AJAX detection (important)
Some sites (Madara, MangaThemesia) load the chapter list via an AJAX POST call. To detect this:
1. Open the manga detail page.
2. Open browser DevTools → Network tab → reload the page.
3. Look for a POST request to `admin-ajax.php`.
4. Click it → Payload tab → note the `action` value.
If found: fill `chapterList.action` and set `chapterList.endpoint` to `"/wp-admin/admin-ajax.php"`.
If not found: leave `chapterList.action` and `chapterList.endpoint` as `""`.

---

## Pagination type guide
Inspect the URL when going from page 1 to page 2 of the manga list:
- `?page=1` → `"pagination": "page"`   ← most common
- `?offset=0` then `?offset=16` → `"pagination": "offset"`
- Chapter list loaded via WordPress AJAX POST → `"pagination": "ajax"` (only for chapter list, not mangaList)

---

## Output — fill in this exact JSON

```json
{
  "name": "[SITE NAME]",
  "version": "1.0",
  "type": "html",

  "domains": ["[hostname without www, e.g. example.com]"],

  "fingerprints": [],

  "endpointProbes": [],

  "mangaList": {
    "method": "GET",
    "endpoint": "[path to manga list, e.g. /manga]",
    "pageParam": "[page query param, e.g. page]",
    "pagination": "[page | offset]",
    "itemSelector": "[CSS selector for one manga card]",
    "titleSelector": "[CSS selector for title inside card]",
    "coverSelector": "[CSS selector for <img> inside card]",
    "linkSelector": "[CSS selector for <a href> inside card]",
    "searchEndpoint": "[path for search, e.g. / or /search]",
    "searchParam": "[search query param, e.g. s or q]"
  },

  "mangaDetail": {
    "titleSelector": "[CSS selector for manga title on detail page]",
    "coverSelector": "[CSS selector for <img> cover on detail page]",
    "descriptionSelector": "[CSS selector for synopsis / description]"
  },

  "chapterList": {
    "selector": "[CSS selector for each chapter row]",
    "titleSelector": "[CSS selector for chapter name inside row]",
    "linkSelector": "[CSS selector for <a href> inside row]",
    "action": "[WP AJAX action name, or empty string]",
    "endpoint": "[WP AJAX endpoint, or empty string]"
  },

  "pageList": {
    "imageSelector": "[CSS selector for <img> page images in reader]"
  },

  "genres": {
    "endpoint": "[path to genres page, or empty string]",
    "selector": "[CSS selector for each genre item, or empty string]"
  }
}
```

---

## Auto-detection field examples

**Single site (use `domains`):**
```json
"domains": ["mangasite.com"],
"fingerprints": [],
"endpointProbes": []
```

**CMS family (use `fingerprints`):**
```json
"domains": [],
"fingerprints": ["wp-manga", "mangomic-core"],
"endpointProbes": []
```

**API-only site (use `endpointProbes`):**
```json
"domains": [],
"fingerprints": [],
"endpointProbes": [
  { "path": "/api/series", "contains": "\"slug\"" }
]
```

---

## Rules for high-quality selectors

1. **Stable, not unique** — use classes that appear on every manga card, not `div:nth-child(3)`.
2. **Scoped** — prefer selectors relative to the item container (e.g. `.post-title a`, not `body .content .grid .item .title a`).
3. **Cover images** — always point to the `<img>` element directly. The app handles `data-src`, `data-lazy-src`, and `src` automatically.
4. **Anchor links** — always include the `<a>` tag in the selector, not its parent, because `absUrl("href")` is called on it.
5. **Chapter rows** — the selector must match the repeating row element that wraps BOTH the chapter title and the chapter link.
6. **Search** — if the browse page IS the search page (URL changes to `/?s=term`), set `searchEndpoint` to `"/"` and `searchParam` to `"s"`.
7. **No JavaScript selectors** — the app uses Jsoup, not a browser. Selectors must work on the raw downloaded HTML, not on JavaScript-rendered content.

---

## Common selector patterns by CMS (for reference)

| CMS | itemSelector | titleSelector | coverSelector | chapterList.selector |
|-----|-------------|--------------|--------------|---------------------|
| WordPress Madara | `div.page-item-detail` | `.post-title a` | `img.img-responsive` | `li.wp-manga-chapter` |
| MangaThemesia | `div.bs` | `.bsx a` or `div.tt` | `img` | `li.eps-item` |
| MangaStream | `div.bsx` | `div.tt` | `img` | `li.chapter-li` |
| Genkan | `.list-item` | `.list-item__title a` | `.cover-image img` | `.volume-chapters li` |
| FoolSlide2 | `.group` | `.title a` | `.cover img` | `.element` |
| Generic blog | `article` | `h2.entry-title a` | `.post-thumbnail img` | `.chapter-list li` |

---

## After filling in the JSON

- Double-check it is valid JSON (paste into https://jsonlint.com).
- Make sure there are no trailing commas.
- Set `"name"` to a clean, recognisable name for the source (e.g. `"Scanlation Site"`, `"MangaRead"`).
- Return only the raw JSON — no markdown fences, no explanation text around it.
