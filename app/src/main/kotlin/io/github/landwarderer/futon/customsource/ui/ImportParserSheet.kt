package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R

/**
 * Bottom sheet that lets the user pick a `.json` parser template file,
 * validates it, and saves it via [ParserTemplateViewModel].
 *
 * Styled consistently with [AddCustomSourceSheet].
 */
@AndroidEntryPoint
class ImportParserSheet : BottomSheetDialogFragment() {

    private val viewModel: ParserTemplateViewModel by viewModels()

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
                val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "template.json"
                selectedFileContent = content
                selectedFileName?.text = displayName
            } catch (_: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.template_import_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_import_parser, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectedFileName = view.findViewById(R.id.text_selected_file)

        val btnPick   = view.findViewById<MaterialButton>(R.id.btn_pick_file)
        val btnImport = view.findViewById<MaterialButton>(R.id.btn_import_parser)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_cancel_import)
        val errorView = view.findViewById<TextView>(R.id.text_import_error)

        btnPick.setOnClickListener {
            pickFileLauncher.launch(arrayOf("application/json", "*/*"))
        }

        btnImport.setOnClickListener {
            val content = selectedFileContent
            if (content == null) {
                errorView.isVisible = true
                errorView.text = getString(R.string.no_file_selected_error)
                return@setOnClickListener
            }
            errorView.isVisible = false
            viewModel.importTemplate(content)
        }

        btnCancel.setOnClickListener { dismiss() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.importState.collect { state ->
                when (state) {
                    is ParserTemplateViewModel.ImportState.Error -> {
                        errorView.isVisible = true
                        errorView.text = state.message
                        viewModel.resetState()
                    }

                    is ParserTemplateViewModel.ImportState.Success -> {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.template_imported, state.template.name),
                            Toast.LENGTH_SHORT,
                        ).show()
                        dismiss()
                        viewModel.resetState()
                    }

                    is ParserTemplateViewModel.ImportState.Idle -> {
                        /* nothing */
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        selectedFileName = null
    }

    companion object {
        const val TAG = "ImportParserSheet"
        fun newInstance() = ImportParserSheet()
    }
}
