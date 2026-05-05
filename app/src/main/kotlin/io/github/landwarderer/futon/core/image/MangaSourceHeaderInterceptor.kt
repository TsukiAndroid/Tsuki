package io.github.landwarderer.futon.core.image

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.core.model.unwrap
import io.github.landwarderer.futon.core.network.CommonHeaders
import io.github.landwarderer.futon.core.util.ext.mangaSourceKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource

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
                // Add Referer so hotlink-protected custom-source sites serve cover images
                val referer = source.source.cleanBaseUrl + "/"
                if (request.httpHeaders[CommonHeaders.REFERER] == null) {
                    headersBuilder.set(CommonHeaders.REFERER, referer)
                }
                if (request.httpHeaders[CommonHeaders.USER_AGENT] == null) {
                    headersBuilder.set(CommonHeaders.USER_AGENT, "Tsuki/1.0 (Android)")
                }
            }
            else -> return chain.proceed()
        }

        val newRequest = request.newBuilder()
            .httpHeaders(headersBuilder.build())
            .build()
        return chain.withRequest(newRequest).proceed()
    }
}
