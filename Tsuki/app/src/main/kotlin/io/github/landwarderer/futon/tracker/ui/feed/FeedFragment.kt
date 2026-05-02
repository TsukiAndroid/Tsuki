package io.github.landwarderer.futon.tracker.ui.feed

import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.request.target
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.drop
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.exceptions.resolve.SnackbarErrorObserver
import io.github.landwarderer.futon.core.nav.router
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.ui.BaseFragment
import io.github.landwarderer.futon.core.ui.list.PaginationScrollListener
import io.github.landwarderer.futon.core.ui.list.RecyclerScrollKeeper
import io.github.landwarderer.futon.core.ui.util.MenuInvalidator
import io.github.landwarderer.futon.core.ui.util.RecyclerViewOwner
import io.github.landwarderer.futon.core.ui.util.ReversibleActionObserver
import io.github.landwarderer.futon.core.ui.widgets.TipView
import io.github.landwarderer.futon.core.util.ext.addMenuProvider
import io.github.landwarderer.futon.core.util.ext.consumeAll
import io.github.landwarderer.futon.core.util.ext.enqueueWith
import io.github.landwarderer.futon.core.util.ext.observe
import io.github.landwarderer.futon.core.util.ext.observeEvent
import io.github.landwarderer.futon.databinding.FragmentListBinding
import io.github.landwarderer.futon.list.domain.ListFilterOption
import io.github.landwarderer.futon.list.ui.adapter.MangaListListener
import io.github.landwarderer.futon.list.ui.adapter.TypedListSpacingDecoration
import io.github.landwarderer.futon.list.ui.model.ListHeader
import io.github.landwarderer.futon.list.ui.model.MangaListModel
import io.github.landwarderer.futon.list.ui.size.StaticItemSizeResolver
import io.github.landwarderer.futon.scrobbling.common.data.ScrobblerStorage
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerService
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import io.github.landwarderer.futon.tracker.ui.feed.adapter.FeedAdapter
import javax.inject.Inject

@AndroidEntryPoint
class FeedFragment :
        BaseFragment<FragmentListBinding>(),
        PaginationScrollListener.Callback,
        RecyclerViewOwner,
        MangaListListener,
        SwipeRefreshLayout.OnRefreshListener {

        @Inject
        lateinit var coil: ImageLoader

        @Inject
        lateinit var settings: AppSettings

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

        private val viewModel by viewModels<FeedViewModel>()

        private var backgroundImageView: ImageView? = null
        private var dimOverlay: View? = null
        private var lastLoadedUrl: String? = null

        override val recyclerView: RecyclerView?
                get() = viewBinding?.recyclerView

        override fun onCreateViewBinding(
                inflater: LayoutInflater,
                container: ViewGroup?,
        ) = FragmentListBinding.inflate(inflater, container, false)

        override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
                super.onViewBindingCreated(binding, savedInstanceState)
                val sizeResolver = StaticItemSizeResolver(resources.getDimensionPixelSize(R.dimen.smaller_grid_width))
                val feedAdapter = FeedAdapter(this, sizeResolver) { item, v ->
                        viewModel.onItemClick(item)
                        router.openDetails(item.toMangaWithOverride())
                }
                with(binding.recyclerView) {
                        val paddingVertical = resources.getDimensionPixelSize(R.dimen.list_spacing_normal)
                        setPadding(0, paddingVertical, 0, paddingVertical)
                        layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
                        adapter = feedAdapter
                        setHasFixedSize(true)
                        addOnScrollListener(PaginationScrollListener(4, this@FeedFragment))
                        addItemDecoration(TypedListSpacingDecoration(context, true))
                        RecyclerScrollKeeper(this).attach()
                }
                binding.swipeRefreshLayout.setOnRefreshListener(this)
                addMenuProvider(FeedMenuProvider(binding.recyclerView, viewModel))

                viewModel.isHeaderEnabled.drop(1).observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
                viewModel.content.observe(viewLifecycleOwner, feedAdapter)
                viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.recyclerView, this))
                viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
                viewModel.isRunning.observe(viewLifecycleOwner, this::onIsTrackerRunningChanged)

                if (settings.isFeedBackgroundEnabled) {
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
                val url = when (source) {
                        AppSettings.HISTORY_BG_ANILIST -> aniListStorage.user?.let { it.coverImage ?: it.avatar }
                        AppSettings.HISTORY_BG_MAL -> malStorage.user?.let { it.coverImage ?: it.avatar }
                        AppSettings.HISTORY_BG_KITSU -> kitsuStorage.user?.let { it.coverImage ?: it.avatar }
                        AppSettings.HISTORY_BG_SHIKIMORI -> shikimoriStorage.user?.let { it.coverImage ?: it.avatar }
                        else -> null
                }

                if (url != null) {
                        lastLoadedUrl = url
                        loadBackgroundImage(bgImage, dim, url)
                } else {
                        viewModel.content.observe(viewLifecycleOwner) { items ->
                                val coverUrl = items.filterIsInstance<MangaListModel>()
                                        .firstOrNull()?.coverUrl
                                if (coverUrl != null && coverUrl != lastLoadedUrl) {
                                        lastLoadedUrl = coverUrl
                                        loadBackgroundImage(bgImage, dim, coverUrl)
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

        override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
                val typeMask = WindowInsetsCompat.Type.systemBars()
                val barsInsets = insets.getInsets(typeMask)
                val paddingVertical = resources.getDimensionPixelSize(R.dimen.list_spacing_normal)
                viewBinding?.recyclerView?.setPadding(
                        left = barsInsets.left,
                        top = paddingVertical,
                        right = barsInsets.right,
                        bottom = barsInsets.bottom + paddingVertical,
                )
                return insets.consumeAll(typeMask)
        }

        override fun onRefresh() {
                viewModel.update()
        }

        override fun onFilterOptionClick(option: ListFilterOption) = viewModel.toggleFilterOption(option)

        override fun onRetryClick(error: Throwable) = Unit

        override fun onFilterClick(view: View?) = Unit

        override fun onEmptyActionClick() = Unit

        override fun onPrimaryButtonClick(tipView: TipView) = Unit

        override fun onSecondaryButtonClick(tipView: TipView) = Unit

        override fun onListHeaderClick(item: ListHeader, view: View) {
                router.openMangaUpdates()
        }

        private fun onIsTrackerRunningChanged(isRunning: Boolean) {
                requireViewBinding().swipeRefreshLayout.isRefreshing = isRunning
        }

        override fun onScrolledToEnd() {
                viewModel.requestMoreItems()
        }

        override fun onItemClick(item: MangaListModel, view: View) {
                router.openDetails(item.toMangaWithOverride())
        }

        override fun onReadClick(manga: Manga, view: View) = Unit

        override fun onTagClick(manga: Manga, tag: MangaTag, view: View) = Unit
}
