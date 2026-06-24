package io.github.landwarderer.futon.browsersource.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.view.isVisible
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * A [WebChromeClient] that drives a [LinearProgressIndicator] progress bar.
 */
class ProgressChromeClient(
    private val progressBar: LinearProgressIndicator,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        if (newProgress >= 100) {
            progressBar.isVisible = false
        } else {
            progressBar.isVisible = true
            progressBar.setProgressCompat(newProgress, true)
        }
    }
}
