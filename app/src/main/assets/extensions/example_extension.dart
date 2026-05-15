/// Tsuki Dart Extension — example skeleton (evaluated via D4rt)
///
/// Required top-level functions:
///   manifest()    → Map<String, dynamic>
///   getList(Map filter) → List<Map>
///   getManga(String mangaId) → Map
///   getChapters(String mangaId) → List<Map>
///   getPages(String chapterId) → List<String>

Map<String, dynamic> manifest() => {
      "name": "Example Dart Source",
      "author": "Tsuki Team",
      "version": "1.0.0",
      "baseUrl": "https://example.com",
      "language": "en",
      "nsfw": false,
    };

Future<List<Map<String, dynamic>>> getList(Map<String, dynamic> filter) async {
  final query = filter["query"] as String? ?? "";
  final baseUrl = manifest()["baseUrl"] as String;
  final url = Uri.parse("$baseUrl/search").replace(queryParameters: {"q": query});
  // Perform HTTP request and parse response here.
  return [];
}

Future<Map<String, dynamic>> getManga(String mangaId) async {
  final baseUrl = manifest()["baseUrl"] as String;
  final url = "$baseUrl/manga/$mangaId";
  // Perform HTTP request and parse response here.
  return {
    "id": mangaId,
    "title": "Unknown",
    "coverUrl": null,
    "description": null,
    "tags": <String>[],
    "state": null,
    "author": null,
  };
}

Future<List<Map<String, dynamic>>> getChapters(String mangaId) async {
  final baseUrl = manifest()["baseUrl"] as String;
  final url = "$baseUrl/manga/$mangaId/chapters";
  // Perform HTTP request and parse response here.
  return [];
}

Future<List<String>> getPages(String chapterId) async {
  final baseUrl = manifest()["baseUrl"] as String;
  final url = "$baseUrl/chapter/$chapterId";
  // Perform HTTP request and parse response here.
  return [];
}
