export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  if (!process.env.GEMINI_API_KEY) {
    return res.status(500).json({ error: 'Missing GEMINI_API_KEY environment variable' });
  }

  const { url } = req.body;
  if (!url) return res.status(400).json({ error: 'URL is required' });

  let parsedUrl;
  try { parsedUrl = new URL(url); } catch { return res.status(400).json({ error: 'Invalid URL' }); }

  const siteName = parsedUrl.hostname.replace('www.', '');

  const prompt = `You are an expert web scraper and HTML analyst. Your task is to generate a JSON parser file for the Tsuki Android manga reader app. The parser tells the app how to scrape the manga website at: ${url}

The app uses Jsoup (Java HTML parser) — all selectors must be valid CSS selectors. No XPath, no JavaScript.

Analyze the website carefully based on its domain (${siteName}) and your knowledge of manga site CMS families. Then fill in every field of this JSON structure:

Rules:
- All CSS selectors must be valid Jsoup/CSS selectors
- Prefer specific selectors that work across multiple pages (avoid IDs that change per manga)
- Every img cover/page: check for data-src, data-lazy-src, and src — just give the selector for the img element itself
- If a field does not apply to this site, use "" (empty string) — do not remove the key
- The finished JSON must be valid — no trailing commas, no JavaScript comments
- For "type" (NOT "sourceType"): analyze the site's CMS and pick the best match from this list if it fits: MANGADEX_COMPATIBLE, MADARA, MANGATHEMESIA, MANGASTREAM, GENKAN, FOOLSLIDE2, MANGANELO, ZEROSCANS_API, LHTRANSLATION, MANGASEE, GUYA, MANGAFIRE, MANGAPARK, COMIXTO, COMICK_API, BATO, NINEMANGA, MANGAHOST, MANGAREADER, MANGAFOX, TCBSCANS, MANGANATO, READERFRONT, KISSMANGA, CUBARI, MANGAPILL, MANGAHUB, MANGAHERE, MANGALIB, MANGAGO, MANGAFREAK, MANGAOWL, NETTRUYEN, TRUYENQQ, MANGAKATANA, ZEISTMANGA, KEYOAPP, HEANCMS, WPCOMICS, MMRCMS, MADTHEME, MANGABOX, LILIANA, IKEN, SCAN, PIZZAREADER, FMREADER, GATTSU, ANIMEBOOTSTRAP. If the site does not match any of these, use CUSTOM_TEMPLATE instead.

Common selector patterns by CMS for reference:
- WordPress Madara: itemSelector=div.page-item-detail, titleSelector=.post-title a, coverSelector=img.img-responsive, chapterList.selector=li.wp-manga-chapter
- MangaThemesia: itemSelector=div.bs, titleSelector=.bsx a, chapterList.selector=li.eps-item
- MangaStream: itemSelector=div.bsx, titleSelector=div.tt, chapterList.selector=li.chapter-li
- Genkan: itemSelector=.list-item, titleSelector=.list-item__title a, chapterList.selector=.volume-chapters li
- FoolSlide2: itemSelector=.group, titleSelector=.title a, chapterList.selector=.element

WordPress AJAX detection: Some sites (Madara, MangaThemesia) load chapter lists via AJAX POST to admin-ajax.php. If this applies, set chapterList.action to the action name (usually manga_get_chapters) and chapterList.endpoint to /wp-admin/admin-ajax.php. Otherwise leave both as "".

Return ONLY this exact JSON structure filled in, no markdown fences, no explanation:

{
  "name": "${siteName}",
  "version": "1.0",
  "type": "[pick from list above or CUSTOM_TEMPLATE]",

  "mangaList": {
    "method": "GET",
    "endpoint": "[path to manga list]",
    "pageParam": "[page query param]",
    "pagination": "[page or offset]",
    "itemSelector": "[CSS selector for one manga card]",
    "titleSelector": "[CSS selector for title inside card]",
    "coverSelector": "[CSS selector for img inside card]",
    "linkSelector": "[CSS selector for a href inside card]",
    "searchEndpoint": "[path for search]",
    "searchParam": "[search query param]"
  },

  "mangaDetail": {
    "titleSelector": "[CSS selector for manga title on detail page]",
    "coverSelector": "[CSS selector for img cover on detail page]",
    "descriptionSelector": "[CSS selector for synopsis]"
  },

  "chapterList": {
    "selector": "[CSS selector for each chapter row]",
    "titleSelector": "[CSS selector for chapter name inside row]",
    "linkSelector": "[CSS selector for a href inside row]",
    "action": "[WP AJAX action name or empty string]",
    "endpoint": "[WP AJAX endpoint or empty string]"
  },

  "pageList": {
    "imageSelector": "[CSS selector for img page images in reader]"
  },

  "genres": {
    "endpoint": "[path to genres page or empty string]",
    "selector": "[CSS selector for each genre item or empty string]"
  }
}`;

  try {
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${process.env.GEMINI_API_KEY}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }]
      })
    });

    const data = await response.json();

    if (!response.ok) {
      return res.status(500).json({ error: `Gemini error: ${data?.error?.message || response.status}` });
    }

    const raw = data.candidates?.[0]?.content?.parts?.[0]?.text?.trim() || '';
    if (!raw) return res.status(500).json({ error: 'Gemini returned empty response' });

    const clean = raw.replace(/```json|```/g, '').trim();
    const parsed = JSON.parse(clean);

    return res.status(200).json({ parser: parsed });
  } catch (err) {
    return res.status(500).json({ error: err.message || 'Failed to generate parser' });
  }
      }
