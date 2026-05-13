package io.github.landwarderer.futon.customsource.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import io.github.landwarderer.futon.settings.SettingsActivity
import java.io.File

/**
 * Settings screen — "Parsers" subsection inside Manage Sources.
 *
 * Shows two sections:
 *  - **Built-in Parsers** — all built-in [CustomSourceType] values (except WEBVIEW and
 *    KOTATSU_PARSER) with a toggle to enable/disable each one from the picker.
 *  - **Imported Parsers** — user-imported [ParserTemplate]s; each card has a toggle
 *    plus buttons to add a site, export, or delete the template.
 *
 * An info icon (ⓘ) in the toolbar opens a dialog that explains what parser templates
 * are and which JSON fields they support.
 */
@AndroidEntryPoint
class ParserTemplatesFragment : Fragment() {

    private val viewModel: ParserTemplateViewModel by viewModels()

    private var recyclerView: RecyclerView? = null
    private var emptyView: View? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_parser_templates, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.parser_templates))

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_parser_templates, menu)
            }
            override fun onMenuItemSelected(item: MenuItem): Boolean {
                if (item.itemId == R.id.action_parser_info) { showAboutDialog(); return true }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        recyclerView = view.findViewById<RecyclerView>(R.id.recycler_parser_templates).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
        }
        emptyView = view.findViewById(R.id.empty_view_parsers)

        view.findViewById<MaterialButton>(R.id.btn_import_parser_fab).setOnClickListener {
            ImportParserSheet.newInstance().show(childFragmentManager, ImportParserSheet.TAG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.templates.collectLatest { templates -> render(templates) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.addSiteState.collectLatest { state ->
                if (state is ParserTemplateViewModel.AddSiteState.Success) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.add_site_success, state.siteName, state.templateName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    viewModel.resetAddSiteState()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
        emptyView = null
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun render(templates: List<ParserTemplate>) {
        // The empty state only applies to imported templates; built-in parsers are
        // always present, so we only show the empty-state view when there are truly
        // no templates AND we want to hint the user to import one.
        emptyView?.isVisible = false
        recyclerView?.isVisible = true
        recyclerView?.adapter = ParsersAdapter(
            templates           = templates,
            isBuiltinEnabled    = viewModel::isBuiltinParserEnabled,
            onBuiltinToggled    = viewModel::setBuiltinParserEnabled,
            onTemplateToggled   = { template, enabled ->
                viewModel.setTemplateEnabled(template.id, enabled)
            },
            onDelete            = ::confirmDelete,
            onAddSite           = ::showAddSiteDialog,
            onExport            = ::exportTemplate,
        )
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.parser_template_what_title)
            .setMessage(
                "${getString(R.string.parser_template_what_body)}\n\n" +
                    "${getString(R.string.parser_template_example_title)}\n" +
                    getString(R.string.parser_template_example)
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete(template: ParserTemplate) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_parser_template_title)
            .setMessage(getString(R.string.remove_parser_template_message, template.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                viewModel.removeTemplate(template.id)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.parser_template_removed, template.name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .show()
    }

    private fun showAddSiteDialog(template: ParserTemplate) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_template_site, null, false)
        val urlLayout = dialogView.findViewById<TextInputLayout>(R.id.layout_add_site_url)
        val urlInput  = dialogView.findViewById<TextInputEditText>(R.id.input_add_site_url)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.add_site_for_template_title, template.name))
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add_source_label, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = urlInput.text?.toString().orEmpty().trim()
                if (url.isBlank()) {
                    urlLayout.error = getString(R.string.add_site_error_invalid_url)
                    return@setOnClickListener
                }
                urlLayout.error = null
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.addSiteState.collectLatest { state ->
                        when (state) {
                            is ParserTemplateViewModel.AddSiteState.Error -> {
                                urlLayout.error = state.message
                                viewModel.resetAddSiteState()
                            }
                            is ParserTemplateViewModel.AddSiteState.Success -> dialog.dismiss()
                            else -> {}
                        }
                    }
                }
                viewModel.addSourceForTemplate(template, url, "")
            }
        }
        dialog.show()
    }

    private fun exportTemplate(template: ParserTemplate) {
        val ctx = requireContext()
        runCatching {
            val safeName = template.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "template" }
            val outFile  = File(File(ctx.cacheDir, "template_exports").apply { mkdirs() }, "$safeName.json")
            outFile.writeText(template.rawJson)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", outFile)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_template_subject, template.name))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    getString(R.string.export_parser_template),
                )
            )
        }.onFailure {
            Toast.makeText(ctx, R.string.export_template_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    /**
     * Unified adapter for the parsers screen.
     *
     * Sections rendered (in order):
     *  1. Section header "Built-in Parsers"
     *  2. One [BuiltinVH] row per applicable [CustomSourceType] (enable/disable switch)
     *  3. Section header "Imported Parsers"
     *  4. One [TemplateVH] card per [ParserTemplate] (enable/disable switch + action buttons)
     *     — or a "no templates" hint row when the list is empty
     */
    private class ParsersAdapter(
        templates:          List<ParserTemplate>,
        private val isBuiltinEnabled:  (CustomSourceType) -> Boolean,
        private val onBuiltinToggled:  (CustomSourceType, Boolean) -> Unit,
        private val onTemplateToggled: (ParserTemplate, Boolean) -> Unit,
        private val onDelete:          (ParserTemplate) -> Unit,
        private val onAddSite:         (ParserTemplate) -> Unit,
        private val onExport:          (ParserTemplate) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        // ── Row model ─────────────────────────────────────────────────────────

        private sealed class Row {
            data class Header(val title: String) : Row()
            data class Builtin(val type: CustomSourceType) : Row()
            data class Template(val template: ParserTemplate) : Row()
            data class EmptyHint(val message: String) : Row()
        }

        private val rows: List<Row> = buildList {
            // ── Built-in section ──────────────────────────────────────────────
            add(Row.Header("Built-in Parsers"))
            CustomSourceType.entries
                .filter { it != CustomSourceType.WEBVIEW && it != CustomSourceType.KOTATSU_PARSER }
                .forEach { add(Row.Builtin(it)) }

            // ── Imported section ──────────────────────────────────────────────
            add(Row.Header("Imported Parsers"))
            if (templates.isEmpty()) {
                add(Row.EmptyHint("No templates imported yet — tap \"+ Import Parser\" below."))
            } else {
                templates.forEach { add(Row.Template(it)) }
            }
        }

        // ── View types ────────────────────────────────────────────────────────

        companion object {
            private const val VT_HEADER   = 0
            private const val VT_BUILTIN  = 1
            private const val VT_TEMPLATE = 2
            private const val VT_HINT     = 3
        }

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header   -> VT_HEADER
            is Row.Builtin  -> VT_BUILTIN
            is Row.Template -> VT_TEMPLATE
            is Row.EmptyHint -> VT_HINT
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                VT_HEADER   -> HeaderVH(inf.inflate(R.layout.item_parser_section_header, parent, false))
                VT_BUILTIN  -> BuiltinVH(inf.inflate(R.layout.item_builtin_parser_row, parent, false))
                VT_HINT     -> HintVH(inf.inflate(R.layout.item_parser_section_header, parent, false))
                else        -> TemplateVH(inf.inflate(R.layout.item_parser_template, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header    -> (holder as HeaderVH).bind(row.title)
                is Row.Builtin   -> (holder as BuiltinVH).bind(row.type)
                is Row.Template  -> (holder as TemplateVH).bind(row.template)
                is Row.EmptyHint -> (holder as HintVH).bind(row.message)
            }
        }

        // ── View holders ──────────────────────────────────────────────────────

        inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            private val text: TextView = view.findViewById(R.id.text_section_header)
            fun bind(title: String) { text.text = title }
        }

        inner class HintVH(view: View) : RecyclerView.ViewHolder(view) {
            private val text: TextView = view.findViewById(R.id.text_section_header)
            fun bind(message: String) {
                text.alpha    = 0.55f
                text.textSize = 13f
                text.text     = message
            }
        }

        inner class BuiltinVH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView: TextView      = view.findViewById(R.id.text_builtin_name)
            private val toggle: MaterialSwitch  = view.findViewById(R.id.switch_builtin_enabled)

            fun bind(type: CustomSourceType) {
                nameView.text = type.label
                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = isBuiltinEnabled(type)
                // Dim the label when disabled so the state is visually obvious
                nameView.alpha = if (toggle.isChecked) 1f else 0.45f
                toggle.setOnCheckedChangeListener { _, checked ->
                    nameView.alpha = if (checked) 1f else 0.45f
                    onBuiltinToggled(type, checked)
                }
            }
        }

        inner class TemplateVH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView:   TextView       = view.findViewById(R.id.text_template_name)
            private val metaView:   TextView       = view.findViewById(R.id.text_template_meta)
            private val toggle:     MaterialSwitch = view.findViewById(R.id.switch_template_enabled)
            private val addSiteBtn: MaterialButton = view.findViewById(R.id.btn_add_site_template)
            private val exportBtn:  MaterialButton = view.findViewById(R.id.btn_export_template)
            private val deleteBtn:  MaterialButton = view.findViewById(R.id.btn_delete_template)

            fun bind(template: ParserTemplate) {
                nameView.text = template.name
                metaView.text = itemView.context.getString(
                    R.string.template_meta, template.version, template.type,
                )

                // Dim the whole card when the template is disabled
                val dimAlpha = if (template.isEnabled) 1f else 0.5f
                nameView.alpha = dimAlpha
                metaView.alpha = dimAlpha

                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = template.isEnabled
                toggle.setOnCheckedChangeListener { _, checked ->
                    nameView.alpha = if (checked) 1f else 0.5f
                    metaView.alpha = if (checked) 1f else 0.5f
                    onTemplateToggled(template, checked)
                }

                addSiteBtn.setOnClickListener { onAddSite(template) }
                exportBtn.setOnClickListener  { onExport(template) }
                deleteBtn.setOnClickListener  { onDelete(template) }
            }
        }
    }
}
