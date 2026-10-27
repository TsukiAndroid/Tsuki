package io.github.landwarderer.futon.mihon.extensions.repo

import io.github.landwarderer.futon.core.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

class ExtensionRepoServiceTest {

    private fun createService(): ExtensionRepoService = ExtensionRepoService(
        httpClient = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        settings = Mockito.mock(AppSettings::class.java),
    )

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
    fun rejectInsecureRepositoryUrl() {
        assertNull(createService().normalizeIndexUrl("http://example.com/extensions/index.json"))
    }
}
