package io.github.landwarderer.futon.settings.about

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.viewModels
import androidx.core.text.buildSpannedString
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.github.AppVersion
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.core.util.ext.consumeAllSystemBarsInsets
import io.github.landwarderer.futon.core.util.ext.setTextAndVisible
import io.github.landwarderer.futon.core.util.ext.observe
import io.github.landwarderer.futon.core.util.ext.observeEvent
import io.github.landwarderer.futon.core.util.ext.systemBarsInsets
import io.github.landwarderer.futon.databinding.ActivityAppUpdateBinding

@AndroidEntryPoint
class AppUpdateActivity : BaseActivity<ActivityAppUpdateBinding>(), View.OnClickListener {

        private val viewModel: AppUpdateViewModel by viewModels()

        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(ActivityAppUpdateBinding.inflate(layoutInflater))
                viewBinding.buttonUpdate.setText(R.string.download_apk)
                viewModel.nextVersion.observe(this, ::onNextVersionChanged)
                viewBinding.buttonCancel.setOnClickListener(this)
                viewBinding.buttonUpdate.setOnClickListener(this)
                viewModel.isLoading.observe(this) { isLoading ->
                        viewBinding.buttonUpdate.isEnabled =
                                viewModel.nextVersion.value != null && !isLoading
                }
                viewModel.onError.observeEvent(this, ::onError)
        }

        override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
                val bars = insets.systemBarsInsets
                viewBinding.root.updatePadding(top = bars.top)
                viewBinding.dockedToolbarChild.updateLayoutParams<MarginLayoutParams> {
                        leftMargin   = bars.left
                        rightMargin  = bars.right
                        bottomMargin = bars.bottom
                }
                viewBinding.scrollView.updatePadding(left = bars.left, right = bars.right)
                return insets.consumeAllSystemBarsInsets()
        }

        override fun onClick(v: View) {
                when (v.id) {
                        R.id.button_cancel -> finishAfterTransition()
                        R.id.button_update -> downloadApk()
                }
        }

        private suspend fun onNextVersionChanged(version: AppVersion?) {
                viewBinding.buttonUpdate.isEnabled = version != null && !viewModel.isLoading.value
                if (version == null) {
                        viewBinding.textViewContent.setText(R.string.loading_)
                        return
                }
                val markwon = Markwon.create(this)
                val message = withContext(Dispatchers.IO) {
                        buildSpannedString {
                                append(getString(R.string.new_version_s, version.name))
                                appendLine()
                                appendLine()
                                append(markwon.toMarkdown(version.description))
                        }
                }
                markwon.setParsedMarkdown(viewBinding.textViewContent, message)
        }

        /**
         * Enqueues the matched-arch APK download via Android's DownloadManager.
         *
         * The correct APK URL (already resolved by arch detection in AppUpdateRepository) is
         * stored in [AppVersion.apkUrl].  The arch suffix in the filename matches the naming
         * convention used by the release CI: arm64, arm32, x86_64, or universal.
         */
        private fun downloadApk() {
                val version = viewModel.nextVersion.value ?: return
                val apkUrl  = version.apkUrl.ifEmpty { version.url }
                if (apkUrl.isEmpty()) {
                        Snackbar.make(
                                viewBinding.scrollView,
                                R.string.operation_not_supported,
                                Snackbar.LENGTH_SHORT,
                        ).show()
                        return
                }

                val arch     = viewModel.deviceArch
                val fileName = "tsuki-update-" + version.name + "-" + arch + ".apk"

                val request = DownloadManager.Request(Uri.parse(apkUrl))
                        .setTitle(getString(R.string.new_version_s, version.name))
                        .setDescription(getString(R.string.app_update_available))
                        .setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                        )
                        .setDestinationInExternalFilesDir(
                                this,
                                Environment.DIRECTORY_DOWNLOADS,
                                fileName,
                        )
                        .setMimeType("application/vnd.android.package-archive")
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)
                        .addRequestHeader("User-Agent", "Tsuki/${io.github.landwarderer.futon.BuildConfig.VERSION_NAME} (Android)")

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)

                Snackbar.make(viewBinding.scrollView, R.string.download_started, Snackbar.LENGTH_LONG).show()
                viewBinding.buttonUpdate.isEnabled = false
                viewBinding.buttonUpdate.setText(R.string.downloading_apk)
        }

        private fun onError(e: Throwable) {
                viewBinding.textViewError.setTextAndVisible(R.string.error_occurred)
        }
}
