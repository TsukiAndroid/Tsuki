package io.github.landwarderer.futon.webviewsource.ui.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import io.github.landwarderer.futon.databinding.ItemWebviewSourceBinding

class WebViewSourceAdapter(
    private val onItemClick: (WebViewSourceEntity) -> Unit,
    private val onItemLongClick: (WebViewSourceEntity) -> Boolean,
) : ListAdapter<WebViewSourceEntity, WebViewSourceAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(private val binding: ItemWebviewSourceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: WebViewSourceEntity) {
            binding.tvTitle.text = item.title

            // Chapter label
            val chapter = item.lastReadChapter
            binding.tvChapter.text = if (chapter != null) {
                "Chapter ${chapter.toDisplayString()}"
            } else {
                "Not started"
            }

            // Progress bar (0–100)
            binding.progressBar.progress = (item.lastReadScrollPercent * 100).toInt()

            // "N chapters behind" badge
            val behind = chaptersBehinд(item)
            binding.tvBehind.isVisible = behind > 0
            binding.tvBehind.text = "$behind chapter${if (behind > 1) "s" else ""} behind"

            // Cover image via CoilImageView
            binding.ivCover.setImageAsync(item.coverUrl)

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onItemLongClick(item) }
        }

        private fun chaptersBehinд(item: WebViewSourceEntity): Int {
            val latest = item.latestKnownChapter ?: return 0
            val current = item.lastReadChapter ?: return 0
            return (latest - current).toInt().coerceAtLeast(0)
        }

        private fun Float.toDisplayString(): String =
            if (this == toLong().toFloat()) toLong().toString() else toString()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemWebviewSourceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    private object DiffCallback : DiffUtil.ItemCallback<WebViewSourceEntity>() {
        override fun areItemsTheSame(a: WebViewSourceEntity, b: WebViewSourceEntity) =
            a.id == b.id

        override fun areContentsTheSame(a: WebViewSourceEntity, b: WebViewSourceEntity) =
            a == b
    }
}
