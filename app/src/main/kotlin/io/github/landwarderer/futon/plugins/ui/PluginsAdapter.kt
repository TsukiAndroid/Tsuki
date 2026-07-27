package io.github.landwarderer.futon.plugins.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.plugins.domain.Plugin

/**
 * RecyclerView adapter for the list of installed plugins.
 */
class PluginsAdapter(
    private val onToggle: (Plugin, Boolean) -> Unit,
    private val onUpdate: (Plugin) -> Unit,
    private val onDelete: (Plugin) -> Unit,
) : ListAdapter<Plugin, PluginsAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_plugin, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView     = itemView.findViewById(R.id.tv_plugin_name)
        private val tvVersion: TextView  = itemView.findViewById(R.id.tv_plugin_version)
        private val tvAuthor: TextView   = itemView.findViewById(R.id.tv_plugin_author)
        private val tvSources: TextView  = itemView.findViewById(R.id.tv_plugin_sources)
        private val swEnabled: Switch    = itemView.findViewById(R.id.sw_plugin_enabled)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btn_plugin_more)

        fun bind(plugin: Plugin) {
            tvName.text = plugin.name
            tvVersion.text = itemView.context.getString(R.string.version_format, plugin.version)
            tvAuthor.text = itemView.context.getString(R.string.plugin_author, plugin.author)
            tvSources.text = itemView.context.resources.getQuantityString(
                R.plurals.plugin_sources_count,
                plugin.sourceCount,
                plugin.sourceCount,
            )

            // Prevent listener from firing during rebind
            swEnabled.setOnCheckedChangeListener(null)
            swEnabled.isChecked = plugin.isEnabled
            swEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(plugin, checked)
            }

            btnMore.setOnClickListener { showPopupMenu(it, plugin) }
        }

        private fun showPopupMenu(anchor: View, plugin: Plugin) {
            val popup = PopupMenu(anchor.context, anchor)
            popup.inflate(R.menu.opt_plugin_item)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_plugin_update -> { onUpdate(plugin); true }
                    R.id.action_plugin_delete -> { onDelete(plugin); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Plugin>() {
            override fun areItemsTheSame(a: Plugin, b: Plugin) = a.id == b.id
            override fun areContentsTheSame(a: Plugin, b: Plugin) = a == b
        }
    }
}
