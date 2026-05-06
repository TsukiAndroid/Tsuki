package io.github.landwarderer.futon.settings.about

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.util.ext.observe
import io.github.landwarderer.futon.databinding.FragmentWhatsNewBinding

/**
 * Dialog shown once on the first launch after the app is updated.
 *
 * It fetches the GitHub release notes for the currently installed version and renders them
 * with Markwon.  Triggered by [MainViewModel.onShowWhatsNew], which checks
 * [AppUpdateRepository.shouldShowWhatsNew] on every cold start.
 */
@AndroidEntryPoint
class WhatsNewSheet : DialogFragment() {

        private val viewModel: WhatsNewViewModel by viewModels()
        private var _binding: FragmentWhatsNewBinding? = null
        private val binding get() = requireNotNull(_binding)

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
                _binding = FragmentWhatsNewBinding.inflate(layoutInflater)
                val markwon = Markwon.create(requireContext())

                viewModel.isLoading.observe(this) { loading ->
                        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                }

                viewModel.releaseNotes
                        .filterNotNull()
                        .flowOn(Dispatchers.IO)
                        .observe(this) { notes ->
                                if (notes.isNotBlank()) {
                                        markwon.setParsedMarkdown(
                                                binding.textViewContent,
                                                markwon.toMarkdown(notes),
                                        )
                                } else {
                                        binding.textViewContent.setText(R.string.no_release_notes)
                                }
                        }

                return MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.whats_new_in, viewModel.versionName))
                        .setView(binding.root)
                        .setPositiveButton(R.string.whats_new_close) { _, _ -> dismiss() }
                        .create()
        }

        override fun onDestroyView() {
                super.onDestroyView()
                _binding = null
        }

        companion object {
                private const val TAG = "WhatsNewSheet"

                fun show(fm: FragmentManager) {
                        if (fm.findFragmentByTag(TAG) == null) {
                                WhatsNewSheet().show(fm, TAG)
                        }
                }
        }
}
