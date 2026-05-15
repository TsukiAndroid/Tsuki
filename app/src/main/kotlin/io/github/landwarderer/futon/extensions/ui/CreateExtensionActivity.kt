package io.github.landwarderer.futon.extensions.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.databinding.ActivityCreateExtensionBinding
import io.github.landwarderer.futon.extensions.domain.ExtensionType

/**
 * Full-screen form for creating a new extension from scratch.
 *
 * Fills in a template source file automatically based on the chosen script language,
 * then saves the extension via [ExtensionsViewModel.createExtension].
 *
 * Mirrors the "Create Extension" screen in Mangayomi.
 */
@AndroidEntryPoint
class CreateExtensionActivity : BaseActivity<ActivityCreateExtensionBinding>() {

    private val viewModel by viewModels<ExtensionsViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityCreateExtensionBinding.inflate(layoutInflater))

        setTitle(R.string.ext_create_extension)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

        setupDropdowns()
        setupCreateButton()
    }

    private fun setupDropdowns() {
        val languageLabels = arrayOf(
            getString(R.string.ext_language_dart),
            getString(R.string.ext_language_js),
        )
        val languageAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            languageLabels,
        )
        viewBinding.spinnerLanguage.setAdapter(languageAdapter)
        viewBinding.spinnerLanguage.setText(languageLabels[0], false)

        val typeLabels = arrayOf(
            getString(R.string.ext_source_type_single),
            getString(R.string.ext_source_type_multi),
            getString(R.string.ext_source_type_torrent),
        )
        val typeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            typeLabels,
        )
        viewBinding.spinnerType.setAdapter(typeAdapter)
        viewBinding.spinnerType.setText(typeLabels[0], false)

        val targetLabels = arrayOf(
            getString(R.string.ext_target_manga),
            getString(R.string.ext_target_anime),
            getString(R.string.ext_target_novel),
        )
        val targetAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            targetLabels,
        )
        viewBinding.spinnerTarget.setAdapter(targetAdapter)
        viewBinding.spinnerTarget.setText(targetLabels[0], false)
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
            )

            Toast.makeText(
                this,
                getString(R.string.ext_create_success, name),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    override fun onApplyWindowInsets(v: android.view.View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(bottom = bars.bottom)
        return insets
    }
}
