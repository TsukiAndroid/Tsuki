package io.github.landwarderer.futon.webviewsource.data.anilist

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.await
import io.github.landwarderer.futon.scrobbling.anilist.data.AniListRepository
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerManga
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerService
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerType
import javax.inject.Inject
import javax.inject.Singleton

private const val GRAPHQL_URL = "https://graphql.anilist.co"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * Thin wrapper that reuses the existing [AniListRepository] for search and
 * OAuth state, and the existing scrobbler [OkHttpClient] (which carries
 * [AniListInterceptor] / [AniListAuthenticator]) for raw GraphQL mutations.
 *
 * Do NOT inject a fresh OkHttpClient here – reuse the scrobbling one so
 * we never create a second AniList auth stack.
 */
@Singleton
class WebViewAniListRepository @Inject constructor(
    private val aniListRepository: AniListRepository,
    @ScrobblerType(ScrobblerService.ANILIST) private val okHttp: OkHttpClient,
) {

    /** True when the user has already authorised AniList in scrobbling settings. */
    val isLoggedIn: Boolean
        get() = aniListRepository.isAuthorized

    /** URL to open in a browser / Custom Tab to start the AniList OAuth flow. */
    val oauthUrl: String
        get() = aniListRepository.oauthUrl

    /**
     * Search AniList for manga by title.
     * Does NOT require a token – public read-only query.
     */
    suspend fun searchManga(query: String): List<ScrobblerManga> =
        aniListRepository.findManga(query, 0)

    /**
     * Saves read progress for a linked AniList entry.
     * Requires the user to be logged in; silently returns false otherwise.
     * The [AniListInterceptor] on the OkHttpClient injects the Bearer token
     * automatically from [ScrobblerStorage].
     */
    suspend fun syncProgress(anilistId: Int, chapter: Int): Boolean = runCatching {
        if (!isLoggedIn) return false
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}progress: Int, ${'$'}status: MediaListStatus) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress, status: ${'$'}status) {
                id status progress
              }
            }
        """.trimIndent()
        val variables = JSONObject()
            .put("mediaId", anilistId)
            .put("progress", chapter)
            .put("status", "CURRENT")
        graphqlPost(mutation, variables)
        true
    }.getOrElse { false }

    /**
     * Fetches the current reading-list entry for the given media.
     * Returns null if not logged in or not in the user's list.
     */
    suspend fun getListEntry(anilistId: Int): AniListEntryResult? = runCatching {
        if (!isLoggedIn) return null
        val query = """
            query (${'$'}mediaId: Int) {
              Media(id: ${'$'}mediaId) {
                mediaListEntry { status progress }
              }
            }
        """.trimIndent()
        val variables = JSONObject().put("mediaId", anilistId)
        val response = graphqlPost(query, variables)
        val entry = response
            .getJSONObject("data")
            .getJSONObject("Media")
            .optJSONObject("mediaListEntry") ?: return null
        AniListEntryResult(
            status = entry.optString("status").takeIf { it.isNotBlank() },
            progress = entry.optInt("progress", 0),
        )
    }.getOrNull()

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun graphqlPost(query: String, variables: JSONObject): JSONObject {
        val bodyString = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
        val requestBody = bodyString.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(GRAPHQL_URL)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()
        val response = okHttp.newCall(request).await()
        val responseBody = response.body?.string() ?: error("Empty AniList response")
        return JSONObject(responseBody)
    }
}

data class AniListEntryResult(
    val status: String?,
    val progress: Int,
)
