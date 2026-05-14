package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.settings.SettingsActivity
import io.github.landwarderer.futon.core.nav.AppRouter
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType

/**
 * Manage user-added custom sources. Shows the saved list, lets the user open
 * one in the in-app browser (WebView) or manga list view, edit, remove entries,
 * and import/export the full list as a JSON file.
 */
@AndroidEntryPoint
class CustomSourcesSettingsFragment : Fragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

    private var recyclerView: RecyclerView? = null
    private var emptyView: View? = null
    private var fab: ExtendedFloatingActionButton? = null

    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val json = viewModel.exportSourcesJson()
                requireContext().contentResolver.openOutputStream(uri)
                    ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val count = viewModel.sources.value.size
                Toast.makeText(
                    requireContext(),
                    getString(R.string.sources_exported, count),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (_: Exception) {
                Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_LONG).show()
            }
        }

        importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val json = requireContext().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: return@registerForActivityResult
                val count = viewModel.importSourcesJson(json)
                val msg = if (count > 0) {
                    getString(R.string.sources_imported, count)
                } else {
                    getString(R.string.no_sources_imported)
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(requireContext(), getString(R.string.import_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_custom_sources, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? SettingsActivity)?.setSectionTitle(getString(R.string.custom_sources))

        recyclerView = view.findViewById<RecyclerView>(R.id.recycler_custom_sources).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(true)
        }
        emptyView = view.findViewById(R.id.empty_view)

        fab = view.findViewById<ExtendedFloatingActionButton>(R.id.fab_add_source).apply {
            setOnClickListener {
                AddCustomSourceSheet.newInstance()
                    .show(childFragmentManager, AddCustomSourceSheet.TAG)
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.opt_custom_sources, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_export_sources -> {
                        exportLauncher.launch("tsuki-sources.json")
                        true
                    }
                    R.id.action_import_sources -> {
                        importLauncher.launch(arrayOf("application/json", "*/*"))
                        true
                    }
                    R.id.action_browse_library -> {
                        KotatsuParserBrowserSheet.newInstance()
                            .show(childFragmentManager, KotatsuParserBrowserSheet.TAG)
                        true
                    }
                    R.id.action_health_check -> {
                        ParserHealthCheckSheet.newInstance()
                            .show(childFragmentManager, ParserHealthCheckSheet.TAG)
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sources.collectLatest { sources -> render(sources) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
        emptyView = null
        fab = null
    }

    private fun render(sources: List<CustomSource>) {
        emptyView?.isVisible = sources.isEmpty()
        recyclerView?.isVisible = sources.isNotEmpty()
        recyclerView?.adapter = CustomSourcesAdapter(
            sources = sources,
            onOpen = ::openSource,
            onEdit = ::openEditSheet,
            onDelete = ::confirmDelete,
            onToggleEnabled = { source -> viewModel.toggleEnabled(source.id) },
        )
    }

    private fun openSource(source: CustomSource) {
        if (!source.isEnabled) {
            Toast.makeText(
                requireContext(),
                getString(R.string.source_disabled_badge) + " — enable it first",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val ctx = context ?: return
        val intent = when (source.type) {
            CustomSourceType.WEBVIEW -> AppRouter.browserIntent(
                context = ctx,
                url = source.cleanBaseUrl,
                source = null,
                title = source.displayName,
            )
            else -> AppRouter.listIntent(
                context = ctx,
                source = CustomMangaSource(source),
                filter = null,
                sortOrder = null,
            )
        }
        startActivity(intent)
    }

    private fun openEditSheet(source: CustomSource) {
        EditCustomSourceSheet.newInstance(source.id)
            .show(childFragmentManager, EditCustomSourceSheet.TAG)
    }

    private fun confirmDelete(source: CustomSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_source_title)
            .setMessage(getString(R.string.remove_source_message, source.displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ -> viewModel.removeSource(source.id) }
            .show()
    }

    private class CustomSourcesAdapter(
        private val sources: List<CustomSource>,
        private val onOpen: (CustomSource) -> Unit,
        private val onEdit: (CustomSource) -> Unit,
        private val onDelete: (CustomSource) -> Unit,
        private val onToggleEnabled: (CustomSource) -> Unit,
    ) : RecyclerView.Adapter<CustomSourcesAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_custom_source, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(sources[position], onOpen, onEdit, onDelete, onToggleEnabled)
        }

        override fun getItemCount(): Int = sources.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val container: LinearLayout      = view.findViewById(R.id.row_root)
            private val titleView: TextView          = view.findViewById(R.id.text_source_title)
            private val urlView: TextView            = view.findViewById(R.id.text_source_url)
            private val typeView: TextView           = view.findViewById(R.id.text_source_type)
            private val descView: TextView           = view.findViewById(R.id.text_source_desc)
            private val enabledSwitch: MaterialSwitch = view.findViewById(R.id.switch_source_enabled)
            private val editBtn: MaterialButton      = view.findViewById(R.id.btn_edit_source)
            private val deleteBtn: MaterialButton    = view.findViewById(R.id.btn_delete_source)

            fun bind(
                source: CustomSource,
                onOpen: (CustomSource) -> Unit,
                onEdit: (CustomSource) -> Unit,
                onDelete: (CustomSource) -> Unit,
                onToggleEnabled: (CustomSource) -> Unit,
            ) {
                titleView.text = source.displayName
                urlView.text   = source.cleanBaseUrl
                descView.text  = source.description.orEmpty()
                descView.isVisible = !source.description.isNullOrBlank()

                // Show type label; append "(Disabled)" badge when off
                typeView.text = if (source.isEnabled) {
                    source.type.label
                } else {
                    val ctx = itemView.context
                    "${source.type.label} · ${ctx.getString(R.string.source_disabled_badge)}"
                }

                // Dim the whole card when disabled so it's immediately obvious
                itemView.alpha = if (source.isEnabled) 1f else 0.5f

                // Sync switch without triggering the listener
                enabledSwitch.setOnCheckedChangeListener(null)
                enabledSwitch.isChecked = source.isEnabled
                enabledSwitch.contentDescription = itemView.context.getString(
                    if (source.isEnabled) R.string.source_enabled_desc
                    else R.string.source_disabled_desc
                )
                enabledSwitch.setOnCheckedChangeListener { _, _ -> onToggleEnabled(source) }

                container.setOnClickListener { onOpen(source) }
                editBtn.setOnClickListener   { onEdit(source) }
                deleteBtn.setOnClickListener { onDelete(source) }
            }
        }
    }
}
