package io.github.landwarderer.futon.webviewsource.ui.anilist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.databinding.SheetLinkAnilistBinding
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerManga
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerService
import kotlinx.coroutines.launch

/**
 * Bottom sheet that lets the user search AniList and link a result to a
 * WebView source.
 *
 * Launch via [newInstance]; the source ID and name are passed as arguments.
 */
@AndroidEntryPoint
class LinkAniListSheet : BottomSheetDialogFragment() {

    private var _binding: SheetLinkAnilistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LinkAniListViewModel by viewModels()
    private var adapter: AniListSearchAdapter? = null

    private val sourceId: Long by lazy { requireArguments().getLong(ARG_SOURCE_ID) }
    private val sourceName: String by lazy { requireArguments().getString(ARG_SOURCE_NAME, "") }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetLinkAnilistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // If user is not logged in, show a notice; search still works
        binding.tvLoginNotice.isVisible = !viewModel.isLoggedIn

        // Adapter
        adapter = AniListSearchAdapter { media -> showConfirmationDialog(media) }
        binding.recyclerResults.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerResults.adapter = adapter

        // Search button / keyboard action
        binding.btnSearch.setOnClickListener { triggerSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch()
                true
            } else {
                false
            }
        }

        // Observe state
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.results.collect { results ->
                        adapter?.submitList(results)
                        binding.tvNoResults.isVisible =
                            results.isEmpty() && !viewModel.isLoading.value
                    }
                }
                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressSearch.isVisible = loading
                    }
                }
                launch {
                    viewModel.linked.collect { linked ->
                        if (linked) dismissAllowingStateLoss()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter = null
        _binding = null
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun triggerSearch() {
        val query = binding.etSearch.text?.toString().orEmpty().trim()
        if (query.isNotBlank()) viewModel.search(query)
    }

    private fun showConfirmationDialog(media: ScrobblerManga) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.link_anilist_confirm_title))
            .setMessage(
                getString(
                    R.string.link_anilist_confirm_message,
                    media.name,
                    sourceName,
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.link(sourceId, media)
            }
            .show()
    }

    companion object {
        const val TAG = "LinkAniListSheet"
        private const val ARG_SOURCE_ID = "source_id"
        private const val ARG_SOURCE_NAME = "source_name"

        fun newInstance(sourceId: Long, sourceName: String): LinkAniListSheet =
            LinkAniListSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_SOURCE_ID, sourceId)
                    putString(ARG_SOURCE_NAME, sourceName)
                }
            }
    }
}
