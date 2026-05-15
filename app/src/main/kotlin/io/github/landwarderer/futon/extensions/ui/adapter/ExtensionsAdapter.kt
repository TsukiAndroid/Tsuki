package io.github.landwarderer.futon.extensions.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.extensions.data.AvailableExtension
import io.github.landwarderer.futon.extensions.domain.Extension
import io.github.landwarderer.futon.extensions.domain.ExtensionType

/**
 * Displays installed extensions and available (remote repo) extensions in a single list.
 *
 * Section headers separate INSTALLED vs AVAILABLE entries.
 * Uses dedicated layouts (item_ext_installed, item_ext_available) that are completely
 * separate from the existing Mihon extension downloader item_extension.xml.
 */
class ExtensionsAdapter(
    private val onEnableToggle: (id: String, enabled: Boolean) -> Unit,
    private val onDelete: (id: String) -> Unit,
    private val onInstall: (available: AvailableExtension) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var installed: List<Extension> = emptyList()
    private var available: List<AvailableExtension> = emptyList()

    private sealed class Item {
        data class Header(val title: String) : Item()
        data class Installed(val extension: Extension) : Item()
        data class Available(val extension: AvailableExtension) : Item()
    }

    private var items: List<Item> = emptyList()

    fun setInstalled(list: List<Extension>) {
        installed = list
        rebuildItems()
    }

    fun setAvailable(list: List<AvailableExtension>) {
        val installedKeys = installed.map { "${it.name.lowercase()}|${it.baseUrl}" }.toSet()
        available = list.filter { "${it.name.lowercase()}|${it.baseUrl}" !in installedKeys }
        rebuildItems()
    }

    private fun rebuildItems() {
        val newItems = mutableListOf<Item>()
        if (installed.isNotEmpty()) {
            newItems.add(Item.Header("Installed (${installed.size})"))
            installed.forEach { newItems.add(Item.Installed(it)) }
        }
        if (available.isNotEmpty()) {
            newItems.add(Item.Header("Available (${available.size})"))
            available.forEach { newItems.add(Item.Available(it)) }
        }
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Header -> VIEW_TYPE_HEADER
        is Item.Installed -> VIEW_TYPE_INSTALLED
        is Item.Available -> VIEW_TYPE_AVAILABLE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_header, parent, false))
            VIEW_TYPE_INSTALLED -> InstalledVH(inflater.inflate(R.layout.item_ext_installed, parent, false))
            else -> AvailableVH(inflater.inflate(R.layout.item_ext_available, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderVH).bind(item.title)
            is Item.Installed -> (holder as InstalledVH).bind(item.extension)
            is Item.Available -> (holder as AvailableVH).bind(item.extension)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView? = view.findViewById(R.id.textView_title)
        fun bind(text: String) { title?.text = text }
    }

    inner class InstalledVH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView? = view.findViewById(R.id.text_ext_name)
        private val meta: TextView? = view.findViewById(R.id.text_ext_meta)
        private val typeLabel: TextView? = view.findViewById(R.id.text_ext_type)
        private val toggle: Switch? = view.findViewById(R.id.switch_ext_enabled)
        private val btnDelete: ImageButton? = view.findViewById(R.id.btn_ext_delete)

        fun bind(ext: Extension) {
            name?.text = ext.name
            meta?.text = buildMeta(ext.version, ext.author, ext.baseUrl)
            typeLabel?.text = ext.type.label()
            toggle?.setOnCheckedChangeListener(null)
            toggle?.isChecked = ext.isEnabled
            toggle?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                onEnableToggle(ext.id, checked)
            }
            btnDelete?.setOnClickListener { onDelete(ext.id) }
        }
    }

    inner class AvailableVH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView? = view.findViewById(R.id.text_ext_name)
        private val meta: TextView? = view.findViewById(R.id.text_ext_meta)
        private val typeLabel: TextView? = view.findViewById(R.id.text_ext_type)
        private val btnInstall: View? = view.findViewById(R.id.btn_install_ext)

        fun bind(ext: AvailableExtension) {
            name?.text = ext.name
            meta?.text = buildMeta(ext.version, ext.author, ext.baseUrl)
            typeLabel?.text = ext.type.label()
            btnInstall?.setOnClickListener { onInstall(ext) }
        }
    }

    private fun buildMeta(version: String, author: String, baseUrl: String): String =
        listOfNotNull(
            version.ifEmpty { null },
            author.ifEmpty { baseUrl.ifEmpty { null } },
        ).joinToString(" · ")

    private fun ExtensionType.label(): String = when (this) {
        ExtensionType.JS -> "JS"
        ExtensionType.DART -> "Dart"
        ExtensionType.MIHON_APK -> "Mihon"
        ExtensionType.JSON_TEMPLATE -> "Template"
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_INSTALLED = 1
        private const val VIEW_TYPE_AVAILABLE = 2
    }
}
