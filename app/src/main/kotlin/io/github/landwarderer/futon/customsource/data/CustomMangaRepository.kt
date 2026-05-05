package io.github.landwarderer.futon.customsource.data

  import io.github.landwarderer.futon.core.parser.MangaRepository
  import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
  import io.github.landwarderer.futon.customsource.domain.CustomSource
  import io.github.landwarderer.futon.customsource.domain.CustomSourceType
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import org.json.JSONArray
  import org.json.JSONObject
  import org.koitharu.kotatsu.parsers.model.ContentRating
  import org.koitharu.kotatsu.parsers.model.Manga
  import org.koitharu.kotatsu.parsers.model.MangaChapter
  import org.koitharu.kotatsu.parsers.model.MangaListFilter
  import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
  import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
  import org.koitharu.kotatsu.parsers.model.MangaPage
  import org.koitharu.kotatsu.parsers.model.MangaState
  import org.koitharu.kotatsu.parsers.model.SortOrder
  import java.util.EnumSet
  import java.util.concurrent.TimeUnit

  /**
   * MangaRepository implementation backed by user-defined [CustomSource]s.
   *
   * All parser types (except [CustomSourceType.WEBVIEW]) support the full
   * manga experience inside the app: browse list, chapter list, and page
   * images. Every type also participates in CMS auto-detection.
   *
   * Designed to fail soft: any HTTP/parsing error returns an empty list so the
   * source still renders in the Sources tab without crashing the app.
   */
  class CustomMangaRepository(
      private val customSource: CustomMangaSource,
  ) : MangaRepository {

      override val source: CustomMangaSource = customSource

      override val sortOrders: Set<SortOrder> = EnumSet.of(
          SortOrder.UPDATED,
          SortOrder.POPULARITY,
          SortOrder.NEWEST,
          SortOrder.RATING,
          SortOrder.RELEVANCE,
      )

      override var defaultSortOrder: SortOrder = SortOrder.UPDATED

      override val filterCapabilities: MangaListFilterCapabilities
          get() = when (customSource.source.type) {
              CustomSourceType.MADARA,
              CustomSourceType.MANGATHEMESIA,
              CustomSourceType.MANGASTREAM -> MangaListFilterCapabilities(
                  isSearchSupported = true,
                  isSearchWithFiltersSupported = true,
                  isMultipleTagsSupported = false,
                  isTagsExclusionSupported = false,
              )
              else -> MangaListFilterCapabilities(
                  isSearchSupported = true,
                  isSearchWithFiltersSupported = false,
                  isMultipleTagsSupported = false,
                  isTagsExclusionSupported = false,
              )
          }

      // ── Parser instances (lazy, one per type) ─────────────────────────────────

      private val madaraParser: MadaraHtmlParser by lazy { MadaraHtmlParser(customSource) }
      private val mangaThemesiaParser: MangaThemesiaHtmlParser by lazy { MangaThemesiaHtmlParser(customSource) }
      private val mangaStreamParser: MangaStreamHtmlParser by lazy { MangaStreamHtmlParser(customSource) }
      private val genkanParser: GenkanHtmlParser by lazy { GenkanHtmlParser(customSource) }
      private val foolSlide2Parser: FoolSlide2HtmlParser by lazy { FoolSlide2HtmlParser(customSource) }
      private val manganeloParser: ManganeloHtmlParser by lazy { ManganeloHtmlParser(customSource) }
      private val zeroscansParser: ZeroscansParser by lazy { ZeroscansParser(customSource) }
      private val lhTranslationParser: LightNovelPubHtmlParser by lazy { LightNovelPubHtmlParser(customSource) }
      private val mangaSeeParser: MangaSeeHtmlParser by lazy { MangaSeeHtmlParser(customSource) }
      private val guyaParser: GuyaApiParser by lazy { GuyaApiParser(customSource) }
      private val mangaFireParser: MangaFireHtmlParser by lazy { MangaFireHtmlParser(customSource) }
      private val mangaParkParser: MangaParkHtmlParser by lazy { MangaParkHtmlParser(customSource) }

      // ── MangaRepository implementation ────────────────────────────────────────

      override suspend fun getList(
          offset: Int,
          order: SortOrder?,
          filter: MangaListFilter?,
      ): List<Manga> {
          return when (customSource.source.type) {
              CustomSourceType.WEBVIEW               -> emptyList()
              CustomSourceType.MANGADEX_COMPATIBLE   -> runCatching { fetchMangaDexList(customSource.source, offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MADARA                -> runCatching { madaraParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGATHEMESIA         -> runCatching { mangaThemesiaParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGASTREAM           -> runCatching { mangaStreamParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.GENKAN                -> runCatching { genkanParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.FOOLSLIDE2            -> runCatching { foolSlide2Parser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGANELO             -> runCatching { manganeloParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.ZEROSCANS_API         -> runCatching { zeroscansParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.LHTRANSLATION         -> runCatching { lhTranslationParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGASEE              -> runCatching { mangaSeeParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.GUYA                  -> runCatching { guyaParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAFIRE             -> runCatching { mangaFireParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAPARK             -> runCatching { mangaParkParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.KOTATSU_PARSER        -> emptyList()
          }
      }

      override suspend fun getDetails(manga: Manga): Manga {
          return when (customSource.source.type) {
              CustomSourceType.MANGADEX_COMPATIBLE   -> manga
              CustomSourceType.MADARA                -> runCatching { madaraParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGATHEMESIA         -> runCatching { mangaThemesiaParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGASTREAM           -> runCatching { mangaStreamParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.GENKAN                -> runCatching { genkanParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.FOOLSLIDE2            -> runCatching { foolSlide2Parser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGANELO             -> runCatching { manganeloParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.ZEROSCANS_API         -> runCatching { zeroscansParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.LHTRANSLATION         -> runCatching { lhTranslationParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGASEE              -> runCatching { mangaSeeParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.GUYA                  -> runCatching { guyaParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAFIRE             -> runCatching { mangaFireParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAPARK             -> runCatching { mangaParkParser.getDetails(manga) }.getOrElse { manga }
              else                                   -> manga
          }
      }

      override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
          return when (customSource.source.type) {
              CustomSourceType.MADARA                -> runCatching { madaraParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGATHEMESIA         -> runCatching { mangaThemesiaParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGASTREAM           -> runCatching { mangaStreamParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.GENKAN                -> runCatching { genkanParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.FOOLSLIDE2            -> runCatching { foolSlide2Parser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGANELO             -> runCatching { manganeloParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.ZEROSCANS_API         -> runCatching { zeroscansParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.LHTRANSLATION         -> runCatching { lhTranslationParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGASEE              -> runCatching { mangaSeeParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.GUYA                  -> runCatching { guyaParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAFIRE             -> runCatching { mangaFireParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAPARK             -> runCatching { mangaParkParser.getPages(chapter) }.getOrElse { emptyList() }
              else                                   -> emptyList()
          }
      }

      override suspend fun getPageUrl(page: MangaPage): String = page.url

      override suspend fun getFilterOptions(): MangaListFilterOptions = when (customSource.source.type) {
          CustomSourceType.MADARA -> MangaListFilterOptions(
              availableTags = runCatching { madaraParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGATHEMESIA -> MangaListFilterOptions(
              availableTags = runCatching { mangaThemesiaParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGASTREAM -> MangaListFilterOptions(
              availableTags = runCatching { mangaStreamParser.getGenres() }.getOrElse { emptySet() },
          )
          else -> MangaListFilterOptions()
      }

      override suspend fun getRelated(seed: Manga): List<Manga> = emptyList()

      // ── MangaDex-compatible REST backend ──────────────────────────────────────

      private fun fetchMangaDexList(
          cs: CustomSource,
          offset: Int,
          order: SortOrder?,
          filter: MangaListFilter?,
      ): List<Manga> {
          val baseUrl = cs.cleanBaseUrl
          val limit = PAGE_SIZE
          val orderParam = when (order) {
              SortOrder.POPULARITY -> "order[followedCount]=desc"
              SortOrder.NEWEST     -> "order[createdAt]=desc"
              SortOrder.RATING     -> "order[rating]=desc"
              SortOrder.RELEVANCE  -> "order[relevance]=desc"
              else                 -> "order[updatedAt]=desc"
          }
          val query = filter?.query?.takeIf { it.isNotBlank() }?.let {
              "&title=${java.net.URLEncoder.encode(it, "UTF-8")}"
          } ?: ""
          val url = "$baseUrl/manga?limit=$limit&offset=$offset&includes[]=cover_art" +
              "&contentRating[]=safe&contentRating[]=suggestive&$orderParam$query"

          val response = httpClient.newCall(
              Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
          ).execute()

          response.use { resp ->
              if (!resp.isSuccessful) return emptyList()
              val body = resp.body?.string() ?: return emptyList()
              val root = JSONObject(body)
              if (root.optString("result") != "ok") return emptyList()
              val data = root.optJSONArray("data") ?: return emptyList()
              return parseMangaList(data, baseUrl)
          }
      }

      private fun parseMangaList(data: JSONArray, baseUrl: String): List<Manga> {
          val out = ArrayList<Manga>(data.length())
          for (i in 0 until data.length()) {
              val node = data.optJSONObject(i) ?: continue
              val id = node.optString("id").takeIf { it.isNotEmpty() } ?: continue
              val attrs = node.optJSONObject("attributes") ?: continue
              val titleObj = attrs.optJSONObject("title")
              val title = pickLocalisedString(titleObj) ?: id
              val descObj = attrs.optJSONObject("description")
              val description = pickLocalisedString(descObj)
              val state = when (attrs.optString("status")) {
                  "completed"  -> MangaState.FINISHED
                  "hiatus"     -> MangaState.PAUSED
                  "cancelled"  -> MangaState.ABANDONED
                  else         -> MangaState.ONGOING
              }
              val rating = when (attrs.optString("contentRating")) {
                  "safe"       -> ContentRating.SAFE
                  "suggestive" -> ContentRating.SUGGESTIVE
                  else         -> ContentRating.ADULT
              }
              val rels = node.optJSONArray("relationships")
              var coverFile: String? = null
              if (rels != null) {
                  for (j in 0 until rels.length()) {
                      val rel = rels.optJSONObject(j) ?: continue
                      if (rel.optString("type") == "cover_art") {
                          coverFile = rel.optJSONObject("attributes")?.optString("fileName")
                          if (!coverFile.isNullOrEmpty()) break
                      }
                  }
              }
              val coverUrl = coverFile?.let { "https://uploads.mangadex.org/covers/$id/$it.256.jpg" } ?: ""
              out += Manga(
                  id = id.hashCode().toLong(),
                  title = title,
                  altTitles = emptySet(),
                  url = "/manga/$id",
                  publicUrl = "$baseUrl/title/$id",
                  rating = 0f,
                  contentRating = rating,
                  coverUrl = coverUrl,
                  tags = emptySet(),
                  state = state,
                  authors = emptySet(),
                  largeCoverUrl = coverUrl,
                  description = description,
                  chapters = null,
                  source = customSource,
              )
          }
          return out
      }

      private fun pickLocalisedString(obj: JSONObject?): String? {
          if (obj == null) return null
          val keys = obj.keys()
          var fallback: String? = null
          while (keys.hasNext()) {
              val k = keys.next()
              val v = obj.optString(k)
              if (v.isNullOrEmpty()) continue
              if (k == "en") return v
              if (fallback == null) fallback = v
          }
          return fallback
      }

      companion object {
          private const val PAGE_SIZE = 30
          private const val USER_AGENT = "Tsuki/1.0 (Android)"

          private val httpClient: OkHttpClient by lazy {
              OkHttpClient.Builder()
                  .connectTimeout(15, TimeUnit.SECONDS)
                  .readTimeout(20, TimeUnit.SECONDS)
                  .followRedirects(true)
                  .build()
          }
      }
  }
  