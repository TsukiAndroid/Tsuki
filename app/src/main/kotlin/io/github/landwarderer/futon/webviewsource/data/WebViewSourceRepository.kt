package io.github.landwarderer.futon.webviewsource.data

import io.github.landwarderer.futon.core.db.dao.WebViewSourceDao
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebViewSourceRepository @Inject constructor(
    private val dao: WebViewSourceDao,
) {
    fun observeAll(): Flow<List<WebViewSourceEntity>> = dao.observeAll()

    fun observeById(id: Long): Flow<WebViewSourceEntity?> = dao.observeById(id)

    suspend fun getById(id: Long): WebViewSourceEntity? = dao.getById(id)

    suspend fun getAll(): List<WebViewSourceEntity> = dao.getAll()

    suspend fun getAllWithAnilist(): List<WebViewSourceEntity> = dao.getAllWithAnilist()

    suspend fun save(source: WebViewSourceEntity) = dao.upsert(source)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun updateProgress(
        id: Long,
        url: String,
        scrollPercent: Float,
        chapter: Float?,
    ) = dao.updateProgress(
        id = id,
        url = url,
        scrollPercent = scrollPercent,
        chapter = chapter,
        timestamp = System.currentTimeMillis(),
    )

    suspend fun updateLatestKnownChapter(id: Long, chapter: Float) =
        dao.updateLatestKnownChapter(id, chapter)

    suspend fun updateAnilistLink(id: Long, anilistId: Int, status: String?) =
        dao.updateAnilistLink(id, anilistId, status)

    suspend fun setNotificationsEnabled(id: Long, enabled: Boolean) =
        dao.setNotificationsEnabled(id, enabled)

    /**
     * Generates a stable Long ID from a URL string.
     * Use this whenever you need to create or look up a source by URL.
     */
    fun idFromUrl(url: String): Long = url.trim().lowercase().hashCode().toLong()
}
