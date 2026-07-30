package io.github.landwarderer.futon.webviewsource.ui.list

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import io.github.landwarderer.futon.core.ui.BaseFragment
import io.github.landwarderer.futon.core.ui.dialog.buildAlertDialog
import io.github.landwarderer.futon.core.ui.dialog.setEditText
import io.github.landwarderer.futon.databinding.FragmentWebviewSourceListBinding
import io.github.landwarderer.futon.webviewsource.ui.anilist.LinkAniListSheet
import io.github.landwarderer.futon.webviewsource.ui.reader.WebViewReaderActivity
import kotlinx.coroutines.launch

@AndroidEntryPoint
class WebViewSourceListFragment : BaseFragment<FragmentWebviewSourceListBinding>() {

    private val viewModel: WebViewSourceListViewModel by viewModels()
    private lateinit var adapter: WebViewSourceAdapter

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ) = FragmentWebviewSourceListBinding.inflate(inflater, container, false)

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        viewBinding?.recyclerView?.updatePadding(bottom = bars.bottom + v.paddingBottom)
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
            .build()
    }

    override fun onViewBindingCreated(
        binding: FragmentWebviewSourceListBinding,
        savedInstanceState: Bundle?,
    ) {
        adapter = WebViewSourceAdapter(
            onItemClick = { source ->
                startActivity(
                    WebViewReaderActivity.createIntent(requireContext(), source.id),
                )
            },
            onItemLongClick = { source ->
                showContextMenu(source)
                true
            },
        )

        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sources.collect { sources ->
                    adapter.submitList(sources)
                    binding.emptyView.isVisible = sources.isEmpty()
                }
            }
        }
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun showContextMenu(source: WebViewSourceEntity) {
        val ctx = requireContext()
        val options = arrayOf(
            getString(R.string.edit_title),
            getString(R.string.edit_chapter_pattern),
            getString(R.string.action_link_anilist),
            getString(R.string.action_custom_css),
            getString(R.string.delete),
        )
        buildAlertDialog(ctx) {
            setTitle(source.title)
            setItems(options) { _, which ->
                when (which) {
                    0 -> showEditTitleDialog(source)
                    1 -> showEditPatternDialog(source)
                    2 -> showLinkAniListSheet(source)
                    3 -> showCustomCssDialog(source)
                    4 -> showDeleteConfirmation(source)
                }
            }
        }.show()
    }

    private fun showLinkAniListSheet(source: WebViewSourceEntity) {
        LinkAniListSheet.newInstance(source.id, source.title)
            .show(parentFragmentManager, LinkAniListSheet.TAG)
    }

    private fun showEditTitleDialog(source: WebViewSourceEntity) {
        val ctx = requireContext()
        val dialog = buildAlertDialog(ctx, isCentered = true) {
            setTitle(getString(R.string.edit_title))
            val et = setEditText(InputType.TYPE_CLASS_TEXT, singleLine = true)
            et.setText(source.title)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                val newTitle = et.text?.toString().orEmpty()
                if (newTitle.isNotBlank()) {
                    viewModel.updateTitle(source, newTitle)
                }
            }
        }
        dialog.show()
    }

    private fun showEditPatternDialog(source: WebViewSourceEntity) {
        val ctx = requireContext()
        val dialog = buildAlertDialog(ctx, isCentered = true) {
            setTitle(getString(R.string.edit_chapter_pattern))
            setMessage(getString(R.string.chapter_pattern_hint))
            val et = setEditText(InputType.TYPE_CLASS_TEXT, singleLine = true)
            et.setText(source.chapterUrlPattern.orEmpty())
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                val pattern = et.text?.toString().orEmpty()
                viewModel.updatePattern(source, pattern)
            }
        }
        dialog.show()
    }

    private fun showCustomCssDialog(source: WebViewSourceEntity) {
        val ctx = requireContext()
        val dialog = buildAlertDialog(ctx, isCentered = true) {
            setTitle(getString(R.string.action_custom_css))
            setMessage(getString(R.string.custom_css_hint))
            val et = setEditText(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                singleLine = false,
            )
            et.setText(source.customCss.orEmpty())
            et.hint = "/* Example: div.chapter-warning { display: none !important; } */"
            et.minLines = 5
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                val css = et.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() }
                viewModel.updateCustomCss(source, css)
            }
        }
        dialog.show()
    }

    private fun showDeleteConfirmation(source: WebViewSourceEntity) {
        buildAlertDialog(requireContext(), isCentered = true) {
            setTitle(getString(R.string.delete))
            setMessage(getString(R.string.delete_source_confirm, source.title))
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.delete(source.id)
            }
        }.show()
    }

    companion object {
        fun newInstance() = WebViewSourceListFragment()
    }
}
