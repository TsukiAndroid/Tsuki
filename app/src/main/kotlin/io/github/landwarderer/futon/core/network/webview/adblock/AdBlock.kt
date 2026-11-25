package io.github.landwarderer.futon.core.network.webview.adblock

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.core.network.BaseHttpClient
import io.github.landwarderer.futon.core.network.CommonHeaders
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.util.ext.isNotEmpty
import io.github.landwarderer.futon.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.requireBody
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.sink
import java.io.File
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@Reusable
class AdBlock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {
    @Volatile private var rules: RulesList? = null

    @WorkerThread
    fun warmUp() {
        if (!settings.isAdBlockEnabled) return
        synchronized(this) {
            if (rules == null) rules = parseRules()
        }
    }

    @WorkerThread
    fun shouldLoadUrl(url: String, baseUrl: String?, resourceType: String? = null): Boolean {
        val parsedUrl = url.lowercase().toHttpUrlOrNull() ?: return true
        return shouldLoadUrl(parsedUrl, baseUrl?.lowercase()?.toHttpUrlOrNull(), resourceType)
    }

    @WorkerThread
    fun shouldLoadUrl(url: HttpUrl, baseUrl: HttpUrl?, resourceType: String? = null): Boolean {
        if (!settings.isAdBlockEnabled) return true
        val activeRules = synchronized(this) { rules ?: parseRules().also { rules = it } } ?: return true
        val rule = activeRules[url, baseUrl, resourceType]
        if (rule != null) Log.d(TAG, "Blocked " + url.host + url.encodedPath + " by " + rule)
        return rule == null
    }

    /** CSS cosmetic rules for a page, respecting the user's global ad-block toggle. */
    @WorkerThread
    fun cosmeticCss(pageUrl: String): String {
        if (!settings.isAdBlockEnabled) return ""
        val host = pageUrl.toHttpUrlOrNull()?.host ?: return ""
        val activeRules = synchronized(this) { rules ?: parseRules().also { rules = it } } ?: return ""
        return activeRules.cosmeticCss(host)
    }

    @WorkerThread
    private fun parseRules(): RulesList? = runCatchingCancellable {
        val parsed = RulesList()
        listOf(EASYLIST_FILENAME, EASYPRIVACY_FILENAME).forEach { filename ->
            val file = listFile(context, filename)
            if (file.exists()) file.useLines { lines -> lines.forEach(parsed::add) }
        }
        parsed.trimToSize()
        parsed
    }.onFailure { it.printStackTraceDebug("AdBlock::parseRules") }.getOrNull()

    class Updater @Inject constructor(
        @ApplicationContext private val context: Context,
        @BaseHttpClient private val okHttpClient: OkHttpClient,
    ) {
        suspend fun updateList() {
            updateFilterList(EASYLIST_URL, EASYLIST_FILENAME)
            updateFilterList(EASYPRIVACY_URL, EASYPRIVACY_FILENAME)
        }

        private suspend fun updateFilterList(url: String, filename: String) {
            val file = listFile(context, filename)
            val dateFormat = SimpleDateFormat(CommonHeaders.DATE_FORMAT, Locale.ENGLISH)
            val requestBuilder = Request.Builder().url(url).get()
            if (file.exists() && file.isNotEmpty()) {
                requestBuilder.header(CommonHeaders.IF_MODIFIED_SINCE, dateFormat.format(Date(file.lastModified())))
            }
            okHttpClient.newCall(requestBuilder.build()).await().use { response ->
                if (response.code == HttpURLConnection.HTTP_NOT_MODIFIED) return
                val lastModified = response.header(CommonHeaders.LAST_MODIFIED)?.let {
                    runCatching { dateFormat.parse(it) }.getOrNull()
                }?.time ?: System.currentTimeMillis()
                response.requireBody().source().use { source -> file.sink().use { sink -> source.readAll(sink) } }
                file.setLastModified(lastModified)
            }
        }
    }

    private companion object {
        private const val LIST_DIR = "adblock"
        private const val EASYLIST_FILENAME = "easylist.txt"
        private const val EASYPRIVACY_FILENAME = "easyprivacy.txt"
        private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
        private const val EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"
        private const val TAG = "AdBlock"

        fun listFile(context: Context, filename: String): File {
            val root = File(context.externalCacheDir ?: context.cacheDir, LIST_DIR)
            root.mkdirs()
            return File(root, filename)
        }
    }
}
