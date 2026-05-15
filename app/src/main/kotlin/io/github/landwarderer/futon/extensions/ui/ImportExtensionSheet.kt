package io.github.landwarderer.futon.extensions.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.extensions.domain.ExtensionType

/**
 * Bottom sheet for importing a local .js or .dart extension file.
 *
 * Follows the same UI pattern as ImportParserSheet from the Custom Sources system.
 * Does NOT modify CustomSourcesRepository or any existing class.
 */
@AndroidEntryPoint
class ImportExtensionSheet : BottomSheetDialogFragment() {

    private val viewModel: ExtensionsViewModel by viewModels(
        ownerProducer = { requireActivity() },
    )

    private var selectedFileName: TextView? = null
    private var selectedFileContent: String? = null

    private lateinit var pickFileLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickFileLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val content = requireContext().contentResolver
                    .openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: return@registerForActivityResult
                val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "extension"
                selectedFileContent = content
                selectedFileName?.text = displayName
            } catch (_: Exception) {
                Toast.makeText(requireContext(), getString(R.string.ext_install_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_import_extension, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectedFileName = view.findViewById(R.id.text_selected_file)

        val editName = view.findViewById<TextInputEditText>(R.id.edit_ext_name)
        val editBaseUrl = view.findViewById<TextInputEditText>(R.id.edit_ext_base_url)
        val editVersion = view.findViewById<TextInputEditText>(R.id.edit_ext_version)
        val editAuthor = view.findViewById<TextInputEditText>(R.id.edit_ext_author)
        val spinnerType = view.findViewById<AutoCompleteTextView>(R.id.spinner_ext_type)
        val btnPick = view.findViewById<MaterialButton>(R.id.btn_pick_ext_file)
        val btnImport = view.findViewById<MaterialButton>(R.id.btn_import_ext)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_cancel_ext)
        val errorView = view.findViewById<TextView>(R.id.text_ext_import_error)

        val typeLabels = arrayOf("JavaScript (.js)", "Dart (.dart)")
        val typeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, typeLabels)
        spinnerType?.setAdapter(typeAdapter)
        spinnerType?.setText(typeLabels[0], false)

        btnPick?.setOnClickListener {
            pickFileLauncher.launch(arrayOf("application/javascript", "text/javascript", "text/x-dart", "*/*"))
        }

        btnImport?.setOnClickListener {
            val content = selectedFileContent
            val name = editName?.text?.toString()?.trim()
            val baseUrl = editBaseUrl?.text?.toString()?.trim()

            if (content == null) {
                errorView?.isVisible = true
                errorView?.text = getString(R.string.ext_no_file)
                return@setOnClickListener
            }
            if (name.isNullOrEmpty()) {
                errorView?.isVisible = true
                errorView?.text = getString(R.string.ext_name_hint)
                return@setOnClickListener
            }
            errorView?.isVisible = false

            val typeIndex = typeLabels.indexOf(spinnerType?.text?.toString())
            val type = if (typeIndex == 1) ExtensionType.DART else ExtensionType.JS

            viewModel.installFromCode(
                name = name,
                version = editVersion?.text?.toString()?.trim()?.ifEmpty { "1.0.0" } ?: "1.0.0",
                author = editAuthor?.text?.toString()?.trim() ?: "",
                description = "",
                baseUrl = baseUrl ?: "",
                language = "en",
                type = type,
                sourceCode = content,
            )
            Toast.makeText(requireContext(), getString(R.string.ext_install_success, name), Toast.LENGTH_SHORT).show()
            dismiss()
        }

        btnCancel?.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        selectedFileName = null
    }

    companion object {
        const val TAG = "ImportExtensionSheet"
        fun newInstance() = ImportExtensionSheet()
    }
}
