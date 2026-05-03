package io.github.landwarderer.futon.favourites.ui.list

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.nav.AppRouter
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.ui.list.ListSelectionController
import io.github.landwarderer.futon.core.util.ext.observe
import io.github.landwarderer.futon.core.util.ext.sortedByOrdinal
import io.github.landwarderer.futon.core.util.ext.withArgs
import io.github.landwarderer.futon.databinding.FragmentListBinding
import io.github.landwarderer.futon.main.ui.BackgroundOwner
import io.github.landwarderer.futon.list.domain.ListSortOrder
import io.github.landwarderer.futon.list.ui.MangaListFragment
import io.github.landwarderer.futon.list.ui.model.MangaListModel
import io.github.landwarderer.futon.scrobbling.common.data.ScrobblerStorage
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerService
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerType
import javax.inject.Inject

@AndroidEntryPoint
class FavouritesListFragment : MangaListFragment(), PopupMenu.OnMenuItemClickListener {

        override val viewModel by viewModels<FavouritesListViewModel>()

        override val isSwipeRefreshEnabled = false

        @Inject
        @ScrobblerType(ScrobblerService.ANILIST)
        lateinit var aniListStorage: ScrobblerStorage

        @Inject
        @ScrobblerType(ScrobblerService.MAL)
        lateinit var malStorage: ScrobblerStorage

        @Inject
        @ScrobblerType(ScrobblerService.KITSU)
        lateinit var kitsuStorage: ScrobblerStorage

        @Inject
        @ScrobblerType(ScrobblerService.SHIKIMORI)
        lateinit var shikimoriStorage: ScrobblerStorage

        private var lastLoadedUrl: String? = null

        val categoryId
                get() = viewModel.categoryId

        override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
                super.onViewBindingCreated(binding, savedInstanceState)
                binding.recyclerView.isVP2BugWorkaroundEnabled = true
                if (settings.isFavouritesBackgroundEnabled) {
                        setupBackgroundImage()
                }
        }

        private fun setupBackgroundImage() {
                val source = settings.historyBackgroundSource
                val immediateUrl = when (source) {
                        AppSettings.HISTORY_BG_ANILIST -> aniListStorage.user?.let { it.coverImage ?: it.avatar }
                        AppSettings.HISTORY_BG_MAL -> malStorage.user?.let { it.coverImage ?: it.avatar }
                        AppSettings.HISTORY_BG_KITSU -> kitsuStorage.user?.let { it.coverImage ?: it.avatar }
                        AppSettings.HISTORY_BG_SHIKIMORI -> shikimoriStorage.user?.let { it.coverImage ?: it.avatar }
                        else -> null
                }

                if (immediateUrl != null) {
                        lastLoadedUrl = immediateUrl
                        (activity as? BackgroundOwner)?.setActivityBackground(immediateUrl)
                } else {
                        viewModel.content.observe(viewLifecycleOwner) { items ->
                                val url = items.filterIsInstance<MangaListModel>()
                                        .firstOrNull()?.coverUrl
                                if (url != null && url != lastLoadedUrl) {
                                        lastLoadedUrl = url
                                        (activity as? BackgroundOwner)?.setActivityBackground(url)
                                }
                        }
                }
        }

        override fun onDestroyView() {
                super.onDestroyView()
                lastLoadedUrl = null
        }

        override fun onScrolledToEnd() = viewModel.requestMoreItems()

        override fun onEmptyActionClick() = viewModel.clearFilter()

        override fun onFilterClick(view: View?) {
                val menu = PopupMenu(view?.context ?: return, view)
                menu.setOnMenuItemClickListener(this)
                val orders = ListSortOrder.FAVORITES.sortedByOrdinal()
                for ((i, item) in orders.withIndex()) {
                        menu.menu.add(Menu.NONE, Menu.NONE, i, item.titleResId)
                }
                menu.show()
        }

        override fun onMenuItemClick(item: MenuItem): Boolean {
                val order = ListSortOrder.FAVORITES.sortedByOrdinal().getOrNull(item.order) ?: return false
                viewModel.setSortOrder(order)
                return true
        }

        override fun onCreateActionMode(
                controller: ListSelectionController,
                menuInflater: MenuInflater,
                menu: Menu
        ): Boolean {
                menuInflater.inflate(R.menu.mode_favourites, menu)
                return super.onCreateActionMode(controller, menuInflater, menu)
        }

        override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
                return when (item.itemId) {
                        R.id.action_remove -> {
                                viewModel.removeFromFavourites(selectedItemsIds)
                                mode?.finish()
                                true
                        }

                        R.id.action_mark_current -> {
                                val itemsSnapshot = selectedItems
                                MaterialAlertDialogBuilder(context ?: return false)
                                        .setTitle(item.title)
                                        .setMessage(R.string.mark_as_completed_prompt)
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .setPositiveButton(android.R.string.ok) { _, _ ->
                                                viewModel.markAsRead(itemsSnapshot)
                                                mode?.finish()
                                        }.show()
                                true
                        }

                        else -> super.onActionItemClicked(controller, mode, item)
                }
        }

        companion object {

                const val NO_ID = 0L

                fun newInstance(categoryId: Long) = FavouritesListFragment().withArgs(1) {
                        putLong(AppRouter.KEY_ID, categoryId)
                }
        }
}
