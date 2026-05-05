package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.domain.CustomSourceType

@AndroidEntryPoint
class AddCustomSourceSheet : BottomSheetDialogFragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_add_custom_source, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val nameInput = view.findViewById<TextInputEditText>(R.id.input_source_name)
        val urlInput = view.findViewById<TextInputEditText>(R.id.input_source_url)
        val urlLayout = view.findViewById<TextInputLayout>(R.id.layout_source_url)
        val descInput = view.findViewById<TextInputEditText>(R.id.input_source_description)
        val typeDropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_source_type)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btn_add_source)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_cancel)

        val types = CustomSourceType.entries.map { it.label }
        val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_simple, types)
        typeDropdown.setAdapter(adapter)
        typeDropdown.setText(CustomSourceType.WEBVIEW.label, false)

        typeDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedType = CustomSourceType.entries[position]
            urlLayout.hint = when (selectedType) {
                CustomSourceType.MANGADEX_COMPATIBLE -> "API base URL (e.g. https://api.mangadex.org)"
                CustomSourceType.MADARA -> "Site base URL (e.g. https://mangakakalot.com)"
                CustomSourceType.GENKAN -> "Site base URL (e.g. https://leviatanscans.com)"
                CustomSourceType.WEBVIEW -> "Website URL (e.g. https://example.com)"
            }
        }

        btnAdd.setOnClickListener {
            val name = nameInput.text?.toString().orEmpty()
            val url = urlInput.text?.toString().orEmpty()
            val desc = descInput.text?.toString().orEmpty()
            val typeLabel = typeDropdown.text?.toString().orEmpty()
            val type = CustomSourceType.entries.find { it.label == typeLabel }
                ?: CustomSourceType.WEBVIEW

            urlLayout.error = null
            viewModel.addSource(name, url, type, desc)
        }

        btnCancel.setOnClickListener { dismiss() }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is CustomSourceViewModel.UiState.Error -> {
                        urlLayout.error = state.message
                        viewModel.resetState()
                    }
                    is CustomSourceViewModel.UiState.SourceAdded -> {
                        dismiss()
                        viewModel.resetState()
                    }
                    CustomSourceViewModel.UiState.Idle -> { /* nothing */ }
                }
            }
        }
    }

    companion object {
        const val TAG = "AddCustomSourceSheet"
        fun newInstance() = AddCustomSourceSheet()
    }
}
