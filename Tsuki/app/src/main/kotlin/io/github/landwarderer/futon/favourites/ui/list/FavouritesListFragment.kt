package io.github.landwarderer.futon.favourites.ui.list

import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.viewModels
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.request.target
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.nav.AppRouter
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.ui.list.ListSelectionController
import io.github.landwarderer.futon.core.util.ext.enqueueWith
import io.github.landwarderer.futon.core.util.ext.observe
import io.github.landwarderer.futon.core.util.ext.sortedByOrdinal
import io.github.landwarderer.futon.core.util.ext.withArgs
import io.github.landwarderer.futon.databinding.FragmentListBinding
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

        private var backgroundImageView: ImageView? = null
        private var dimOverlay: View? = null
        private var lastLoadedUrl: String? = null

        val categoryId
                get() = viewModel.categoryId

        override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
                super.onViewBindingCreated(binding, savedInstanceState)
                binding.recyclerView.isVP2BugWorkaroundEnabled = true
                if (settings.isFavouritesBackgroundEnabled) {
                        setupBackgroundImage(binding)
                }
        }

        private fun setupBackgroundImage(binding: FragmentListBinding) {
                val root = binding.root as? FrameLayout ?: return

                val bgImage = ImageView(root.context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0f
                }
                root.addView(bgImage, 0, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                backgroundImageView = bgImage

                val dim = View(root.context).apply {
                        setBackgroundColor(Color.parseColor("#A0000000"))
                        alpha = 0f
                }
                root.addView(dim, 1, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                dimOverlay = dim

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
                        loadBackgroundImage(bgImage, dim, immediateUrl)
                } else {
                        viewModel.content.observe(viewLifecycleOwner) { items ->
                                val url = items.filterIsInstance<MangaListModel>()
                                        .firstOrNull()?.coverUrl
                                if (url != null && url != lastLoadedUrl) {
                                        lastLoadedUrl = url
                                        loadBackgroundImage(bgImage, dim, url)
                                }
                        }
                }
        }

        private fun loadBackgroundImage(bgImage: ImageView, dim: View, url: String) {
                bgImage.alpha = 0f
                dim.alpha = 0f
                ImageRequest.Builder(bgImage.context)
                        .data(url)
                        .crossfade(false)
                        .target(bgImage)
                        .listener(object : ImageRequest.Listener {
                                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                                        ObjectAnimator.ofFloat(bgImage, "alpha", 0f, 0.65f).setDuration(800).start()
                                        ObjectAnimator.ofFloat(dim, "alpha", 0f, 1f).setDuration(800).start()
                                }
                        })
                        .enqueueWith(coil)
        }

        override fun onDestroyView() {
                super.onDestroyView()
                backgroundImageView = null
                dimOverlay = null
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
