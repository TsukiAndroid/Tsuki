package io.github.landwarderer.futon.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.exceptions.InteractiveActionRequiredException
import io.github.landwarderer.futon.core.nav.AppRouter
import io.github.landwarderer.futon.core.nav.router
import io.github.landwarderer.futon.core.parser.ParserMangaRepository
import io.github.landwarderer.futon.core.util.ext.getDisplayMessage
import io.github.landwarderer.futon.core.util.ext.printStackTraceDebug
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BrowserActivity : BaseBrowserActivity() {

    @Inject
    lateinit var customSourcesRepository: CustomSourcesRepository

    private var customSourceId: Long? = null

    override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
        viewBinding.webView.webViewClient = BrowserClient(this, adBlock)

        customSourceId = intent?.getStringExtra(AppRouter.KEY_SOURCE)
            ?.let { CustomMangaSource.extractId(it) }

        lifecycleScope.launch {
            try {
                proxyProvider.applyWebViewConfig()
            } catch (e: Exception) {
                e.printStackTraceDebug("BrowserActivity::onCreate2")
                Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
            }
            if (savedInstanceState == null) {
                val baseUrl = intent?.dataString
                if (baseUrl.isNullOrEmpty()) {
                    finishAfterTransition()
                } else {
                    val resumeUrl = customSourceId
                        ?.let { customSourcesRepository.getLastUrl(it) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: baseUrl
                    onTitleChanged(
                        intent?.getStringExtra(AppRouter.KEY_TITLE) ?: getString(R.string.loading_),
                        resumeUrl,
                    )
                    viewBinding.webView.loadUrl(resumeUrl)
                }
            }
        }
    }

    override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
        super.onTitleChanged(title, subtitle)
        val url = subtitle?.toString()
        if (!url.isNullOrEmpty()) {
            customSourceId?.let { customSourcesRepository.saveLastUrl(it, url) }
        }
    }

    override fun onStop() {
        if (hasViewBinding()) {
            val currentUrl = viewBinding.webView.url
            if (!currentUrl.isNullOrEmpty()) {
                customSourceId?.let { customSourcesRepository.saveLastUrl(it, currentUrl) }
            }
        }
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.opt_browser, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            viewBinding.webView.stopLoading()
            finishAfterTransition()
            true
        }

        R.id.action_browser -> {
            if (!router.openExternalBrowser(viewBinding.webView.url.orEmpty(), item.title)) {
                Snackbar.make(viewBinding.webView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
            }
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    class Contract : ActivityResultContract<InteractiveActionRequiredException, Unit>() {
        override fun createIntent(
            context: Context,
            input: InteractiveActionRequiredException
        ): Intent = AppRouter.browserIntent(
            context = context,
            url = input.url,
            source = input.source,
            title = null,
        )

        override fun parseResult(resultCode: Int, intent: Intent?): Unit = Unit
    }

    companion object {
        const val TAG = "BrowserActivity"
    }
}
