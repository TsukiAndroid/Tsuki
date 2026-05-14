package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.parser.KotatsuParserMatcher.KotatsuLibraryParser
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen bottom sheet that exposes every non-broken parser from the
 * kotatsu-parsers-redo library for manual selection, management, and bulk control.
 *
 * ### Features
 *  - Real-time search by name, domain, or language tag
 *  - Language filter chips (auto-populated from parser data)
 *  - "Already added" indicators with enable/disable dot and "Manage" button
 *  - Manage dialog: toggle enable/disable, edit mirror URL, remove
 *  - Bulk "Enable All / Disable All" bottom bar (visible when ≥1 source is added)
 *  - Stats chip: "NNN parsers · M added"
 *
 * ### Two modes
 *  - **Add mode** (default): tapping a parser shows a dialog pre-filled with the
 *    parser's default domain; the user can type a mirror URL before confirming.
 *  - **Change mode** (launched from [ChangeParserSheet]): tapping a parser shows a
 *    lightweight confirmation; the existing source URL is preserved.
 */
@AndroidEntryPoint
class KotatsuParserBrowserSheet : BottomSheetDialogFragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

    private val existingSourceId: Long by lazy {
        arguments?.getLong(ARG_EXISTING_SOURCE_ID, -1L) ?: -1L
    }
    private val isChangeMode get() = existingSourceId >= 0L

    // ── Views ─────────────────────────────────────────────────────────────────

    private var recyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var emptyView: TextView? = null
    private var searchInput: TextInputEditText? = null
    private var chipGroupLang: ChipGroup? = null
    private var scrollLangFilter: View? = null
    private var statsChip: Chip? = null
    private var bulkLayout: LinearLayout? = null
    private var dividerBulk: View? = null

    // ── State ─────────────────────────────────────────────────────────────────

    private var allParsers: List<KotatsuLibraryParser> = emptyList()
    private var addedSources: List<CustomSource> = emptyList()
    private var selectedLanguage: String = LANG_ALL
    private var languageChipsPopulated: Boolean = false
    private var adapter: LibraryAdapter? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_kotatsu_parser_browser, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView    = view.findViewById(R.id.recycler_library_parsers)
        progressBar     = view.findViewById(R.id.progress_library_loading)
        emptyView       = view.findViewById(R.id.text_library_empty)
        searchInput     = view.findViewById(R.id.input_library_search)
        chipGroupLang   = view.findViewById(R.id.chip_group_language)
        scrollLangFilter = view.findViewById(R.id.scroll_language_filter)
        statsChip       = view.findViewById(R.id.chip_parser_stats)
        bulkLayout      = view.findViewById(R.id.layout_bulk_actions)
        dividerBulk     = view.findViewById(R.id.divider_bulk_actions)

        if (isChangeMode) {
            view.findViewById<TextView>(R.id.text_library_title)?.setText(R.string.kotatsu_library_title_change)
            view.findViewById<TextView>(R.id.text_library_subtitle)?.setText(R.string.kotatsu_library_subtitle_change)
        }

        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        recyclerView?.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        searchInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filterParsers(s?.toString().orEmpty())
            }
        })

        // Bulk action buttons
        view.findViewById<MaterialButton>(R.id.btn_enable_all_sources)?.setOnClickListener {
            viewModel.setAllKotatsuSourcesEnabled(true)
            Toast.makeText(requireContext(), getString(R.string.enable_all_kotatsu), Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.btn_disable_all_sources)?.setOnClickListener {
            viewModel.setAllKotatsuSourcesEnabled(false)
            Toast.makeText(requireContext(), getString(R.string.disable_all_kotatsu), Toast.LENGTH_SHORT).show()
        }

        // Observe library parsers
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.kotatsuLibraryParsers.collectLatest { parsers ->
                if (parsers.isEmpty()) {
                    progressBar?.isVisible = true
                    recyclerView?.isVisible = false
                    emptyView?.isVisible = false
                    statsChip?.isVisible = false
                } else {
                    progressBar?.isVisible = false
                    allParsers = parsers
                    if (!languageChipsPopulated) populateLanguageChips(parsers)
                    filterParsers(searchInput?.text?.toString().orEmpty())
                }
            }
        }

        // Observe added sources to update the "Added/Manage" state in the list
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sources.collectLatest { sources ->
                addedSources = sources
                if (allParsers.isNotEmpty()) {
                    filterParsers(searchInput?.text?.toString().orEmpty())
                }
            }
        }

        // Observe UiState for add/change confirmations
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
        recyclerView = null; progressBar = null; emptyView = null
        searchInput = null; chipGroupLang = null; scrollLangFilter = null
        statsChip = null; bulkLayout = null; dividerBulk = null
        adapter = null
    }

    // ── Language filter chips ─────────────────────────────────────────────────

    private fun populateLanguageChips(parsers: List<KotatsuLibraryParser>) {
        if (languageChipsPopulated) return
        languageChipsPopulated = true
        val group = chipGroupLang ?: return

        // Top languages by frequency, max 14
        val topLangs = parsers
            .groupingBy { it.languageTag.ifEmpty { "??" } }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(14)
            .map { it.key }

        // "All" first
        group.addView(buildFilterChip(LANG_ALL, getString(R.string.lang_all), true))
        topLangs.forEach { lang -> group.addView(buildFilterChip(lang, lang, false)) }

        scrollLangFilter?.isVisible = true
    }

    private fun buildFilterChip(tag: String, label: String, checked: Boolean): Chip {
        return Chip(requireContext(), null, 0).apply {
            text = label
            isCheckable = true
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedLanguage = tag
                    filterParsers(searchInput?.text?.toString().orEmpty())
                }
            }
        }
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    private fun filterParsers(query: String) {
        val q = query.trim().lowercase()

        var filtered = allParsers
        if (selectedLanguage != LANG_ALL) {
            filtered = filtered.filter { p ->
                p.languageTag.ifEmpty { "??" } == selectedLanguage
            }
        }
        if (q.isNotBlank()) {
            filtered = filtered.filter { p ->
                p.displayName.lowercase().contains(q) ||
                    p.domain.lowercase().contains(q) ||
                    p.languageTag.lowercase().contains(q) ||
                    p.source.name.lowercase().contains(q)
            }
        }

        val addedMap: Map<String, CustomSource> = addedSources
            .filter { it.type == CustomSourceType.KOTATSU_PARSER }
            .mapNotNull { s -> s.parserSourceName?.let { name -> name to s } }
            .toMap()

        emptyView?.isVisible = filtered.isEmpty() && allParsers.isNotEmpty()
        recyclerView?.isVisible = filtered.isNotEmpty()

        if (adapter == null) {
            adapter = LibraryAdapter(filtered, addedMap, ::onParserTapped)
            recyclerView?.adapter = adapter
        } else {
            adapter?.update(filtered, addedMap)
        }

        // Stats chip
        val addedCount = addedSources.count { it.type == CustomSourceType.KOTATSU_PARSER }
        statsChip?.apply {
            isVisible = allParsers.isNotEmpty()
            text = getString(R.string.parser_stats_chip, allParsers.size, addedCount)
        }

        // Bulk action bar
        val showBulk = addedCount > 0
        bulkLayout?.isVisible = showBulk
        dividerBulk?.isVisible = showBulk
    }

    // ── Parser tap handling ───────────────────────────────────────────────────

    private fun onParserTapped(parser: KotatsuLibraryParser, addedSource: CustomSource?) {
        when {
            addedSource != null -> showManageDialog(parser, addedSource)
            isChangeMode        -> showChangeModeDialog(parser)
            else                -> showAddModeDialog(parser)
        }
    }

    /**
     * Manage dialog for parsers that are already added as a custom source.
     * Options: enable/disable, edit mirror URL, remove.
     */
    private fun showManageDialog(parser: KotatsuLibraryParser, source: CustomSource) {
        val toggleLabel = if (source.isEnabled)
            getString(R.string.manage_option_disable)
        else
            getString(R.string.manage_option_enable)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.manage_parser_title, parser.displayName))
            .setMessage(getString(R.string.manage_parser_added_as, source.displayName))
            .setItems(arrayOf(toggleLabel,
                getString(R.string.manage_option_edit_url),
                getString(R.string.manage_option_remove))) { _, which ->
                when (which) {
                    0 -> {
                        viewModel.toggleEnabled(source.id)
                        val msg = if (source.isEnabled)
                            getString(R.string.source_disabled_toast, source.displayName)
                        else
                            getString(R.string.source_enabled_toast, source.displayName)
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                    1 -> showEditUrlDialog(parser, source)
                    2 -> showRemoveConfirmDialog(parser, source)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Edit mirror URL dialog — user can change the base URL of an existing source. */
    private fun showEditUrlDialog(parser: KotatsuLibraryParser, source: CustomSource) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_source_url, null)
        val urlInput  = dialogView.findViewById<TextInputEditText>(R.id.input_edit_url)
        val urlLayout = dialogView.findViewById<TextInputLayout>(R.id.layout_edit_url)

        urlInput.setText(source.baseUrl)
        urlLayout.helperText = getString(R.string.library_parser_default_domain, parser.domain)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.manage_edit_url_title, parser.displayName))
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply_label) { _, _ ->
                val newUrl = urlInput.text?.toString().orEmpty()
                if (newUrl.isNotBlank()) viewModel.updateSourceUrl(source.id, newUrl)
            }
            .show()
    }

    /** Confirm before removing a source. */
    private fun showRemoveConfirmDialog(parser: KotatsuLibraryParser, source: CustomSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.manage_remove_confirm, source.displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.removeSource(source.id)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.source_removed_toast, source.displayName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .show()
    }

    /**
     * Change mode: lightweight confirm — keeps existing source URL, only swaps parser.
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
     * URL defaults to the parser's official domain but can be overridden for mirrors.
     */
    private fun showAddModeDialog(parser: KotatsuLibraryParser) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_library_source, null)

        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.input_lib_name)
        val urlInput  = dialogView.findViewById<TextInputEditText>(R.id.input_lib_url)
        val urlLayout = dialogView.findViewById<TextInputLayout>(R.id.layout_lib_url)
        val descInput = dialogView.findViewById<TextInputEditText>(R.id.input_lib_desc)

        nameInput.setText(parser.displayName)
        urlInput.setText("https://${parser.domain}")

        val langNote = parser.languageTag.ifEmpty { "" }.let { if (it.isNotEmpty()) " · ${it.uppercase()}" else "" }
        urlLayout.helperText = getString(R.string.library_parser_default_domain, parser.domain) + langNote

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.add_library_parser_title, parser.displayName))
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add_source_label) { _, _ ->
                viewModel.addKotatsuLibrarySource(
                    source      = parser.source,
                    mirrorUrl   = urlInput.text?.toString().orEmpty(),
                    name        = nameInput.text?.toString().orEmpty(),
                    description = descInput.text?.toString().orEmpty(),
                )
            }
            .show()
    }

    // ── RecyclerView adapter ──────────────────────────────────────────────────

    private class LibraryAdapter(
        private var items: List<KotatsuLibraryParser>,
        private var addedMap: Map<String, CustomSource>,
        private val onTap: (KotatsuLibraryParser, CustomSource?) -> Unit,
    ) : RecyclerView.Adapter<LibraryAdapter.VH>() {

        fun update(newItems: List<KotatsuLibraryParser>, newAddedMap: Map<String, CustomSource>) {
            items = newItems
            addedMap = newAddedMap
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_kotatsu_library_parser, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val parser = items[position]
            val added  = addedMap[parser.source.name]
            holder.bind(parser, added, onTap)
        }

        override fun getItemCount() = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView:     TextView      = view.findViewById(R.id.text_lib_parser_name)
            private val domainView:   TextView      = view.findViewById(R.id.text_lib_parser_domain)
            private val langView:     TextView      = view.findViewById(R.id.text_lib_parser_lang)
            private val addBtn:       MaterialButton = view.findViewById(R.id.btn_lib_add)
            private val statusLayout: View?          = view.findViewById(R.id.layout_added_status)
            private val statusDot:    View?          = view.findViewById(R.id.dot_status)
            private val statusText:   TextView?      = view.findViewById(R.id.text_added_status)

            fun bind(
                parser: KotatsuLibraryParser,
                added:  CustomSource?,
                onTap:  (KotatsuLibraryParser, CustomSource?) -> Unit,
            ) {
                nameView.text   = parser.displayName
                domainView.text = parser.domain
                langView.text   = parser.languageTag.ifEmpty { "??" }.take(3).uppercase()

                if (added != null) {
                    // Parser is already a custom source — show status and "Manage" button
                    val ctx = itemView.context
                    statusLayout?.isVisible = true
                    if (added.isEnabled) {
                        statusDot?.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                ctx.getColor(R.color.health_ok)
                            )
                        statusText?.text = ctx.getString(R.string.added_enabled_label)
                    } else {
                        statusDot?.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                ctx.getColor(R.color.health_error)
                            )
                        statusText?.text = ctx.getString(R.string.added_disabled_label)
                    }

                    // Change button to "Manage" with primary accent
                    addBtn.text = ctx.getString(R.string.manage_label)
                    val primaryColor = MaterialColors.getColor(
                        addBtn,
                        androidx.appcompat.R.attr.colorPrimary,
                        0,
                    )
                    addBtn.strokeColor = android.content.res.ColorStateList.valueOf(primaryColor)
                    addBtn.setTextColor(primaryColor)
                    // Switch to outlined style programmatically by clearing fill
                    addBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.TRANSPARENT
                    )

                } else {
                    statusLayout?.isVisible = false
                    addBtn.text = itemView.context.getString(R.string.add_source_label)
                    // Restore tonal fill
                    addBtn.backgroundTintList = null
                    addBtn.strokeColor = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.TRANSPARENT
                    )
                    addBtn.setTextColor(
                        MaterialColors.getColor(
                            addBtn,
                            com.google.android.material.R.attr.colorOnSecondaryContainer,
                            android.graphics.Color.WHITE,
                        )
                    )
                }

                addBtn.setOnClickListener { onTap(parser, added) }
                itemView.setOnClickListener { onTap(parser, added) }
            }
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val TAG = "KotatsuParserBrowserSheet"
        private const val ARG_EXISTING_SOURCE_ID = "existing_source_id"
        private const val LANG_ALL = "ALL"

        fun newInstance() = KotatsuParserBrowserSheet()

        fun newInstanceForChange(existingSourceId: Long) = KotatsuParserBrowserSheet().apply {
            arguments = Bundle().apply { putLong(ARG_EXISTING_SOURCE_ID, existingSourceId) }
        }
    }
}
