package io.github.landwarderer.futon.webviewsource.ui.anilist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.landwarderer.futon.databinding.ItemAnilistResultBinding
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerManga

class AniListSearchAdapter(
    private val onItemClick: (ScrobblerManga) -> Unit,
) : ListAdapter<ScrobblerManga, AniListSearchAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(private val binding: ItemAnilistResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ScrobblerManga) {
            binding.tvTitle.text = item.name
            binding.tvAltTitle.text = item.altName ?: ""
            binding.ivCover.setImageAsync(item.cover)
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemAnilistResultBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private object DiffCallback : DiffUtil.ItemCallback<ScrobblerManga>() {
        override fun areItemsTheSame(a: ScrobblerManga, b: ScrobblerManga) = a.id == b.id
        override fun areContentsTheSame(a: ScrobblerManga, b: ScrobblerManga) = a == b
    }
}
