package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
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
 * one in the in-app browser (WebView) and remove entries they no longer want.
 */
@AndroidEntryPoint
class CustomSourcesSettingsFragment : Fragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

    private var recyclerView: RecyclerView? = null
    private var emptyView: View? = null
    private var fab: ExtendedFloatingActionButton? = null

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
            onOpen = ::openInBrowser,
            onDelete = ::confirmDelete,
        )
    }

    private fun openInBrowser(source: CustomSource) {
        val ctx = context ?: return
        val intent = when (source.type) {
            CustomSourceType.MANGADEX_COMPATIBLE,
            CustomSourceType.MADARA -> AppRouter.listIntent(
                context = ctx,
                source = CustomMangaSource(source),
                filter = null,
                sortOrder = null,
            )
            CustomSourceType.WEBVIEW -> AppRouter.browserIntent(
                context = ctx,
                url = source.cleanBaseUrl,
                source = null,
                title = source.displayName,
            )
        }
        startActivity(intent)
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
        private val onDelete: (CustomSource) -> Unit,
    ) : RecyclerView.Adapter<CustomSourcesAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_custom_source, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(sources[position], onOpen, onDelete)
        }

        override fun getItemCount(): Int = sources.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val container: LinearLayout = view.findViewById(R.id.row_root)
            private val titleView: TextView = view.findViewById(R.id.text_source_title)
            private val urlView: TextView = view.findViewById(R.id.text_source_url)
            private val typeView: TextView = view.findViewById(R.id.text_source_type)
            private val descView: TextView = view.findViewById(R.id.text_source_desc)
            private val deleteBtn: MaterialButton = view.findViewById(R.id.btn_delete_source)

            fun bind(
                source: CustomSource,
                onOpen: (CustomSource) -> Unit,
                onDelete: (CustomSource) -> Unit,
            ) {
                titleView.text = source.displayName
                urlView.text = source.cleanBaseUrl
                typeView.text = source.type.label
                descView.text = source.description.orEmpty()
                descView.isVisible = !source.description.isNullOrBlank()
                container.setOnClickListener { onOpen(source) }
                deleteBtn.setOnClickListener { onDelete(source) }
            }
        }
    }
}
