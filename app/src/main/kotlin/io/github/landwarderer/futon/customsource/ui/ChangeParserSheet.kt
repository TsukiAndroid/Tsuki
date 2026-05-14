package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.util.ext.getThemeColor
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate

/**
 * Bottom sheet that lets the user change which parser a custom source uses.
 *
 * Shows a "Currently using" info row at the very top so the active parser is
 * always visible — especially important for KOTATSU_PARSER sources where the
 * built-in match would otherwise be invisible (KOTATSU_PARSER is not listed as
 * a selectable option).  Below that are two sections: "Built-in Parsers" and
 * "Imported Parsers".  The currently active parser is highlighted with a check
 * icon.  Tapping any row immediately updates the source and dismisses the sheet.
 *
 * Shown from [RemoteListFragment] via the "Change Parser" overflow menu item.
 */
@AndroidEntryPoint
class ChangeParserSheet : BottomSheetDialogFragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_change_parser, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sourceId = arguments?.getLong(ARG_SOURCE_ID) ?: run { dismiss(); return }
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_parsers)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.setHasFixedSize(false)

        val currentSource    = viewModel.findById(sourceId)
        val currentType      = currentSource?.type
        val currentTemplate  = currentSource?.parserSourceName

        val items = buildItems(currentType, currentTemplate, viewModel.parserTemplates.value)

        recycler.adapter = ParserPickerAdapter(
            items           = items,
            currentType     = currentType,
            currentTemplate = currentTemplate,
            onBuiltinSelected = { type ->
                viewModel.changeParser(sourceId, type, null)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.parser_changed, type.label),
                    Toast.LENGTH_SHORT,
                ).show()
                dismiss()
            },
            onTemplateSelected = { template ->
                viewModel.changeParser(sourceId, CustomSourceType.CUSTOM_TEMPLATE, template.name)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.parser_changed, template.name),
                    Toast.LENGTH_SHORT,
                ).show()
                dismiss()
            },
            onBrowseLibrary = {
                dismiss()
                KotatsuParserBrowserSheet.newInstanceForChange(sourceId)
                    .show(parentFragmentManager, KotatsuParserBrowserSheet.TAG)
            },
        )
    }

    // ── Item model ────────────────────────────────────────────────────────────

    private sealed class PickerItem {
        /** Non-tappable row that displays the currently active parser at the top. */
        data class CurrentInfo(val label: String) : PickerItem()
        data class Header(val title: String) : PickerItem()
        data class BuiltIn(val type: CustomSourceType) : PickerItem()
        data class Imported(val template: ParserTemplate) : PickerItem()
        data class EmptyHint(val message: String) : PickerItem()
        /** Tappable row that opens the full Kotatsu library browser sheet. */
        object BrowseLibrary : PickerItem()
    }

    private fun buildItems(
        currentType: CustomSourceType?,
        currentTemplate: String?,
        templates: List<ParserTemplate>,
    ): List<PickerItem> {
        val items = mutableListOf<PickerItem>()

        // Always show what is currently active at the top so the user can see at a
        // glance — especially for KOTATSU_PARSER where the matched parser name
        // would not appear anywhere else in the list.
        val currentLabel: String? = when (currentType) {
            CustomSourceType.KOTATSU_PARSER ->
                "Auto-matched: ${currentTemplate ?: "Built-in parser"}"
            CustomSourceType.CUSTOM_TEMPLATE ->
                "Template: ${currentTemplate ?: "Custom Template"}"
            null -> null
            else -> "Currently: ${currentType.label}"
        }
        if (currentLabel != null) {
            items.add(PickerItem.CurrentInfo(currentLabel))
        }

        items.add(PickerItem.Header(getString(R.string.section_builtin_parsers)))
        CustomSourceType.entries
            .filter { it != CustomSourceType.WEBVIEW && it != CustomSourceType.KOTATSU_PARSER }
            .forEach { items.add(PickerItem.BuiltIn(it)) }

        items.add(PickerItem.Header(getString(R.string.section_imported_parsers)))
        if (templates.isEmpty()) {
            items.add(PickerItem.EmptyHint(getString(R.string.no_imported_parsers)))
        } else {
            templates.forEach { items.add(PickerItem.Imported(it)) }
        }

        // "Browse Kotatsu Library" action row — opens the full 800+ parser browser
        // so the user can switch to any kotatsu-parsers-redo parser.
        items.add(PickerItem.Header(getString(R.string.section_kotatsu_library)))
        items.add(PickerItem.BrowseLibrary)

        return items
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class ParserPickerAdapter(
        private val items: List<PickerItem>,
        private val currentType: CustomSourceType?,
        private val currentTemplate: String?,
        private val onBuiltinSelected: (CustomSourceType) -> Unit,
        private val onTemplateSelected: (ParserTemplate) -> Unit,
        private val onBrowseLibrary: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val VIEW_CURRENT = 0
            private const val VIEW_HEADER  = 1
            private const val VIEW_PARSER  = 2
            private const val VIEW_HINT    = 3
        }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is PickerItem.CurrentInfo   -> VIEW_CURRENT
            is PickerItem.Header        -> VIEW_HEADER
            is PickerItem.EmptyHint     -> VIEW_HINT
            else                        -> VIEW_PARSER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_CURRENT -> CurrentInfoVH(
                    inflater.inflate(R.layout.item_parser_option, parent, false)
                )
                VIEW_HEADER -> HeaderVH(
                    inflater.inflate(R.layout.item_parser_section_header, parent, false)
                )
                VIEW_HINT -> HintVH(
                    inflater.inflate(R.layout.item_parser_section_header, parent, false)
                )
                else -> ParserVH(
                    inflater.inflate(R.layout.item_parser_option, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is PickerItem.CurrentInfo  -> (holder as CurrentInfoVH).bind(item.label)
                is PickerItem.Header       -> (holder as HeaderVH).bind(item.title)
                is PickerItem.EmptyHint    -> (holder as HintVH).bind(item.message)
                is PickerItem.BuiltIn      -> (holder as ParserVH).bindBuiltIn(item)
                is PickerItem.Imported     -> (holder as ParserVH).bindImported(item)
                is PickerItem.BrowseLibrary -> (holder as ParserVH).bindBrowseLibrary()
            }
        }

        override fun getItemCount() = items.size

        /** Non-tappable info row showing the currently active parser. */
        class CurrentInfoVH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView: TextView   = view.findViewById(R.id.text_parser_name)
            private val checkIcon: ImageView = view.findViewById(R.id.icon_selected)

            fun bind(label: String) {
                nameView.text = label
                checkIcon.isVisible = true
                nameView.setTypeface(null, android.graphics.Typeface.BOLD)
                val color = itemView.context.getThemeColor(
                    appcompatR.attr.colorPrimary,
                    nameView.currentTextColor,
                )
                nameView.setTextColor(color)
                // Non-interactive — just an info display
                itemView.isClickable = false
                itemView.isFocusable = false
                itemView.alpha = 1f
            }
        }

        class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            private val text: TextView = view.findViewById(R.id.text_section_header)
            fun bind(title: String) { text.text = title }
        }

        class HintVH(view: View) : RecyclerView.ViewHolder(view) {
            private val text: TextView = view.findViewById(R.id.text_section_header)
            fun bind(message: String) {
                text.alpha = 0.55f
                text.textSize = 13f
                text.text = message
            }
        }

        inner class ParserVH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView: TextView   = view.findViewById(R.id.text_parser_name)
            private val checkIcon: ImageView = view.findViewById(R.id.icon_selected)

            fun bindBuiltIn(item: PickerItem.BuiltIn) {
                val isActive = currentType == item.type
                nameView.text = item.type.label
                applyActiveStyle(isActive)
                itemView.setOnClickListener { onBuiltinSelected(item.type) }
            }

            fun bindImported(item: PickerItem.Imported) {
                val isActive = currentType == CustomSourceType.CUSTOM_TEMPLATE
                    && currentTemplate == item.template.name
                nameView.text = item.template.name
                applyActiveStyle(isActive)
                itemView.setOnClickListener { onTemplateSelected(item.template) }
            }

            fun bindBrowseLibrary() {
                nameView.text = itemView.context.getString(R.string.browse_kotatsu_library_action)
                checkIcon.isVisible = false
                nameView.setTypeface(null, android.graphics.Typeface.NORMAL)
                val color = itemView.context.getThemeColor(
                    com.google.android.material.R.attr.colorPrimary,
                    nameView.currentTextColor,
                )
                nameView.setTextColor(color)
                itemView.setOnClickListener { onBrowseLibrary() }
            }

            private fun applyActiveStyle(active: Boolean) {
                checkIcon.isVisible = active
                nameView.setTypeface(
                    null,
                    if (active) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL,
                )
                val color = if (active)
                    itemView.context.getThemeColor(appcompatR.attr.colorPrimary, nameView.currentTextColor)
                else
                    itemView.context.getThemeColor(materialR.attr.colorOnSurface, nameView.currentTextColor)
                nameView.setTextColor(color)
            }
        }
    }

    companion object {
        const val TAG = "ChangeParserSheet"
        private const val ARG_SOURCE_ID = "source_id"

        fun newInstance(sourceId: Long) = ChangeParserSheet().apply {
            arguments = Bundle().apply { putLong(ARG_SOURCE_ID, sourceId) }
        }
    }
}

