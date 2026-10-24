package io.github.landwarderer.futon.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import io.github.landwarderer.futon.core.exceptions.CloudFlareBlockedException
import io.github.landwarderer.futon.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

class CloudFlareInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		val detected = runCatching {
			CloudFlareHelper.checkResponseForProtection(response)
		}.getOrDefault(CloudFlareHelper.PROTECTION_NOT_DETECTED)
		val protection = if (
			detected == CloudFlareHelper.PROTECTION_NOT_DETECTED &&
			(response.code == 403 || response.code == 503) &&
			(response.header("cf-ray") != null ||
				response.header("server")?.contains("cloudflare", ignoreCase = true) == true) &&
			response.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true
		) {
			CloudFlareHelper.PROTECTION_CAPTCHA
		} else {
			detected
		}
		return when (protection) {
			CloudFlareHelper.PROTECTION_BLOCKED -> response.closeThrowing(
				CloudFlareBlockedException(
					url = request.url.toString(),
					source = request.tag(MangaSource::class.java),
				),
			)

			CloudFlareHelper.PROTECTION_CAPTCHA -> response.closeThrowing(
				CloudFlareProtectedException(
					url = request.url.toString(),
					source = request.tag(MangaSource::class.java),
					headers = request.headers,
				),
			)

			else -> response
		}
	}

	private fun Response.closeThrowing(error: IOException): Nothing {
		try {
			close()
		} catch (e: Exception) {
			error.addSuppressed(e)
		}
		throw error
	}
}
