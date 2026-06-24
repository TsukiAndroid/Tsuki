package io.github.landwarderer.futon.customsource.data

  import io.github.landwarderer.futon.core.parser.MangaRepository
  import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
  import io.github.landwarderer.futon.customsource.domain.CustomSource
  import io.github.landwarderer.futon.customsource.domain.CustomSourceType
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import org.json.JSONArray
  import io.github.landwarderer.futon.browsersource.data.BrowserSourcePageStore
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
              CustomSourceType.MANGASTREAM,
              CustomSourceType.COMIXTO,
              CustomSourceType.BATO,
              CustomSourceType.MANGAHOST,
              CustomSourceType.MANGAREADER,
              CustomSourceType.MANGAFOX,
              CustomSourceType.MANGANATO,
              CustomSourceType.KISSMANGA,
              CustomSourceType.MANGAPILL,
              CustomSourceType.MANGAHUB,
              CustomSourceType.MANGAHERE,
              CustomSourceType.MANGAGO,
              CustomSourceType.MANGAFREAK,
              CustomSourceType.MANGAOWL,
              CustomSourceType.NETTRUYEN,
              CustomSourceType.TRUYENQQ,
              CustomSourceType.MANGAKATANA,
              CustomSourceType.ZEISTMANGA,
              CustomSourceType.KEYOAPP,
              CustomSourceType.HEANCMS,
              CustomSourceType.WPCOMICS,
              CustomSourceType.MMRCMS,
              CustomSourceType.MADTHEME,
              CustomSourceType.MANGABOX,
              CustomSourceType.IKEN,
              CustomSourceType.SCAN,
              CustomSourceType.FMREADER,
              CustomSourceType.GATTSU,
              CustomSourceType.ANIMEBOOTSTRAP -> MangaListFilterCapabilities(
                  isSearchSupported = true,
                  isSearchWithFiltersSupported = true,
                  isMultipleTagsSupported = false,
                  isTagsExclusionSupported = false,
              )
              CustomSourceType.LILIANA -> MangaListFilterCapabilities(
                  isSearchSupported = true,
                  isSearchWithFiltersSupported = true,
                  isMultipleTagsSupported = true,
                  isTagsExclusionSupported = true,
              )
              CustomSourceType.PIZZAREADER -> MangaListFilterCapabilities(
                  isSearchSupported = true,
                  isSearchWithFiltersSupported = true,
                  isMultipleTagsSupported = true,
                  isTagsExclusionSupported = true,
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

      // ── New parsers (batch 1) ─────────────────────────────────────────────────
      private val comixToParser: ComixToHtmlParser by lazy { ComixToHtmlParser(customSource) }
      private val comicKParser: ComicKApiParser by lazy { ComicKApiParser(customSource) }
      private val batoParser: BatoHtmlParser by lazy { BatoHtmlParser(customSource) }
      private val nineMangaParser: NineMangaHtmlParser by lazy { NineMangaHtmlParser(customSource) }
      private val mangaHostParser: MangaHostHtmlParser by lazy { MangaHostHtmlParser(customSource) }
      private val mangaReaderParser: MangaReaderHtmlParser by lazy { MangaReaderHtmlParser(customSource) }
      private val mangaFoxParser: MangaFoxHtmlParser by lazy { MangaFoxHtmlParser(customSource) }
      private val tcbScansParser: TcbScansHtmlParser by lazy { TcbScansHtmlParser(customSource) }
      private val mangaNatoParser: MangaNatoHtmlParser by lazy { MangaNatoHtmlParser(customSource) }
      private val readerFrontParser: ReaderFrontApiParser by lazy { ReaderFrontApiParser(customSource) }
      private val kissMangaParser: KissMangaHtmlParser by lazy { KissMangaHtmlParser(customSource) }
      private val cubariParser: CubariHtmlParser by lazy { CubariHtmlParser(customSource) }

      // ── New parsers (batch 2) ─────────────────────────────────────────────────
      private val mangaPillParser: MangaPillHtmlParser by lazy { MangaPillHtmlParser(customSource) }

      private val mangaHubParser: MangaHubHtmlParser by lazy { MangaHubHtmlParser(customSource) }
      private val mangaHereParser: MangaHereHtmlParser by lazy { MangaHereHtmlParser(customSource) }
      private val mangaLibParser: MangaLibApiParser by lazy { MangaLibApiParser(customSource) }
      private val mangagoParser: MangagoHtmlParser by lazy { MangagoHtmlParser(customSource) }
      private val mangaFreakParser: MangaFreakHtmlParser by lazy { MangaFreakHtmlParser(customSource) }
      private val mangaOwlParser: MangaOwlHtmlParser by lazy { MangaOwlHtmlParser(customSource) }
      private val nettruyenParser: NettruyenHtmlParser by lazy { NettruyenHtmlParser(customSource) }
      private val truyenQQParser: TruyenQQHtmlParser by lazy { TruyenQQHtmlParser(customSource) }
      private val mangaKatanaParser: MangaKatanaHtmlParser by lazy { MangaKatanaHtmlParser(customSource) }

      // ── New parsers (batch 3 — kotatsu-parsers-redo families) ────────────────
      private val zeistMangaParser: ZeistMangaHtmlParser by lazy { ZeistMangaHtmlParser(customSource) }
      private val keyoappParser: KeyoappHtmlParser by lazy { KeyoappHtmlParser(customSource) }
      private val heanCmsParser: HeanCmsApiParser by lazy { HeanCmsApiParser(customSource) }
      private val wpComicsParser: WpComicsHtmlParser by lazy { WpComicsHtmlParser(customSource) }
      private val mmrcmsParser: MmrcmsHtmlParser by lazy { MmrcmsHtmlParser(customSource) }
      private val madthemeParser: MadthemeHtmlParser by lazy { MadthemeHtmlParser(customSource) }
      private val mangaboxParser: MangaboxHtmlParser by lazy { MangaboxHtmlParser(customSource) }
      private val lilianaParser: LilianaHtmlParser by lazy { LilianaHtmlParser(customSource) }
      private val ikenParser: IkenApiParser by lazy { IkenApiParser(customSource) }
      private val scanParser: ScanHtmlParser by lazy { ScanHtmlParser(customSource) }
      private val pizzaReaderParser: PizzaReaderApiParser by lazy { PizzaReaderApiParser(customSource) }
      private val fmReaderParser: FmReaderHtmlParser by lazy { FmReaderHtmlParser(customSource) }
      private val gattsuParser: GattsuHtmlParser by lazy { GattsuHtmlParser(customSource) }
      private val animeBootstrapParser: AnimeBootstrapHtmlParser by lazy { AnimeBootstrapHtmlParser(customSource) }

      // ── Template-driven parser ────────────────────────────────────────────────
      private val templateParser: TemplateHtmlParser by lazy { TemplateHtmlParser(customSource) }

      // ── MangaRepository implementation ────────────────────────────────────────

      override suspend fun getList(
          offset: Int,
          order: SortOrder?,
          filter: MangaListFilter?,
      ): List<Manga> {
          val _src = customSource.source
          android.util.Log.d("TsukiDebug", "CMR.getList: name='${_src.name}' type=${_src.type.name} parserSourceName=${_src.parserSourceName}")
          if (_src.type == io.github.landwarderer.futon.customsource.domain.CustomSourceType.CUSTOM_TEMPLATE) {
              val _tName = _src.parserSourceName
              val _tFound = io.github.landwarderer.futon.customsource.data.ParserTemplateRepository.peekByName(_tName ?: "")
              android.util.Log.d("TsukiDebug", "CMR.getList: CUSTOM_TEMPLATE lookup name='$_tName' found=${_tFound != null} instanceReady=${io.github.landwarderer.futon.customsource.data.ParserTemplateRepository.instanceIsReady()} knownNames=${io.github.landwarderer.futon.customsource.data.ParserTemplateRepository.peekAll().map { it.name }}")
          } else {
              android.util.Log.d("TsukiDebug", "CMR.getList: routing ${_src.type.name} to proven parser")
          }
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
              CustomSourceType.COMIXTO               -> runCatching { comixToParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.COMICK_API            -> runCatching { comicKParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.BATO                  -> runCatching { batoParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.NINEMANGA             -> runCatching { nineMangaParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAHOST             -> runCatching { mangaHostParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAREADER           -> runCatching { mangaReaderParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAFOX              -> runCatching { mangaFoxParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.TCBSCANS              -> runCatching { tcbScansParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGANATO             -> runCatching { mangaNatoParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.READERFRONT           -> runCatching { readerFrontParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.KISSMANGA             -> runCatching { kissMangaParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.CUBARI                -> runCatching { cubariParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAPILL             -> runCatching { mangaPillParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAHUB              -> runCatching { mangaHubParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAHERE             -> runCatching { mangaHereParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGALIB              -> runCatching { mangaLibParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAGO               -> runCatching { mangagoParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAFREAK            -> runCatching { mangaFreakParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAOWL              -> runCatching { mangaOwlParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.NETTRUYEN             -> runCatching { nettruyenParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.TRUYENQQ              -> runCatching { truyenQQParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAKATANA           -> runCatching { mangaKatanaParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.ZEISTMANGA            -> runCatching { zeistMangaParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.KEYOAPP               -> runCatching { keyoappParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.HEANCMS               -> runCatching { heanCmsParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.WPCOMICS              -> runCatching { wpComicsParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MMRCMS                -> runCatching { mmrcmsParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MADTHEME              -> runCatching { madthemeParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.MANGABOX              -> runCatching { mangaboxParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.LILIANA               -> runCatching { lilianaParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.IKEN                  -> runCatching { ikenParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.SCAN                  -> runCatching { scanParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.PIZZAREADER           -> runCatching { pizzaReaderParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.FMREADER              -> runCatching { fmReaderParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.GATTSU                -> runCatching { gattsuParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.ANIMEBOOTSTRAP        -> runCatching { animeBootstrapParser.getList(offset, order, filter) }.getOrElse { emptyList() }
              CustomSourceType.CUSTOM_TEMPLATE       -> runCatching { templateParser.getList(offset, order, filter) }.getOrElse { emptyList() }
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
              CustomSourceType.COMIXTO               -> runCatching { comixToParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.COMICK_API            -> runCatching { comicKParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.BATO                  -> runCatching { batoParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.NINEMANGA             -> runCatching { nineMangaParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAHOST             -> runCatching { mangaHostParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAREADER           -> runCatching { mangaReaderParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAFOX              -> runCatching { mangaFoxParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.TCBSCANS              -> runCatching { tcbScansParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGANATO             -> runCatching { mangaNatoParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.READERFRONT           -> runCatching { readerFrontParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.KISSMANGA             -> runCatching { kissMangaParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.CUBARI                -> runCatching { cubariParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAPILL             -> runCatching { mangaPillParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAHUB              -> runCatching { mangaHubParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAHERE             -> runCatching { mangaHereParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGALIB              -> runCatching { mangaLibParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAGO               -> runCatching { mangagoParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAFREAK            -> runCatching { mangaFreakParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAOWL              -> runCatching { mangaOwlParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.NETTRUYEN             -> runCatching { nettruyenParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.TRUYENQQ              -> runCatching { truyenQQParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGAKATANA           -> runCatching { mangaKatanaParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.ZEISTMANGA            -> runCatching { zeistMangaParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.KEYOAPP               -> runCatching { keyoappParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.HEANCMS               -> runCatching { heanCmsParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.WPCOMICS              -> runCatching { wpComicsParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MMRCMS                -> runCatching { mmrcmsParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MADTHEME              -> runCatching { madthemeParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.MANGABOX              -> runCatching { mangaboxParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.LILIANA               -> runCatching { lilianaParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.IKEN                  -> runCatching { ikenParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.SCAN                  -> runCatching { scanParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.PIZZAREADER           -> runCatching { pizzaReaderParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.FMREADER              -> runCatching { fmReaderParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.GATTSU                -> runCatching { gattsuParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.ANIMEBOOTSTRAP        -> runCatching { animeBootstrapParser.getDetails(manga) }.getOrElse { manga }
              CustomSourceType.CUSTOM_TEMPLATE       -> runCatching { templateParser.getDetails(manga) }.getOrElse { manga }
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
              CustomSourceType.COMIXTO               -> runCatching { comixToParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.COMICK_API            -> runCatching { comicKParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.BATO                  -> runCatching { batoParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.NINEMANGA             -> runCatching { nineMangaParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAHOST             -> runCatching { mangaHostParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAREADER           -> runCatching { mangaReaderParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAFOX              -> runCatching { mangaFoxParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.TCBSCANS              -> runCatching { tcbScansParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGANATO             -> runCatching { mangaNatoParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.READERFRONT           -> runCatching { readerFrontParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.KISSMANGA             -> runCatching { kissMangaParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.CUBARI                -> runCatching { cubariParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAPILL             -> runCatching { mangaPillParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAHUB              -> runCatching { mangaHubParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAHERE             -> runCatching { mangaHereParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGALIB              -> runCatching { mangaLibParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAGO               -> runCatching { mangagoParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAFREAK            -> runCatching { mangaFreakParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAOWL              -> runCatching { mangaOwlParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.NETTRUYEN             -> runCatching { nettruyenParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.TRUYENQQ              -> runCatching { truyenQQParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGAKATANA           -> runCatching { mangaKatanaParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.ZEISTMANGA            -> runCatching { zeistMangaParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.KEYOAPP               -> runCatching { keyoappParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.HEANCMS               -> runCatching { heanCmsParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.WPCOMICS              -> runCatching { wpComicsParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MMRCMS                -> runCatching { mmrcmsParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MADTHEME              -> runCatching { madthemeParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.MANGABOX              -> runCatching { mangaboxParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.LILIANA               -> runCatching { lilianaParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.IKEN                  -> runCatching { ikenParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.SCAN                  -> runCatching { scanParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.PIZZAREADER           -> runCatching { pizzaReaderParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.FMREADER              -> runCatching { fmReaderParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.GATTSU                -> runCatching { gattsuParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.ANIMEBOOTSTRAP        -> runCatching { animeBootstrapParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.CUSTOM_TEMPLATE       -> runCatching { templateParser.getPages(chapter) }.getOrElse { emptyList() }
              CustomSourceType.BROWSER_SOURCE         -> BrowserSourcePageStore.getAndClear(chapter.id)
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
          CustomSourceType.COMIXTO -> MangaListFilterOptions(
              availableTags = runCatching { comixToParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.BATO -> MangaListFilterOptions(
              availableTags = runCatching { batoParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.NINEMANGA -> MangaListFilterOptions(
              availableTags = runCatching { nineMangaParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAHOST -> MangaListFilterOptions(
              availableTags = runCatching { mangaHostParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAREADER -> MangaListFilterOptions(
              availableTags = runCatching { mangaReaderParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAFOX -> MangaListFilterOptions(
              availableTags = runCatching { mangaFoxParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGANATO -> MangaListFilterOptions(
              availableTags = runCatching { mangaNatoParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.KISSMANGA -> MangaListFilterOptions(
              availableTags = runCatching { kissMangaParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAPILL -> MangaListFilterOptions(
              availableTags = runCatching { mangaPillParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAHUB -> MangaListFilterOptions(
              availableTags = runCatching { mangaHubParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAHERE -> MangaListFilterOptions(
              availableTags = runCatching { mangaHereParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAGO -> MangaListFilterOptions(
              availableTags = runCatching { mangagoParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAFREAK -> MangaListFilterOptions(
              availableTags = runCatching { mangaFreakParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAOWL -> MangaListFilterOptions(
              availableTags = runCatching { mangaOwlParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.NETTRUYEN -> MangaListFilterOptions(
              availableTags = runCatching { nettruyenParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.TRUYENQQ -> MangaListFilterOptions(
              availableTags = runCatching { truyenQQParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGAKATANA -> MangaListFilterOptions(
              availableTags = runCatching { mangaKatanaParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.ZEISTMANGA -> MangaListFilterOptions(
              availableTags = runCatching { zeistMangaParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.KEYOAPP -> MangaListFilterOptions(
              availableTags = runCatching { keyoappParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.HEANCMS -> MangaListFilterOptions(
              availableTags = runCatching { heanCmsParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.WPCOMICS -> MangaListFilterOptions(
              availableTags = runCatching { wpComicsParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MMRCMS -> MangaListFilterOptions(
              availableTags = runCatching { mmrcmsParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MADTHEME -> MangaListFilterOptions(
              availableTags = runCatching { madthemeParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.MANGABOX -> MangaListFilterOptions(
              availableTags = runCatching { mangaboxParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.LILIANA -> MangaListFilterOptions(
              availableTags = runCatching { lilianaParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.IKEN -> MangaListFilterOptions(
              availableTags = runCatching { ikenParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.SCAN -> MangaListFilterOptions(
              availableTags = runCatching { scanParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.PIZZAREADER -> MangaListFilterOptions(
              availableTags = runCatching { pizzaReaderParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.FMREADER -> MangaListFilterOptions(
              availableTags = runCatching { fmReaderParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.GATTSU -> MangaListFilterOptions(
              availableTags = runCatching { gattsuParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.ANIMEBOOTSTRAP -> MangaListFilterOptions(
              availableTags = runCatching { animeBootstrapParser.getGenres() }.getOrElse { emptySet() },
          )
          CustomSourceType.CUSTOM_TEMPLATE -> MangaListFilterOptions(
              availableTags = runCatching { templateParser.getGenres() }.getOrElse { emptySet() },
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
