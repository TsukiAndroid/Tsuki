/**
 * Tsuki JS Extension — example skeleton
 *
 * Required exports:
 *   - manifest  : object  — extension metadata
 *   - getList   : async (filter) => Manga[]
 *   - getManga  : async (mangaId) => Manga
 *   - getChapters: async (mangaId) => Chapter[]
 *   - getPages  : async (chapterId) => string[]
 */

const manifest = {
    name: "Example Source",
    author: "Tsuki Team",
    version: "1.0.0",
    baseUrl: "https://example.com",
    language: "en",
    nsfw: false,
};

async function getList(filter) {
    const query = filter.query ?? "";
    const url = `${manifest.baseUrl}/search?q=${encodeURIComponent(query)}`;
    const html = await fetch(url).then(r => r.text());
    return parseMangaList(html);
}

async function getManga(mangaId) {
    const url = `${manifest.baseUrl}/manga/${mangaId}`;
    const html = await fetch(url).then(r => r.text());
    return parseMangaDetails(html, mangaId);
}

async function getChapters(mangaId) {
    const url = `${manifest.baseUrl}/manga/${mangaId}/chapters`;
    const html = await fetch(url).then(r => r.text());
    return parseChapterList(html, mangaId);
}

async function getPages(chapterId) {
    const url = `${manifest.baseUrl}/chapter/${chapterId}`;
    const html = await fetch(url).then(r => r.text());
    return parsePageUrls(html);
}

function parseMangaList(html) {
    return [];
}

function parseMangaDetails(html, id) {
    return { id, title: "Unknown", coverUrl: null, description: null, tags: [], state: null, author: null };
}

function parseChapterList(html, mangaId) {
    return [];
}

function parsePageUrls(html) {
    return [];
}
