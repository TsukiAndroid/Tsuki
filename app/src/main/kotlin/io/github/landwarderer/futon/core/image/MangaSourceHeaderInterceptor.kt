package io.github.landwarderer.futon.core.image

  import coil3.intercept.Interceptor
  import coil3.network.httpHeaders
  import coil3.request.ImageResult
  import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
  import io.github.landwarderer.futon.core.model.unwrap
  import io.github.landwarderer.futon.core.network.CommonHeaders
  import io.github.landwarderer.futon.core.util.ext.mangaSourceKey
  import org.koitharu.kotatsu.parsers.model.MangaParserSource

  /**
   * Coil3 interceptor that injects HTTP headers required by hotlink-protected
   * manga sites when loading cover images and chapter pages.
   *
   * For [CustomMangaSource]:
   *  - Always sets Referer to the source base URL (required by virtually every
   *    hotlink-protected CDN — comix.to, Madara sites, MangaThemesia, etc.)
   *  - Always sets a full browser User-Agent string. Using "Tsuki/1.0 (Android)"
   *    causes many CDNs to return a red placeholder or HTTP 403 instead of the
   *    real cover image. We force the browser UA here even if Coil has already
   *    set one, because Coil's default UA is also non-browser.
   *
   * For [MangaParserSource] (built-in kotatsu sources):
   *  - Adds the MANGA_SOURCE header used by the kotatsu network layer.
   */
  class MangaSourceHeaderInterceptor : Interceptor {

      override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
          val source = chain.request.extras[mangaSourceKey]?.unwrap()
          val request = chain.request
          val headersBuilder = request.httpHeaders.newBuilder()

          when (source) {
              is MangaParserSource -> {
                  headersBuilder.set(CommonHeaders.MANGA_SOURCE, source.name)
              }
              is CustomMangaSource -> {
                  // Force Referer — needed by hotlink-protected CDNs on Madara,
                  // MangaThemesia, comix.to, and virtually all WordPress-based sites.
                  val referer = source.source.cleanBaseUrl + "/"
                  headersBuilder.set(CommonHeaders.REFERER, referer)

                  // Force a full browser UA — app/bot UAs are blocked by many CDNs
                  // and will return red placeholders, HTTP 403, or 1x1 pixel images
                  // instead of the real manga cover. This override is intentional.
                  headersBuilder.set(CommonHeaders.USER_AGENT, BROWSER_UA)
              }
              else -> return chain.proceed()
          }

          val newRequest = request.newBuilder()
              .httpHeaders(headersBuilder.build())
              .build()
          return chain.withRequest(newRequest).proceed()
      }

      companion object {
          // Full Chrome-on-Android UA — passes hotlink protection on all known manga CDNs
          const val BROWSER_UA =
              "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
      }
  }
  