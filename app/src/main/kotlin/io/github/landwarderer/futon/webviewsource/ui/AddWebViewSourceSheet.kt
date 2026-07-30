package io.github.landwarderer.futon.webviewsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.databinding.SheetAddWebviewSourceBinding
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AddWebViewSourceSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAddWebviewSourceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AddWebViewSourceViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetAddWebviewSourceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    // Loading indicator
                    binding.progressFetch.isVisible = state.isLoading
                    binding.btnFetch.isEnabled = !state.isLoading

                    // Details section appears once title is populated
                    if (state.title.isNotEmpty()) {
                        binding.layoutDetails.isVisible = true
                        if (binding.etTitle.text.isNullOrEmpty()) {
                            binding.etTitle.setText(state.title)
                        }
                        binding.btnSave.isEnabled = true
                    }

                    // Cover image
                    if (state.coverUrl != null) {
                        binding.ivCover.setImageAsync(state.coverUrl)
                    }

                    // Chapter URL pattern
                    if (state.detectedPattern != null && binding.etPattern.text.isNullOrEmpty()) {
                        binding.etPattern.setText(state.detectedPattern)
                    }

                    // Fetch error
                    if (state.fetchError) {
                        Snackbar.make(
                            binding.root,
                            R.string.webview_source_fetch_error,
                            Snackbar.LENGTH_LONG,
                        ).show()
                    }

                    // Saved — dismiss
                    if (state.saved) {
                        dismissAllowingStateLoss()
                    }
                }
            }
        }

        binding.btnFetch.setOnClickListener {
            val url = binding.etUrl.text?.toString()?.trim() ?: return@setOnClickListener
            if (url.isBlank()) {
                binding.layoutUrl.error = getString(R.string.webview_source_url_required)
                return@setOnClickListener
            }
            binding.layoutUrl.error = null
            viewModel.fetchUrl(url)
        }

        binding.btnSave.setOnClickListener {
            viewModel.save(
                url = binding.etUrl.text?.toString()?.trim().orEmpty(),
                title = binding.etTitle.text?.toString()?.trim().orEmpty(),
                pattern = binding.etPattern.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            )
        }

        binding.btnCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddWebViewSourceSheet"

        fun newInstance(): AddWebViewSourceSheet = AddWebViewSourceSheet()
    }
}
