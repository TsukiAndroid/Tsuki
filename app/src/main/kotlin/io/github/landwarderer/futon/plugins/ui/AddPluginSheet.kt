package io.github.landwarderer.futon.plugins.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import kotlinx.coroutines.launch

/**
 * Bottom sheet for adding a new plugin.
 *
 * Mode A — file import: pass [ARG_URI] to skip directly to the preview step.
 * Mode B — GitHub import: user enters a repo URL; defaults to UMA (InvalidDavid/UMA).
 */
@AndroidEntryPoint
class AddPluginSheet : BottomSheetDialogFragment() {

    private val viewModel: AddPluginViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_add_plugin, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val inputLayout       = view.findViewById<LinearLayout>(R.id.layout_github_input)
        val etRepo            = view.findViewById<TextInputEditText>(R.id.et_github_repo)
        val tvRepoHelper      = view.findViewById<TextView>(R.id.tv_repo_helper)
        val progressBar       = view.findViewById<ProgressBar>(R.id.progress_download)
        val previewLayout     = view.findViewById<LinearLayout>(R.id.layout_preview)
        val tvPreviewName     = view.findViewById<TextView>(R.id.tv_preview_name)
        val tvPreviewVersion  = view.findViewById<TextView>(R.id.tv_preview_version)
        val tvPreviewAuthor   = view.findViewById<TextView>(R.id.tv_preview_author)
        val tvPreviewSources  = view.findViewById<TextView>(R.id.tv_preview_sources)
        val btnAction         = view.findViewById<Button>(R.id.btn_install)
        val btnCancel         = view.findViewById<Button>(R.id.btn_cancel)

        // Pre-fill GitHub repo with UMA default
        val prefilledRepo = arguments?.getString(ARG_REPO) ?: UMA_DEFAULT_REPO
        etRepo.setText(prefilledRepo)
        tvRepoHelper.text = getString(R.string.plugin_uma_helper)

        // If a file URI was passed, go straight to preview
        val uriArg = arguments?.getString(ARG_URI)
        if (uriArg != null) {
            inputLayout.isVisible = false
            viewModel.previewFromUri(Uri.parse(uriArg))
        }

        // Observe UI state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is AddPluginUiState.Idle -> {
                        progressBar.isVisible  = false
                        previewLayout.isVisible = false
                        inputLayout.isVisible  = uriArg == null
                        btnAction.text         = getString(R.string.plugin_fetch_info)
                        btnAction.isEnabled    = true
                    }
                    is AddPluginUiState.Loading -> {
                        progressBar.isVisible  = true
                        previewLayout.isVisible = false
                        btnAction.isEnabled    = false
                    }
                    is AddPluginUiState.Preview -> {
                        progressBar.isVisible  = false
                        inputLayout.isVisible  = false
                        previewLayout.isVisible = true
                        val meta = state.loaded.metadata
                        tvPreviewName.text    = meta.name
                        tvPreviewVersion.text = getString(R.string.version_format, meta.version)
                        tvPreviewAuthor.text  = getString(R.string.plugin_author, meta.author)
                        tvPreviewSources.text = resources.getQuantityString(
                            R.plurals.plugin_sources_count,
                            meta.sourceCount,
                            meta.sourceCount,
                        )
                        btnAction.text      = getString(R.string.plugin_install)
                        btnAction.isEnabled = true
                    }
                    is AddPluginUiState.Error -> {
                        progressBar.isVisible = false
                        btnAction.isEnabled   = true
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        viewModel.reset()
                    }
                    is AddPluginUiState.Success -> {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.plugin_installed_success),
                            Toast.LENGTH_SHORT,
                        ).show()
                        dismissAllowingStateLoss()
                    }
                }
            }
        }

        // Progress bar for download
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloadProgress.collect { progress ->
                progressBar.progress = (progress * 100).toInt()
            }
        }

        btnAction.setOnClickListener {
            when (viewModel.uiState.value) {
                is AddPluginUiState.Preview -> viewModel.confirmInstall()
                else -> {
                    val repo = etRepo.text?.toString()?.trim() ?: return@setOnClickListener
                    if (repo.isBlank()) {
                        etRepo.error = getString(R.string.plugin_repo_required)
                        return@setOnClickListener
                    }
                    viewModel.previewFromGithub(repo)
                }
            }
        }

        btnCancel.setOnClickListener {
            viewModel.reset()
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.reset()
    }

    companion object {
        const val TAG = "AddPluginSheet"
        private const val ARG_URI  = "uri"
        private const val ARG_REPO = "repo"
        const val UMA_DEFAULT_REPO = "InvalidDavid/UMA"

        fun newInstance(
            initialUri: String? = null,
            prefilledRepo: String? = null,
        ): AddPluginSheet = AddPluginSheet().apply {
            arguments = bundleOf(
                ARG_URI  to initialUri,
                ARG_REPO to (prefilledRepo ?: UMA_DEFAULT_REPO),
            )
        }
    }
}
