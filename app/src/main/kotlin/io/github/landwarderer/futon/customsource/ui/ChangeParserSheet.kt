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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate

/**
 * Bottom sheet that lets the user change which parser a custom source uses.
 *
 * Shows two sections — "Built-in Parsers" and "Imported Parsers".
 * The currently active parser is highlighted with a check icon.
 * Tapping any row immediately updates the source and dismisses the sheet.
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

        val items = buildItems(viewModel.parserTemplates.value)

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
        )
    }

    // ── Item model ────────────────────────────────────────────────────────────

    private sealed class PickerItem {
        data class Header(val title: String) : PickerItem()
        data class BuiltIn(val type: CustomSourceType) : PickerItem()
        data class Imported(val template: ParserTemplate) : PickerItem()
        data class EmptyHint(val message: String) : PickerItem()
    }

    private fun buildItems(templates: List<ParserTemplate>): List<PickerItem> {
        val items = mutableListOf<PickerItem>()

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

        return items
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class ParserPickerAdapter(
        private val items: List<PickerItem>,
        private val currentType: CustomSourceType?,
        private val currentTemplate: String?,
        private val onBuiltinSelected: (CustomSourceType) -> Unit,
        private val onTemplateSelected: (ParserTemplate) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val VIEW_HEADER = 0
            private const val VIEW_PARSER = 1
            private const val VIEW_HINT   = 2
        }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is PickerItem.Header    -> VIEW_HEADER
            is PickerItem.EmptyHint -> VIEW_HINT
            else                    -> VIEW_PARSER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
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
                is PickerItem.Header    -> (holder as HeaderVH).bind(item.title)
                is PickerItem.EmptyHint -> (holder as HintVH).bind(item.message)
                is PickerItem.BuiltIn   -> (holder as ParserVH).bindBuiltIn(item)
                is PickerItem.Imported  -> (holder as ParserVH).bindImported(item)
            }
        }

        override fun getItemCount() = items.size

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
            private val nameView: TextView  = view.findViewById(R.id.text_parser_name)
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

            private fun applyActiveStyle(active: Boolean) {
                checkIcon.isVisible = active
                nameView.setTypeface(
                    null,
                    if (active) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL,
                )
                nameView.setTextColor(
                    itemView.context.getColorStateList(
                        if (active) com.google.android.material.R.attr.colorPrimary
                        else com.google.android.material.R.attr.colorOnSurface
                    )?.defaultColor
                        ?: nameView.currentTextColor
                )
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
