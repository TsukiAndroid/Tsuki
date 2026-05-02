package io.github.landwarderer.futon.list.ui.adapter

import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import io.github.landwarderer.futon.core.ui.list.AdapterDelegateClickListenerAdapter
import io.github.landwarderer.futon.core.ui.list.OnListItemClickListener
import io.github.landwarderer.futon.core.util.ext.setTooltipCompat
import io.github.landwarderer.futon.core.util.ext.textAndVisible
import io.github.landwarderer.futon.databinding.ItemMangaListBinding
import io.github.landwarderer.futon.list.ui.model.ListModel
import io.github.landwarderer.futon.list.ui.model.MangaCompactListModel
import io.github.landwarderer.futon.list.ui.model.MangaListModel

fun mangaListItemAD(
        clickListener: OnListItemClickListener<MangaListModel>,
) = adapterDelegateViewBinding<MangaCompactListModel, ListModel, ItemMangaListBinding>(
        { inflater, parent -> ItemMangaListBinding.inflate(inflater, parent, false) },
) {

        AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)

        bind {
                itemView.setTooltipCompat(item.getSummary(context))
                binding.textViewTitle.text = item.title
                binding.textViewSubtitle.textAndVisible = item.subtitle
                val coverAlpha = PreferenceManager.getDefaultSharedPreferences(context)
                        .getInt("cover_alpha", 100) / 100.0f
                binding.imageViewCover.alpha = coverAlpha
                binding.imageViewCover.setImageAsync(item.coverUrl, item.manga)
                binding.badge.number = item.counter
                binding.badge.isVisible = item.counter > 0
        }
}
