package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate

/**
 * Bottom sheet that lets the user change the parser assigned to a custom source
 * and enable or disable individual parsers (both built-in types and imported templates).
 *
 * **Selection** — tapping a parser row updates the source immediately and dismisses the sheet.
 * **Toggle** — flipping the switch enables or disables that parser without changing the source.
 *
 * Shown from [RemoteListFragment] via the overflow menu item "Change Parser".
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

        // Current parser of this source — highlighted in the list
        val currentSource = viewModel.findById(sourceId)
        val currentType = currentSource?.type
        val currentTemplateName = currentSource?.parserSourceName

        // Build the flat list of items to display
        val items = buildItems(viewModel.parserTemplates.value)

        recycler.adapter = ParserPickerAdapter(
            items = items,
            currentType = currentType,
            currentTemplateName = currentTemplateName,
            onBuiltinSelected = { type ->
                viewModel.changeParser(sourceId, type, null)
                val msg = getString(R.string.parser_changed, type.label)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                dismiss()
            },
            onTemplateSelected = { template ->
                viewModel.changeParser(sourceId, CustomSourceType.CUSTOM_TEMPLATE, template.name)
                val msg = getString(R.string.parser_changed, template.name)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                dismiss()
            },
            onBuiltinToggled = { type, enabled ->
                viewModel.setBuiltinParserEnabled(type, enabled)
                val msg = if (enabled) getString(R.string.parser_enabled, type.label)
                          else getString(R.string.parser_disabled, type.label)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            },
            onTemplateToggled = { template, enabled ->
                viewModel.setTemplateEnabled(template.id, enabled)
                val msg = if (enabled) getString(R.string.parser_enabled, template.name)
                          else getString(R.string.parser_disabled, template.name)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            },
            isBuiltinEnabled = { type -> viewModel.isBuiltinParserEnabled(type) },
        )
    }

    private fun buildItems(templates: List<ParserTemplate>): List<PickerItem> {
        val items = mutableListOf<PickerItem>()

        // Section: Built-in Parsers
        items.add(PickerItem.Header(getString(R.string.section_builtin_parsers)))
        CustomSourceType.entries
            .filter { it != CustomSourceType.WEBVIEW && it != CustomSourceType.KOTATSU_PARSER }
            .forEach { items.add(PickerItem.BuiltIn(it)) }

        // Section: Imported Parsers
        items.add(PickerItem.Header(getString(R.string.section_imported_parsers)))
        if (templates.isEmpty()) {
            items.add(PickerItem.EmptyHint(getString(R.string.no_imported_parsers)))
        } else {
            templates.forEach { items.add(PickerItem.Imported(it)) }
        }

        return items
    }

    // ── Data model ────────────────────────────────────────────────────────────

    sealed class PickerItem {
        data class Header(val title: String) : PickerItem()
        data class BuiltIn(val type: CustomSourceType) : PickerItem()
        data class Imported(val template: ParserTemplate) : PickerItem()
        data class EmptyHint(val message: String) : PickerItem()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class ParserPickerAdapter(
        private val items: List<PickerItem>,
        private val currentType: CustomSourceType?,
        private val currentTemplateName: String?,
        private val onBuiltinSelected: (CustomSourceType) -> Unit,
        private val onTemplateSelected: (ParserTemplate) -> Unit,
        private val onBuiltinToggled: (CustomSourceType, Boolean) -> Unit,
        private val onTemplateToggled: (ParserTemplate, Boolean) -> Unit,
        private val isBuiltinEnabled: (CustomSourceType) -> Boolean,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_PARSER = 1
            private const val TYPE_HINT   = 2
        }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is PickerItem.Header    -> TYPE_HEADER
            is PickerItem.EmptyHint -> TYPE_HINT
            else                    -> TYPE_PARSER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(
                    inflater.inflate(R.layout.item_parser_section_header, parent, false)
                )
                TYPE_HINT -> HintVH(
                    inflater.inflate(R.layout.item_parser_section_header, parent, false)
                )
                else -> ParserVH(
                    inflater.inflate(R.layout.item_parser_option, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is PickerItem.Header -> (holder as HeaderVH).bind(item)
                is PickerItem.EmptyHint -> (holder as HintVH).bind(item)
                is PickerItem.BuiltIn -> (holder as ParserVH).bindBuiltIn(
                    item, isCurrentBuiltIn = currentType == item.type
                )
                is PickerItem.Imported -> (holder as ParserVH).bindImported(
                    item, isCurrentTemplate = currentType == CustomSourceType.CUSTOM_TEMPLATE
                        && currentTemplateName == item.template.name
                )
            }
        }

        override fun getItemCount() = items.size

        inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.text_section_header)
            fun bind(item: PickerItem.Header) { title.text = item.title }
        }

        inner class HintVH(view: View) : RecyclerView.ViewHolder(view) {
            private val text: TextView = view.findViewById(R.id.text_section_header)
            fun bind(item: PickerItem.EmptyHint) {
                text.alpha = 0.6f
                text.textSize = 13f
                text.text = item.message
            }
        }

        inner class ParserVH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView: TextView = view.findViewById(R.id.text_parser_name)
            private val toggle: MaterialSwitch = view.findViewById(R.id.switch_parser_enabled)

            fun bindBuiltIn(item: PickerItem.BuiltIn, isCurrentBuiltIn: Boolean) {
                val enabled = isBuiltinEnabled(item.type)
                nameView.text = item.type.label
                nameView.alpha = if (enabled) 1f else 0.45f
                // Bold if this is the currently active parser for the source
                nameView.setTypeface(
                    null,
                    if (isCurrentBuiltIn) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL,
                )

                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = enabled
                toggle.setOnCheckedChangeListener { _, checked ->
                    nameView.alpha = if (checked) 1f else 0.45f
                    onBuiltinToggled(item.type, checked)
                }

                itemView.setOnClickListener { onBuiltinSelected(item.type) }
            }

            fun bindImported(item: PickerItem.Imported, isCurrentTemplate: Boolean) {
                val enabled = item.template.isEnabled
                nameView.text = item.template.name
                nameView.alpha = if (enabled) 1f else 0.45f
                nameView.setTypeface(
                    null,
                    if (isCurrentTemplate) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL,
                )

                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = enabled
                toggle.setOnCheckedChangeListener { _, checked ->
                    nameView.alpha = if (checked) 1f else 0.45f
                    onTemplateToggled(item.template, checked)
                }

                itemView.setOnClickListener { onTemplateSelected(item.template) }
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
