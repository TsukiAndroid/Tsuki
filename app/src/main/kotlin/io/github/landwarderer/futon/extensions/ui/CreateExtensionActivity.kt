package io.github.landwarderer.futon.extensions.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.databinding.ActivityCreateExtensionBinding
import io.github.landwarderer.futon.extensions.domain.ExtensionType
import kotlinx.coroutines.launch

/**
 * Full-screen form for creating a new extension from scratch.
 *
 * The "Auto-Detect Parser" button fetches the entered base URL's homepage and
 * fingerprints it against known manga CMS patterns (Madara, MangaStream,
 * Manganelo, ManhwaRead, MangaDex). When a match is found a tailored JS
 * skeleton is pre-filled into the extension's source code; otherwise a generic
 * skeleton is used.
 */
@AndroidEntryPoint
class CreateExtensionActivity : BaseActivity<ActivityCreateExtensionBinding>() {

    private val viewModel by viewModels<CreateExtensionViewModel>()

    private var detectedTemplate: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityCreateExtensionBinding.inflate(layoutInflater))

        setTitle(R.string.ext_create_extension)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

        setupDropdowns()
        setupDetectButton()
        setupCreateButton()
        observeDetectState()
    }

    private fun setupDropdowns() {
        val languageLabels = arrayOf(
            getString(R.string.ext_language_dart),
            getString(R.string.ext_language_js),
        )
        viewBinding.spinnerLanguage.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languageLabels),
        )
        viewBinding.spinnerLanguage.setText(languageLabels[0], false)

        val typeLabels = arrayOf(
            getString(R.string.ext_source_type_single),
            getString(R.string.ext_source_type_multi),
            getString(R.string.ext_source_type_torrent),
        )
        viewBinding.spinnerType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, typeLabels),
        )
        viewBinding.spinnerType.setText(typeLabels[0], false)

        val targetLabels = arrayOf(
            getString(R.string.ext_target_manga),
            getString(R.string.ext_target_anime),
            getString(R.string.ext_target_novel),
        )
        viewBinding.spinnerTarget.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, targetLabels),
        )
        viewBinding.spinnerTarget.setText(targetLabels[0], false)
    }

    private fun setupDetectButton() {
        viewBinding.btnDetect.setOnClickListener {
            val url = viewBinding.editBaseUrl.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                viewBinding.editBaseUrl.error = getString(R.string.ext_base_url)
                return@setOnClickListener
            }
            detectedTemplate = null
            viewBinding.cardDetectResult.visibility = View.GONE
            viewModel.detectParser(url)
        }

        viewBinding.btnDetectDismiss.setOnClickListener {
            detectedTemplate = null
            viewBinding.cardDetectResult.visibility = View.GONE
            viewModel.resetDetect()
        }
    }

    private fun setupCreateButton() {
        viewBinding.btnCreate.setOnClickListener {
            val name = viewBinding.editName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                viewBinding.editName.error = getString(R.string.ext_name_hint)
                return@setOnClickListener
            }

            val langText = viewBinding.spinnerLanguage.text?.toString().orEmpty()
            val scriptLanguage = if (langText == getString(R.string.ext_language_dart)) {
                ExtensionType.DART
            } else {
                ExtensionType.JS
            }

            viewModel.createExtension(
                name = name,
                lang = viewBinding.editLang.text?.toString()?.trim().orEmpty(),
                baseUrl = viewBinding.editBaseUrl.text?.toString()?.trim().orEmpty(),
                apiUrl = viewBinding.editApiUrl.text?.toString()?.trim().orEmpty(),
                iconUrl = viewBinding.editIconUrl.text?.toString()?.trim().orEmpty(),
                notes = viewBinding.editNotes.text?.toString()?.trim().orEmpty(),
                scriptLanguage = scriptLanguage,
                sourceType = viewBinding.spinnerType.text?.toString().orEmpty()
                    .ifBlank { "single" },
                contentTarget = viewBinding.spinnerTarget.text?.toString().orEmpty()
                    .ifBlank { "Manga" },
                detectedTemplate = if (scriptLanguage == ExtensionType.JS) detectedTemplate else null,
            )

            Toast.makeText(this, getString(R.string.ext_create_success, name), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun observeDetectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.detectState.collect { state ->
                    when (state) {
                        is DetectState.Idle -> {
                            viewBinding.btnDetect.isEnabled = true
                            viewBinding.btnDetect.text = getString(R.string.ext_auto_detect_btn)
                            viewBinding.detectProgress.visibility = View.GONE
                        }

                        is DetectState.Detecting -> {
                            viewBinding.btnDetect.isEnabled = false
                            viewBinding.btnDetect.text = getString(R.string.ext_detecting)
                            viewBinding.detectProgress.visibility = View.VISIBLE
                            viewBinding.cardDetectResult.visibility = View.GONE
                        }

                        is DetectState.Detected -> {
                            viewBinding.btnDetect.isEnabled = true
                            viewBinding.btnDetect.text = getString(R.string.ext_auto_detect_btn)
                            viewBinding.detectProgress.visibility = View.GONE

                            detectedTemplate = state.template
                            viewBinding.textDetectCms.text =
                                getString(R.string.ext_detect_cms_label, state.cmsName)
                            viewBinding.textDetectDesc.text = state.description
                            viewBinding.cardDetectResult.visibility = View.VISIBLE
                        }

                        is DetectState.Failed -> {
                            viewBinding.btnDetect.isEnabled = true
                            viewBinding.btnDetect.text = getString(R.string.ext_auto_detect_btn)
                            viewBinding.detectProgress.visibility = View.GONE
                            detectedTemplate = null
                            Toast.makeText(
                                this@CreateExtensionActivity,
                                state.reason,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun onApplyWindowInsets(v: android.view.View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(bottom = bars.bottom)
        return insets
    }
}
