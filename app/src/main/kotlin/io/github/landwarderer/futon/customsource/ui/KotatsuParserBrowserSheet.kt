package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.parser.KotatsuParserMatcher.KotatsuLibraryParser
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen bottom sheet that exposes every parser from the
 * kotatsu-parsers-redo library for manual selection.
 *
 * **Two modes:**
 *
 *  • **Add mode** (`existingSourceId` = -1, default): Tapping a parser shows a
 *    dialog where the user enters a name and URL (pre-filled with the parser's
 *    default domain, editable for mirror sites).  Confirms by calling
 *    [CustomSourceViewModel.addKotatsuLibrarySource].
 *
 *  • **Change mode** (`existingSourceId` ≥ 0): Launched from [ChangeParserSheet].
 *    Tapping a parser shows a lightweight confirmation dialog.  On confirm it
 *    calls [CustomSourceViewModel.changeParser] to switch the existing source to
 *    the selected library parser.
 *
 * In both modes, the sheet is searchable (name, domain, language tag).
 */
@AndroidEntryPoint
class KotatsuParserBrowserSheet : BottomSheetDialogFragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

    /** Source ID when in change-mode; -1L when in add-mode. */
    private val existingSourceId: Long by lazy {
        arguments?.getLong(ARG_EXISTING_SOURCE_ID, -1L) ?: -1L
    }
    private val isChangeMode get() = existingSourceId >= 0L

    private var recyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var emptyView: TextView? = null
    private var searchInput: TextInputEditText? = null

    private var allParsers: List<KotatsuLibraryParser> = emptyList()
    private var adapter: LibraryAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_kotatsu_parser_browser, container, false)

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (sheet != null) {
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_library_parsers)
        progressBar  = view.findViewById(R.id.progress_library_loading)
        emptyView    = view.findViewById(R.id.text_library_empty)
        searchInput  = view.findViewById(R.id.input_library_search)

        // Update the title when in change mode
        if (isChangeMode) {
            view.findViewById<TextView>(R.id.text_library_title)
                ?.setText(R.string.kotatsu_library_title_change)
            view.findViewById<TextView>(R.id.text_library_subtitle)
                ?.setText(R.string.kotatsu_library_subtitle_change)
        }

        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        recyclerView?.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )

        searchInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filterParsers(s?.toString().orEmpty())
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.kotatsuLibraryParsers.collectLatest { parsers ->
                if (parsers.isEmpty()) {
                    progressBar?.isVisible = true
                    recyclerView?.isVisible = false
                    emptyView?.isVisible = false
                } else {
                    progressBar?.isVisible = false
                    allParsers = parsers
                    filterParsers(searchInput?.text?.toString().orEmpty())
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is CustomSourceViewModel.UiState.SourceAdded -> {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.library_source_added, state.source.displayName),
                            Toast.LENGTH_SHORT,
                        ).show()
                        dismiss()
                        viewModel.resetState()
                    }
                    is CustomSourceViewModel.UiState.SourceUpdated -> {
                        if (isChangeMode) {
                            Toast.makeText(
                                requireContext(),
                                getString(
                                    R.string.parser_changed,
                                    state.source.parserSourceName ?: state.source.type.label
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                            dismiss()
                            viewModel.resetState()
                        }
                    }
                    is CustomSourceViewModel.UiState.Error -> {
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    else -> Unit
                }
            }
        }

        viewModel.loadKotatsuLibraryParsers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
        progressBar  = null
        emptyView    = null
        searchInput  = null
        adapter      = null
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    private fun filterParsers(query: String) {
        val filtered = if (query.isBlank()) {
            allParsers
        } else {
            val q = query.trim().lowercase()
            allParsers.filter { p ->
                p.displayName.lowercase().contains(q) ||
                    p.domain.lowercase().contains(q) ||
                    p.languageTag.lowercase().contains(q) ||
                    p.source.name.lowercase().contains(q)
            }
        }
        emptyView?.isVisible = filtered.isEmpty() && allParsers.isNotEmpty()
        recyclerView?.isVisible = filtered.isNotEmpty()
        if (adapter == null) {
            adapter = LibraryAdapter(filtered, ::onParserTapped)
            recyclerView?.adapter = adapter
        } else {
            adapter?.update(filtered)
        }
    }

    // ── Parser tap handling ───────────────────────────────────────────────────

    private fun onParserTapped(parser: KotatsuLibraryParser) {
        if (isChangeMode) showChangeModeDialog(parser) else showAddModeDialog(parser)
    }

    /**
     * Change mode: lightweight confirmation — no URL input.
     * The existing source's URL is kept; only the parser type changes.
     */
    private fun showChangeModeDialog(parser: KotatsuLibraryParser) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.change_to_library_parser_title, parser.displayName))
            .setMessage(getString(R.string.change_to_library_parser_body, parser.domain))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply_label) { _, _ ->
                viewModel.changeParser(existingSourceId, CustomSourceType.KOTATSU_PARSER, parser.source.name)
            }
            .show()
    }

    /**
     * Add mode: full dialog with name + mirror URL fields.
     * URL defaults to the parser's official domain but can be changed for mirrors.
     */
    private fun showAddModeDialog(parser: KotatsuLibraryParser) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx)
            .inflate(R.layout.dialog_add_library_source, null)

        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.input_lib_name)
        val urlInput  = dialogView.findViewById<TextInputEditText>(R.id.input_lib_url)
        val urlLayout = dialogView.findViewById<TextInputLayout>(R.id.layout_lib_url)
        val descInput = dialogView.findViewById<TextInputEditText>(R.id.input_lib_desc)

        nameInput.setText(parser.displayName)
        urlInput.setText("https://${parser.domain}")

        val langNote = if (parser.languageTag.isNotEmpty()) " · ${parser.languageTag.uppercase()}" else ""
        urlLayout.helperText = getString(R.string.library_parser_default_domain, parser.domain) + langNote

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.add_library_parser_title, parser.displayName))
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add_source_label) { _, _ ->
                val name = nameInput.text?.toString().orEmpty()
                val url  = urlInput.text?.toString().orEmpty()
                val desc = descInput.text?.toString().orEmpty()
                viewModel.addKotatsuLibrarySource(parser.source, url, name, desc)
            }
            .show()
    }

    // ── RecyclerView adapter ──────────────────────────────────────────────────

    private class LibraryAdapter(
        private var items: List<KotatsuLibraryParser>,
        private val onTap: (KotatsuLibraryParser) -> Unit,
    ) : RecyclerView.Adapter<LibraryAdapter.VH>() {

        fun update(newItems: List<KotatsuLibraryParser>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_kotatsu_library_parser, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(items[position], onTap)

        override fun getItemCount() = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView:   TextView      = view.findViewById(R.id.text_lib_parser_name)
            private val domainView: TextView      = view.findViewById(R.id.text_lib_parser_domain)
            private val langView:   TextView      = view.findViewById(R.id.text_lib_parser_lang)
            private val addBtn:     MaterialButton = view.findViewById(R.id.btn_lib_add)

            fun bind(parser: KotatsuLibraryParser, onTap: (KotatsuLibraryParser) -> Unit) {
                nameView.text   = parser.displayName
                domainView.text = parser.domain
                langView.text   = parser.languageTag.uppercase().ifEmpty { "??" }
                langView.isVisible = true
                addBtn.setOnClickListener { onTap(parser) }
                itemView.setOnClickListener { onTap(parser) }
            }
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val TAG = "KotatsuParserBrowserSheet"
        private const val ARG_EXISTING_SOURCE_ID = "existing_source_id"

        /** Add mode: creates a new custom source from the selected parser. */
        fun newInstance() = KotatsuParserBrowserSheet()

        /**
         * Change mode: switches an existing source to use the selected library parser.
         * The existing source's URL is preserved; only [CustomSource.parserSourceName] changes.
         */
        fun newInstanceForChange(existingSourceId: Long) = KotatsuParserBrowserSheet().apply {
            arguments = Bundle().apply { putLong(ARG_EXISTING_SOURCE_ID, existingSourceId) }
        }
    }
}
