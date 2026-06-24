package io.github.landwarderer.futon.browsersource.ui

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.error
import coil3.request.placeholder
import coil3.request.transformations
import coil3.target.ImageViewTarget
import coil3.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI
import javax.inject.Inject

/**
 * Bottom sheet for adding a new BROWSER_SOURCE.
 *
 * UI: URL input → favicon auto-fetch → preview → "Add" button.
 * No CSS selectors, no parser setup, no detection needed.
 */
@AndroidEntryPoint
class AddBrowserSourceSheet : BottomSheetDialogFragment() {

    @Inject lateinit var customSourcesRepository: CustomSourcesRepository
    @Inject lateinit var imageLoader: ImageLoader

    private var faviconFetchJob: Job? = null
    private var fetchedFaviconUrl: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_add_browser_source, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val urlLayout     = view.findViewById<TextInputLayout>(R.id.layout_browser_url)
        val urlInput      = view.findViewById<TextInputEditText>(R.id.input_browser_url)
        val nameInput     = view.findViewById<TextInputEditText>(R.id.input_browser_name)
        val faviconCard   = view.findViewById<View>(R.id.favicon_preview_card)
        val faviconImage  = view.findViewById<android.widget.ImageView>(R.id.favicon_image)
        val faviconDomain = view.findViewById<android.widget.TextView>(R.id.favicon_domain)
        val btnAdd        = view.findViewById<MaterialButton>(R.id.btn_add_browser_source)
        val btnCancel     = view.findViewById<MaterialButton>(R.id.btn_cancel_browser)

        faviconCard.isVisible = false

        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val url = s?.toString().orEmpty().trim()
                urlLayout.error = null
                faviconCard.isVisible = false
                faviconFetchJob?.cancel()

                if (url.length < 4) return

                // Debounce 700ms
                faviconFetchJob = lifecycleScope.launch {
                    delay(700)
                    val normalised = normaliseUrl(url) ?: return@launch
                    val domain = extractDomain(normalised)
                    faviconDomain.text = domain
                    faviconCard.isVisible = true

                    // Auto-fill name if blank
                    if (nameInput.text.isNullOrBlank()) {
                        val suggestedName = domain.removePrefix("www.")
                            .substringBefore(".")
                            .replaceFirstChar { it.uppercase() }
                        nameInput.setText(suggestedName)
                    }

                    // Fetch favicon
                    val faviconUrl = fetchFavicon(normalised, domain)
                    fetchedFaviconUrl = faviconUrl

                    if (faviconUrl != null) {
                        val request = ImageRequest.Builder(requireContext())
                            .data(faviconUrl)
                            .placeholder(R.drawable.ic_browser_source)
                            .error(R.drawable.ic_browser_source)
                            .transformations(CircleCropTransformation())
                            .target(ImageViewTarget(faviconImage))
                            .build()
                        imageLoader.enqueue(request)
                    } else {
                        // Show letter avatar
                        showLetterAvatar(faviconImage, domain)
                    }
                }
            }
        })

        btnAdd.setOnClickListener {
            val rawUrl = urlInput.text?.toString().orEmpty().trim()
            val name   = nameInput.text?.toString().orEmpty().trim()
            urlLayout.error = null

            val normUrl = normaliseUrl(rawUrl)
            if (normUrl == null) {
                urlLayout.error = getString(R.string.browser_source_url_invalid)
                return@setOnClickListener
            }

            // Duplicate check
            val existing = customSourcesRepository.findByUrl(normUrl)
            if (existing != null) {
                urlLayout.error = getString(R.string.browser_source_duplicate)
                return@setOnClickListener
            }

            val domain = extractDomain(normUrl)
            val sourceName = name.ifBlank {
                domain.removePrefix("www.").substringBefore(".")
                    .replaceFirstChar { it.uppercase() }
            }

            val source = CustomSource(
                id          = CustomSourcesRepository.generateId(),
                name        = sourceName,
                baseUrl     = normUrl,
                type        = CustomSourceType.BROWSER_SOURCE,
                iconUrl     = fetchedFaviconUrl,
                createdAt   = System.currentTimeMillis(),
                isEnabled   = true,
            )
            customSourcesRepository.add(source)

            dismiss()
        }

        btnCancel.setOnClickListener { dismiss() }
    }

    // ── Favicon fetching ──────────────────────────────────────────────────────

    private suspend fun fetchFavicon(siteUrl: String, domain: String): String? =
        withContext(Dispatchers.IO) {
            // Strategy 1: direct favicon.ico
            val directFavicon = "${siteUrl.trimEnd('/')}/favicon.ico"
            if (isUrlReachable(directFavicon)) return@withContext directFavicon

            // Strategy 2: parse <link rel="icon"> from homepage HTML
            val htmlFavicon = runCatching {
                val doc = Jsoup.connect(siteUrl)
                    .timeout(5_000)
                    .userAgent(BROWSER_UA)
                    .get()
                val href = doc.select("link[rel~=(?i)icon]").attr("abs:href")
                href.takeIf { it.isNotEmpty() && it.startsWith("http") }
            }.getOrNull()
            if (htmlFavicon != null && isUrlReachable(htmlFavicon)) return@withContext htmlFavicon

            // Strategy 3: Google's favicon service
            val googleFavicon = "https://www.google.com/s2/favicons?sz=64&domain=$domain"
            googleFavicon // Google always returns something; use as last fallback
        }

    private fun isUrlReachable(url: String): Boolean {
        return runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", BROWSER_UA)
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        }.getOrDefault(false)
    }

    private fun showLetterAvatar(imageView: android.widget.ImageView, domain: String) {
        val letter = domain.removePrefix("www.").firstOrNull()?.uppercaseChar() ?: '?'
        val color = AVATAR_COLORS[domain.hashCode().and(0x7FFFFFFF) % AVATAR_COLORS.size]
        val drawable = LetterAvatarDrawable(letter, color)
        imageView.setImageDrawable(drawable)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun normaliseUrl(input: String): String? {
        val withScheme = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            else -> "https://$input"
        }
        return runCatching {
            val uri = URI(withScheme)
            if (uri.host == null) return null
            "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
        }.getOrNull()
    }

    private fun extractDomain(url: String): String {
        return runCatching {
            URI(url).host ?: url
        }.getOrDefault(url)
    }

    companion object {
        const val TAG = "AddBrowserSourceSheet"

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val AVATAR_COLORS = intArrayOf(
            0xFF1976D2.toInt(), 0xFF388E3C.toInt(), 0xFFD32F2F.toInt(),
            0xFF7B1FA2.toInt(), 0xFFF57C00.toInt(), 0xFF0288D1.toInt(),
            0xFF00796B.toInt(), 0xFFC62828.toInt(), 0xFF4527A0.toInt(),
        )

        fun newInstance() = AddBrowserSourceSheet()
    }
}

/**
 * Simple letter-avatar [Drawable] used as a fallback when no favicon is found.
 */
private class LetterAvatarDrawable(
    private val letter: Char,
    private val bgColor: Int,
) : android.graphics.drawable.Drawable() {
    private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = android.graphics.Paint.Style.FILL
    }
    private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val radius = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(cx, cy, radius, bgPaint)
        textPaint.textSize = radius * 1.1f
        canvas.drawText(letter.toString(), cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
    }

    override fun setAlpha(alpha: Int) { textPaint.alpha = alpha; bgPaint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { textPaint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
