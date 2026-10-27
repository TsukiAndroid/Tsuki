package io.github.landwarderer.futon.mihon.extensions.repo

import io.github.landwarderer.futon.core.prefs.AppSettings
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
    fun rejectInsecureRepositoryUrl() {
        assertNull(createService().normalizeIndexUrl("http://example.com/extensions/index.json"))
    }
}
