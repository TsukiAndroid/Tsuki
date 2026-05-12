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

  const prompt = `You are a manga parser expert. Generate a Tsuki manga reader parser template JSON for this site: ${url}\n\nThe site domain is: ${siteName}\n\nBased on your knowledge of manga sites and their common CMS families (Madara/WordPress, MangaDex API, ComicK API, MangaThemesia, MangaStream, Guya, MangaSee, MangaFire, Manganelo, ZeroScans, FoolSlide2, Genkan, etc.), generate the most accurate parser template possible.\n\nReturn ONLY a valid JSON object, no explanation, no markdown backticks, just raw JSON.`;

  try {
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${process.env.GEMINI_API_KEY}`, {
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
