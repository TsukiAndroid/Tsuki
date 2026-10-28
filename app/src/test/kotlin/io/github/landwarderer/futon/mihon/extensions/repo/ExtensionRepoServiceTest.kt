package io.github.landwarderer.futon.mihon.extensions.repo

import android.content.Context
import android.content.SharedPreferences
import io.github.landwarderer.futon.core.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class ExtensionRepoServiceTest {

    private fun createService(httpClient: OkHttpClient = OkHttpClient()): ExtensionRepoService {
        val settings = AppSettings(
            Mockito.mock(Context::class.java),
            Mockito.mock(SharedPreferences::class.java),
        )
        return ExtensionRepoService(
            httpClient = httpClient,
            json = Json { ignoreUnknownKeys = true },
            settings = settings,
        )
    }

    private fun stubCatalogClient(
        protobufBytes: ByteArray? = null,
        jsonBody: String? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val response = when {
                path.endsWith("/index.pb") && protobufBytes != null -> 200 to protobufBytes
                path.endsWith("/index.json") && jsonBody != null -> 200 to jsonBody.encodeToByteArray()
                else -> 404 to byteArrayOf()
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(response.first)
                .message(if (response.first == 200) "OK" else "Not Found")
                .body(response.second.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
        .build()

    private fun keiyoushiRepo() = ExternalExtensionRepo(
        type = ExternalExtensionType.MIHON,
        baseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
        name = "Keiyoushi",
        shortName = "Keiyoushi",
        website = "https://keiyoushi.github.io",
        signingKeyFingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
        createdAt = 0L,
        updatedAt = 0L,
        lastSuccessAt = 0L,
        lastError = null,
    )

    private fun keiyoushiProtobufIndex(): ByteArray {
        val source = stringField(2, "AHottie") + stringField(3, "en")
        val resources = stringField(1, "https://cdn.example.test/extension.apk") +
            stringField(2, "https://cdn.example.test/icon.png") +
            stringField(501, "https://cdn.example.test/extension.jar")
        val extension = stringField(1, "AHottie") +
            stringField(2, "eu.kanade.tachiyomi.extension.all.ahottie") +
            messageField(3, resources) +
            stringField(4, "1.6") +
            varintField(5, 106004L) +
            stringField(6, "1.6.4") +
            varintField(7, 3L) +
            messageField(8, source)
        val index = stringField(1, "Keiyoushi") +
            messageField(101, messageField(1, extension))

        return ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(index) }
        }.toByteArray()
    }

    private fun stringField(number: Int, value: String): ByteArray =
        messageField(number, value.encodeToByteArray())

    private fun messageField(number: Int, value: ByteArray): ByteArray =
        varint((number.toLong() shl 3) or 2L) + varint(value.size.toLong()) + value

    private fun varintField(number: Int, value: Long): ByteArray =
        varint(number.toLong() shl 3) + varint(value)

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream()
        while ((remaining and -128L) != 0L) {
            output.write(((remaining and 127L) or 128L).toInt())
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
        return output.toByteArray()
    }

    @Test
    fun normalizeKeiyoushiBlobIndexUrl() {
        val service = createService()

        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            service.normalizeIndexUrl("https://github.com/keiyoushi/extensions/blob/repo/index.json"),
        )
    }

    @Test
    fun normalizeRepositoryIndexUrlVariants() {
        val service = createService()

        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            service.normalizeIndexUrl("https://github.com/keiyoushi/extensions/tree/repo"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            service.normalizeIndexUrl("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
            service.baseUrlFromIndexUrl("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"),
        )
    }

    @Test
    fun normalizeKeiyoushiLegacyReferencesToLiveRepoBranch() {
        val service = createService()

        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            service.normalizeIndexUrl("https://raw.githubusercontent.com/keiyoushi/extensions/refs/heads/repo/index.json"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            service.normalizeIndexUrl("https://raw.githubusercontent.com/keiyoushi/extensions/main/index.json"),
        )
    }

    @Test
    fun fetchKeiyoushiRepoDetailsWithoutNetworkRoundTrip() = runBlocking {
        val repo = createService().fetchRepoDetails(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
            ExternalExtensionType.MIHON,
        )

        assertEquals("Keiyoushi", repo.displayName)
        assertEquals(
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            repo.signingKeyFingerprint,
        )
    }

    @Test
    fun fetchKeiyoushiGzipProtobufCatalog() = runBlocking {
        val extensions = createService(
            stubCatalogClient(protobufBytes = keiyoushiProtobufIndex()),
        ).fetchAvailableExtensions(keiyoushiRepo())

        assertEquals(1, extensions.size)
        assertEquals("AHottie", extensions.single().name)
        assertEquals("eu.kanade.tachiyomi.extension.all.ahottie", extensions.single().pkgName)
        assertEquals("https://cdn.example.test/extension.apk", extensions.single().apkName)
    }

    @Test
    fun fetchKeiyoushiJsonCatalogWhenProtobufIsUnavailable() = runBlocking {
        val json = """
            {
              "name": "Keiyoushi",
              "extensionList": {
                "extensions": [{
                  "name": "AHottie",
                  "packageName": "eu.kanade.tachiyomi.extension.all.ahottie",
                  "resources": {
                    "apkUrl": "https://cdn.example.test/extension.apk",
                    "iconUrl": "https://cdn.example.test/icon.png",
                    "jarUrl": "https://cdn.example.test/extension.jar"
                  },
                  "extensionLib": "1.6",
                  "versionCode": "106004",
                  "versionName": "1.6.4",
                  "contentWarning": "CONTENT_WARNING_NSFW",
                  "sources": [{"name": "AHottie", "language": "all"}]
                }]
              }
            }
        """.trimIndent()
        val extensions = createService(
            stubCatalogClient(jsonBody = json),
        ).fetchAvailableExtensions(keiyoushiRepo())

        assertEquals(1, extensions.size)
        assertEquals("AHottie", extensions.single().name)
        assertEquals("eu.kanade.tachiyomi.extension.all.ahottie", extensions.single().pkgName)
        assertEquals("https://cdn.example.test/extension.apk", extensions.single().apkName)
    }

    @Test
    fun rejectInsecureRepositoryUrl() {
        assertNull(createService().normalizeIndexUrl("http://example.com/extensions/index.json"))
    }
}
