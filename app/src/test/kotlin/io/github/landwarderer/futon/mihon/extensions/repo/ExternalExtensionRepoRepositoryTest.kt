package io.github.landwarderer.futon.mihon.extensions.repo

import android.content.Context
import io.github.landwarderer.futon.core.db.MangaDatabase
import io.github.landwarderer.futon.core.db.dao.ExternalExtensionRepoDao
import io.github.landwarderer.futon.core.db.entity.ExternalExtensionRepoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ExternalExtensionRepoRepositoryTest {

    @Test
    fun deletingLastRepositoryDoesNotAddKeiyoushiAgain() = runBlocking {
        val dao = InMemoryRepoDao()
        val database = Mockito.mock(MangaDatabase::class.java)
        Mockito.`when`(database.getExternalExtensionRepoDao()).thenReturn(dao)
        val service = Mockito.mock(ExtensionRepoService::class.java)
        Mockito.`when`(service.fetchAvailableExtensions(Mockito.any(ExternalExtensionRepo::class.java)))
            .thenReturn(emptyList())
        val repository = ExternalExtensionRepoRepository(
            appContext = Mockito.mock(Context::class.java),
            db = database,
            service = service,
        )
        val keiyoushi = ExternalExtensionRepo(
            type = ExternalExtensionType.MIHON,
            baseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
            name = "Keiyoushi",
            shortName = "Keiyoushi",
            website = "https://keiyoushi.github.io/extensions",
            signingKeyFingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            createdAt = 1L,
            updatedAt = 1L,
            lastSuccessAt = 1L,
            lastError = null,
        )
        dao.add(keiyoushi.toEntity())

        repository.delete(keiyoushi)
        val catalog = repository.getCatalogExtensions(ExternalExtensionType.MIHON)

        assertTrue(catalog.isEmpty())
        assertTrue(dao.entities.isEmpty())
    }

    private class InMemoryRepoDao : ExternalExtensionRepoDao {
        val entities = mutableListOf<ExternalExtensionRepoEntity>()

        override fun observeByType(type: ExternalExtensionType): Flow<List<ExternalExtensionRepoEntity>> =
            flowOf(entities.filter { it.type == type })

        override suspend fun getByType(type: ExternalExtensionType): List<ExternalExtensionRepoEntity> =
            entities.filter { it.type == type }

        override suspend fun get(type: ExternalExtensionType, baseUrl: String): ExternalExtensionRepoEntity? =
            entities.firstOrNull { it.type == type && it.baseUrl == baseUrl }

        override suspend fun getByFingerprint(
            type: ExternalExtensionType,
            fingerprint: String,
        ): ExternalExtensionRepoEntity? = entities.firstOrNull {
            it.type == type && it.signingKeyFingerprint == fingerprint
        }

        override suspend fun upsert(entity: ExternalExtensionRepoEntity) {
            entities.removeAll { it.type == entity.type && it.baseUrl == entity.baseUrl }
            entities += entity
        }

        override suspend fun delete(type: ExternalExtensionType, baseUrl: String) {
            entities.removeAll { it.type == type && it.baseUrl == baseUrl }
        }

        fun add(entity: ExternalExtensionRepoEntity) {
            entities += entity
        }
    }

    private fun ExternalExtensionRepo.toEntity() = ExternalExtensionRepoEntity(
        type = type,
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = signingKeyFingerprint,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSuccessAt = lastSuccessAt,
        lastError = lastError,
        version = version,
    )
}
