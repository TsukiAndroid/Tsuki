package io.github.landwarderer.futon.extensions.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.extensions.data.ExtensionRepo

class ExtensionRepoAdapter(
    private val onRemove: (repo: ExtensionRepo) -> Unit,
) : ListAdapter<ExtensionRepo, ExtensionRepoAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ext_repo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView? = view.findViewById(R.id.text_repo_name)
        private val url: TextView? = view.findViewById(R.id.text_repo_url)
        private val btnRemove: ImageButton? = view.findViewById(R.id.btn_remove_repo)

        fun bind(repo: ExtensionRepo) {
            name?.text = repo.name
            url?.text = repo.indexUrl
            btnRemove?.setOnClickListener { onRemove(repo) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ExtensionRepo>() {
        override fun areItemsTheSame(oldItem: ExtensionRepo, newItem: ExtensionRepo) =
            oldItem.indexUrl == newItem.indexUrl

        override fun areContentsTheSame(oldItem: ExtensionRepo, newItem: ExtensionRepo) =
            oldItem == newItem
    }
}
