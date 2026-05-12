package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import io.github.landwarderer.futon.settings.SettingsActivity

/**
 * Settings screen — "Parsers" subsection inside Manga Sources.
 *
 * Shows all imported [ParserTemplate]s with their name, version, and type.
 * From here the user can delete individual templates or import a new one
 * via [ImportParserSheet].
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
        emptyView = null
    }

    private fun render(templates: List<ParserTemplate>) {
        emptyView?.isVisible = templates.isEmpty()
        recyclerView?.isVisible = templates.isNotEmpty()
        recyclerView?.adapter = ParserTemplatesAdapter(
            templates = templates,
            onDelete = ::confirmDelete,
        )
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

    private class ParserTemplatesAdapter(
        private val templates: List<ParserTemplate>,
        private val onDelete: (ParserTemplate) -> Unit,
    ) : RecyclerView.Adapter<ParserTemplatesAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_parser_template, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(templates[position], onDelete)
        }

        override fun getItemCount(): Int = templates.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView:    TextView      = view.findViewById(R.id.text_template_name)
            private val metaView:    TextView      = view.findViewById(R.id.text_template_meta)
            private val deleteBtn:   MaterialButton = view.findViewById(R.id.btn_delete_template)

            fun bind(template: ParserTemplate, onDelete: (ParserTemplate) -> Unit) {
                nameView.text = template.name
                metaView.text = itemView.context.getString(
                    R.string.template_meta,
                    template.version,
                    template.type,
                )
                deleteBtn.setOnClickListener { onDelete(template) }
            }
        }
    }
}
