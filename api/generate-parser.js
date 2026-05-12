export default async function handler(req, res) {
  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  const { url } = req.body;
  if (!url) return res.status(400).json({ error: 'URL is required' });

  let parsedUrl;
  try { parsedUrl = new URL(url); } catch { return res.status(400).json({ error: 'Invalid URL' }); }

  const siteName = parsedUrl.hostname.replace('www.', '');

  const prompt = `You are a manga parser expert. Generate a Tsuki manga reader parser template JSON for this site: ${url}

The site domain is: ${siteName}

Based on your knowledge of manga sites and their common CMS families (Madara/WordPress, MangaDex API, ComicK API, MangaThemesia, MangaStream, Guya, MangaSee, MangaFire, Manganelo, ZeroScans, FoolSlide2, Genkan, etc.), generate the most accurate parser template possible.

Return ONLY a valid JSON object, no explanation, no markdown backticks, just raw JSON with this structure:
{
  "name": "Site Name",
  "version": "1.0",
  "author": "Tsuki Parser Generator",
  "source": "${url}",
  "domain": "${parsedUrl.hostname}",
  "type": "html or api",
  "cmsFamily": "detected CMS family name",
  "mangaList": {
    "endpoint": "/path",
    "method": "GET or POST",
    "pagination": "page or ajax or offset",
    "pageParam": "page"
  },
  "mangaDetail": {
    "titleSelector": "css selector",
    "coverSelector": "css selector",
    "descriptionSelector": "css selector",
    "authorSelector": "css selector",
    "statusSelector": "css selector"
  },
  "chapterList": {
    "endpoint": "/path or ajax endpoint",
    "method": "GET or POST",
    "action": "ajax action if applicable or null",
    "dateSelector": "css selector",
    "titleSelector": "css selector",
    "urlSelector": "css selector"
  },
  "pageList": {
    "imageSelector": "css selector for images",
    "type": "html or js_array or api"
  },
  "search": {
    "endpoint": "/search or ajax",
    "param": "s or q or keyword"
  },
  "genres": {
    "endpoint": "/genre/ or /manga-genre/",
    "selector": "css selector for genre links"
  },
  "headers": {
    "Referer": "${parsedUrl.origin}"
  },
  "notes": "brief note about this parser or any quirks"
}`;

  try {
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${process.env.GEMINI_API_KEY}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }]
      })
    });

    const data = await response.json();
    const raw = data.candidates?.[0]?.content?.parts?.[0]?.text?.trim() || '';
    const clean = raw.replace(/```json|```/g, '').trim();
    const parsed = JSON.parse(clean);

    return res.status(200).json({ parser: parsed });
  } catch (err) {
    console.error(err);
    return res.status(500).json({ error: 'Failed to generate parser' });
  }
    }
