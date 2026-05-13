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

  const prompt = `You are a manga parser expert for the Tsuki Android app. Analyze this manga site and generate a parser template JSON: ${url}

Domain: ${siteName}

You MUST return a JSON object with these REQUIRED fields:
- "name": the site's display name (REQUIRED)
- "version": always "1.0" (REQUIRED)
- "author": "Tsuki Parser Generator" (REQUIRED)
- "domain": "${parsedUrl.hostname}" (REQUIRED)
- "type" (NOT "sourceType", the key must be exactly "type"): pick EXACTLY one from this list based on the site's CMS:
  MANGADEX_COMPATIBLE, MADARA, MANGATHEMESIA, MANGASTREAM, GENKAN, FOOLSLIDE2,
  MANGANELO, ZEROSCANS_API, LHTRANSLATION, MANGASEE, GUYA, MANGAFIRE, MANGAPARK,
  COMIXTO, COMICK_API, BATO, NINEMANGA, MANGAHOST, MANGAREADER, MANGAFOX,
  TCBSCANS, MANGANATO, READERFRONT, KISSMANGA, CUBARI, MANGAPILL, MANGAHUB,
  MANGAHERE, MANGALIB, MANGAGO, MANGAFREAK, MANGAOWL, NETTRUYEN, TRUYENQQ,
  MANGAKATANA, ZEISTMANGA, KEYOAPP, HEANCMS, WPCOMICS, MMRCMS, MADTHEME,
  MANGABOX, LILIANA, IKEN, SCAN, PIZZAREADER, FMREADER, GATTSU, ANIMEBOOTSTRAP,
  WEBVIEW, KOTATSU_PARSER, CUSTOM_TEMPLATE
- "mangaList": { "endpoint": "/path", "method": "GET or POST", "pagination": "page or ajax or offset", "pageParam": "page" }
- "mangaDetail": { "titleSelector": "css selector", "coverSelector": "css selector", "descriptionSelector": "css selector", "authorSelector": "css selector", "statusSelector": "css selector" }
- "chapterList": { "endpoint": "/path or ajax endpoint", "method": "GET or POST", "action": "ajax action or null", "dateSelector": "css selector", "titleSelector": "css selector", "urlSelector": "css selector" }
- "pageList": { "imageSelector": "css selector", "type": "html or js_array or api" }
- "search": { "endpoint": "/search or ajax", "param": "s or q or keyword" }
- "genres": { "endpoint": "/genre/", "selector": "css selector" }
- "headers": { "Referer": "${parsedUrl.origin}" }
- "notes": "any quirks about this site"

Return ONLY raw JSON, no markdown, no backticks, no explanation.`;

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
