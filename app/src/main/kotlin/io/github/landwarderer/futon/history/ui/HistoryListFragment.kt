package io.github.landwarderer.futon.history.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.nav.router
import io.github.landwarderer.futon.core.ui.dialog.buildAlertDialog
import io.github.landwarderer.futon.core.ui.list.ListSelectionController
import io.github.landwarderer.futon.core.ui.list.RecyclerScrollKeeper
import io.github.landwarderer.futon.core.ui.util.MenuInvalidator
import io.github.landwarderer.futon.core.util.ext.addMenuProvider
import io.github.landwarderer.futon.core.util.ext.observe
import io.github.landwarderer.futon.databinding.FragmentListBinding
import io.github.landwarderer.futon.list.ui.MangaListFragment
import io.github.landwarderer.futon.list.ui.size.DynamicItemSizeResolver
import io.github.landwarderer.futon.main.ui.BackgroundOwner

@AndroidEntryPoint
class HistoryListFragment : MangaListFragment() {

        override val viewModel by viewModels<HistoryListViewModel>()
        override val isSwipeRefreshEnabled = false

        private var lastLoadedUrl: String? = null

        override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
                super.onViewBindingCreated(binding, savedInstanceState)
                RecyclerScrollKeeper(binding.recyclerView).attach()
                addMenuProvider(HistoryListMenuProvider(binding.root.context, router, viewModel))
                viewModel.isStatsEnabled.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
                if (settings.isHistoryBackgroundEnabled) {
                        setupBackgroundImage()
                }
        }

        private fun setupBackgroundImage() {
                viewModel.backgroundCoverUrl.observe(viewLifecycleOwner) { url ->
                        if (url != null && url != lastLoadedUrl) {
                                lastLoadedUrl = url
                                (activity as? BackgroundOwner)?.setActivityBackground(url)
                        }
                }
        }

        override fun onDestroyView() {
                super.onDestroyView()
                lastLoadedUrl = null
        }

        override fun onScrolledToEnd() = viewModel.requestMoreItems()

        override fun onEmptyActionClick() = viewModel.clearFilter()

        override fun onCreateActionMode(
                controller: ListSelectionController,
                menuInflater: MenuInflater,
                menu: Menu
        ): Boolean {
                menuInflater.inflate(R.menu.mode_history, menu)
                return super.onCreateActionMode(controller, menuInflater, menu)
        }

        override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
                return when (item.itemId) {
                        R.id.action_remove -> {
                                viewModel.removeFromHistory(selectedItemsIds)
                                mode?.finish()
                                true
                        }

                        R.id.action_mark_current -> {
                                val itemsSnapshot = selectedItems
                                buildAlertDialog(context ?: return false, isCentered = true) {
                                        setTitle(item.title)
                                        setIcon(item.icon)
                                        setMessage(R.string.mark_as_completed_prompt)
                                        setNegativeButton(android.R.string.cancel, null)
                                        setPositiveButton(android.R.string.ok) { _, _ ->
                                                viewModel.markAsRead(itemsSnapshot)
                                                mode?.finish()
                                        }
                                }.show()
                                true
                        }

                        else -> super.onActionItemClicked(controller, mode, item)
                }
        }

        override fun onCreateAdapter() = HistoryListAdapter(
                this,
                DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
        )
}
