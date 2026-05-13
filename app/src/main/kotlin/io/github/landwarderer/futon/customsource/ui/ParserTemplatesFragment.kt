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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import io.github.landwarderer.futon.settings.SettingsActivity
import java.io.File

/**
 * Settings screen — "Parsers" subsection inside Manage Sources.
 *
 * Shows all imported [ParserTemplate]s with their name, version, and type.
 * An info icon in the toolbar opens a dialog explaining what parser templates
 * are and what JSON fields they support (replaces the old always-visible card
 * that was blocking scroll).
 *
 * From here the user can:
 *  - import a new template via [ImportParserSheet]
 *  - export an existing template to the Android share sheet
 *  - delete an existing template
 *  - add a manga site that uses a specific template via the "Add Site" button
 */
@AndroidEntryPoint
class ParserTemplatesFragment : Fragment() {

    private val viewModel: ParserTemplateViewModel by viewModels()

    private var recyclerView: RecyclerView? = null
    private var emptyView: View? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_parser_templates, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.parser_templates))

        // Info icon in the toolbar — replaces the old blocking info card at the bottom
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_parser_templates, menu)
            }
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == R.id.action_parser_info) {
                    showAboutDialog()
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        recyclerView = view.findViewById<RecyclerView>(R.id.recycler_parser_templates).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }
        emptyView = view.findViewById(R.id.empty_view_parsers)

        view.findViewById<MaterialButton>(R.id.btn_import_parser_fab).setOnClickListener {
            ImportParserSheet.newInstance()
                .show(childFragmentManager, ImportParserSheet.TAG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.templates.collectLatest { templates -> render(templates) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.addSiteState.collectLatest { state ->
                when (state) {
                    is ParserTemplateViewModel.AddSiteState.Success -> {
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.add_site_success,
                                state.siteName,
                                state.templateName,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                        viewModel.resetAddSiteState()
                    }
                    else -> { /* Idle — nothing to do */ }
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
        emptyView?.isVisible = templates.isEmpty()
        recyclerView?.isVisible = templates.isNotEmpty()
        recyclerView?.adapter = ParserTemplatesAdapter(
            templates = templates,
            onDelete  = ::confirmDelete,
            onAddSite = ::showAddSiteDialog,
            onExport  = ::exportTemplate,
        )
    }

    /** Shows the "What are parser templates?" explanation as a dismissible dialog. */
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
                            is ParserTemplateViewModel.AddSiteState.Success -> {
                                dialog.dismiss()
                            }
                            else -> { /* Idle or in-flight */ }
                        }
                    }
                }
                viewModel.addSourceForTemplate(template, url, "")
            }
        }

        dialog.show()
    }

    /**
     * Writes the template's raw JSON to a temporary file and opens the Android
     * share sheet so the user can send it anywhere they like.
     */
    private fun exportTemplate(template: ParserTemplate) {
        val ctx = requireContext()
        runCatching {
            val safeName = template.name
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(64)
                .ifEmpty { "template" }
            val outDir  = File(ctx.cacheDir, "template_exports").apply { mkdirs() }
            val outFile = File(outDir, "$safeName.json")
            outFile.writeText(template.rawJson)

            val uri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.files",
                outFile,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_template_subject, template.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_parser_template)))
        }.onFailure {
            Toast.makeText(ctx, R.string.export_template_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class ParserTemplatesAdapter(
        private val templates: List<ParserTemplate>,
        private val onDelete:  (ParserTemplate) -> Unit,
        private val onAddSite: (ParserTemplate) -> Unit,
        private val onExport:  (ParserTemplate) -> Unit,
    ) : RecyclerView.Adapter<ParserTemplatesAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_parser_template, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(templates[position], onDelete, onAddSite, onExport)
        }

        override fun getItemCount(): Int = templates.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView:   TextView       = view.findViewById(R.id.text_template_name)
            private val metaView:   TextView       = view.findViewById(R.id.text_template_meta)
            private val addSiteBtn: MaterialButton = view.findViewById(R.id.btn_add_site_template)
            private val exportBtn:  MaterialButton = view.findViewById(R.id.btn_export_template)
            private val deleteBtn:  MaterialButton = view.findViewById(R.id.btn_delete_template)

            fun bind(
                template: ParserTemplate,
                onDelete:  (ParserTemplate) -> Unit,
                onAddSite: (ParserTemplate) -> Unit,
                onExport:  (ParserTemplate) -> Unit,
            ) {
                nameView.text = template.name
                metaView.text = itemView.context.getString(
                    R.string.template_meta,
                    template.version,
                    template.type,
                )
                addSiteBtn.setOnClickListener { onAddSite(template) }
                exportBtn.setOnClickListener  { onExport(template) }
                deleteBtn.setOnClickListener  { onDelete(template) }
            }
        }
    }
}
