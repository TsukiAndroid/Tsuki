package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate

@AndroidEntryPoint
class AddCustomSourceSheet : BottomSheetDialogFragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

    /**
     * Represents one row in the parser picker dropdown.
     * Section headers are not clickable; the rest carry enough data to
     * reconstruct the [CustomSourceType] and [CustomSource.parserSourceName]
     * needed when the user taps "Add".
     */
    private sealed class ParserEntry(val displayLabel: String) {
        object AutoDetect : ParserEntry("Auto-detect (Recommended)")
        class SectionHeader(label: String) : ParserEntry(label)
        class BuiltIn(val type: CustomSourceType) : ParserEntry(type.label)
        class Imported(val template: ParserTemplate) : ParserEntry(template.name)
    }

    /** Tracks which entry is currently selected in the dropdown. */
    private var selectedEntry: ParserEntry = ParserEntry.AutoDetect

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_add_custom_source, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val nameInput    = view.findViewById<TextInputEditText>(R.id.input_source_name)
        val urlInput     = view.findViewById<TextInputEditText>(R.id.input_source_url)
        val urlLayout    = view.findViewById<TextInputLayout>(R.id.layout_source_url)
        val descInput    = view.findViewById<TextInputEditText>(R.id.input_source_description)
        val typeDropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_source_type)
        val btnAdd       = view.findViewById<MaterialButton>(R.id.btn_add_source)
        val btnCancel    = view.findViewById<MaterialButton>(R.id.btn_cancel)
        val matchChip    = view.findViewById<Chip>(R.id.chip_url_match)

        // Build the entries list including both built-in parsers and imported templates.
        // Templates are fetched at open-time from the singleton repository.
        val templates = ParserTemplateRepository.peekAll()
        val entries = buildEntries(templates)

        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            R.layout.item_dropdown_simple,
            entries.map { it.displayLabel },
        ) {
            // No-op filter: always show all items regardless of the current field text.
            // Without this, MaterialAutoCompleteTextView would filter by the typed text and
            // hide every parser label when the field still shows "Auto-detect".
            private val noOpFilter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
                    values = entries.map { it.displayLabel }
                    count = entries.size
                }
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }
            }
            override fun getFilter(): Filter = noOpFilter
            // Section headers are not selectable
            override fun isEnabled(position: Int) = entries[position] !is ParserEntry.SectionHeader
        }
        typeDropdown.setAdapter(adapter)
        typeDropdown.setText(ParserEntry.AutoDetect.displayLabel, false)
        urlLayout.hint = getString(R.string.url_hint_auto_detect)

        // Ensure library parsers are ready for instant domain matching as the user types.
        viewModel.loadKotatsuLibraryParsers()

        // Live URL → parser detection chip: appears instantly when the entered domain
        // matches a known kotatsu-parsers-redo library parser (no network call).
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val url = s?.toString().orEmpty()
                val matched = viewModel.quickMatchUrl(url)
                if (matched != null) {
                    matchChip?.isVisible = true
                    matchChip?.text = getString(
                        R.string.url_matched_parser,
                        matched.displayName,
                        matched.domain,
                        matched.languageTag.ifEmpty { "??" }.uppercase(),
                    )
                    // Auto-fill the name field if the user hasn't typed one yet
                    if (nameInput.text.isNullOrBlank()) {
                        nameInput.setText(matched.displayName)
                    }
                } else {
                    matchChip?.isVisible = false
                }
            }
        })

        typeDropdown.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            if (entry is ParserEntry.SectionHeader) return@setOnItemClickListener
            selectedEntry = entry
            urlLayout.hint = hintForEntry(entry)
        }

        btnAdd.setOnClickListener {
            val name = nameInput.text?.toString().orEmpty()
            val url  = urlInput.text?.toString().orEmpty()
            val desc = descInput.text?.toString().orEmpty()
            urlLayout.error = null

            when (val e = selectedEntry) {
                is ParserEntry.AutoDetect -> viewModel.detectAndAddSource(name, url, desc)
                is ParserEntry.BuiltIn    -> viewModel.addSource(name, url, e.type, desc)
                is ParserEntry.Imported   -> viewModel.addSource(
                    name, url, CustomSourceType.CUSTOM_TEMPLATE, desc,
                    parserSourceName = e.template.name,
                )
                is ParserEntry.SectionHeader -> { /* no-op */ }
            }
        }

        btnCancel.setOnClickListener { dismiss() }

        // "Browse Kotatsu Library" shortcut — opens the full library browser
        // where the user can pick any library parser and optionally enter a mirror URL.
        view.findViewById<MaterialButton>(R.id.btn_browse_library)?.setOnClickListener {
            dismiss()
            KotatsuParserBrowserSheet.newInstance()
                .show(parentFragmentManager, KotatsuParserBrowserSheet.TAG)
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val detecting = state is CustomSourceViewModel.UiState.Detecting
                btnAdd.isEnabled    = !detecting
                btnCancel.isEnabled = !detecting
                btnAdd.text = if (detecting) {
                    getString(R.string.detecting_label)
                } else {
                    getString(R.string.add_source_label)
                }

                when (state) {
                    is CustomSourceViewModel.UiState.Error -> {
                        urlLayout.error = state.message
                        viewModel.resetState()
                    }
                    is CustomSourceViewModel.UiState.SourceAdded -> {
                        state.detectedType?.let { detected ->
                            val msg = when {
                                detected == CustomSourceType.KOTATSU_PARSER && state.parserName != null ->
                                    getString(R.string.detected_kotatsu_toast, state.parserName)
                                detected == CustomSourceType.CUSTOM_TEMPLATE && state.parserName != null ->
                                    getString(R.string.detected_template_toast, state.parserName)
                                else ->
                                    getString(R.string.detected_as_toast, detected.label)
                            }
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        }
                        if (!state.siteReachable) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.site_unreachable_warning),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        dismiss()
                        viewModel.resetState()
                    }
                    else -> { /* Idle or Detecting */ }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the flat list of dropdown entries with section headers.
     * Layout:
     *   Auto-detect (Recommended)
     *   ── Built-in Parsers ──        ← header (not clickable)
     *   ManualType1, ManualType2, …
     *   ── Imported Parsers ──         ← header (only if templates exist)
     *   Template1, Template2, …
     */
    private fun buildEntries(templates: List<ParserTemplate>): List<ParserEntry> {
        val list = mutableListOf<ParserEntry>()
        list.add(ParserEntry.AutoDetect)
        list.add(ParserEntry.SectionHeader("── Built-in Parsers ──"))
        // Exclude broken parsers (BATO / MANGAPARK are @Broken upstream)
        CustomSourceType.entries
            .filter {
                it != CustomSourceType.KOTATSU_PARSER &&
                    it != CustomSourceType.CUSTOM_TEMPLATE &&
                    it != CustomSourceType.BATO &&
                    it != CustomSourceType.MANGAPARK
            }
            .forEach { list.add(ParserEntry.BuiltIn(it)) }
        if (templates.isNotEmpty()) {
            list.add(ParserEntry.SectionHeader("── Imported Parsers ──"))
            templates.forEach { list.add(ParserEntry.Imported(it)) }
        }
        return list
    }

    private fun hintForEntry(entry: ParserEntry): String = when (entry) {
        is ParserEntry.AutoDetect -> getString(R.string.url_hint_auto_detect)
        is ParserEntry.Imported   -> "Site URL for ${entry.template.name} (e.g. https://example.com)"
        is ParserEntry.BuiltIn    -> hintForType(entry.type)
        else                      -> getString(R.string.url_hint_auto_detect)
    }

    private fun hintForType(type: CustomSourceType): String = when (type) {
        CustomSourceType.MANGADEX_COMPATIBLE -> "API base URL (e.g. https://api.mangadex.org)"
        CustomSourceType.MADARA             -> "Site URL — WordPress Madara (e.g. https://mangakakalot.com)"
        CustomSourceType.MANGATHEMESIA      -> "Site URL — MangaThemesia (e.g. https://reaperscans.com)"
        CustomSourceType.MANGASTREAM        -> "Site URL — MangaStream/WPManga (e.g. https://toonily.com)"
        CustomSourceType.GENKAN             -> "Site URL — Genkan CMS (e.g. https://leviatanscans.com)"
        CustomSourceType.FOOLSLIDE2         -> "Site URL — FoolSlide2 (e.g. https://reader.fallenangels.com)"
        CustomSourceType.MANGANELO          -> "Site URL — Manganelo / MangaKakalot (e.g. https://manganelo.com)"
        CustomSourceType.ZEROSCANS_API      -> "API URL — Zeroscans JSON API (e.g. https://api.zeroscans.com)"
        CustomSourceType.LHTRANSLATION      -> "Site URL — LHTranslation style (e.g. https://lhscans.com)"
        CustomSourceType.MANGASEE           -> "Site URL — MangaSee / MangaLife (e.g. https://mangasee123.com)"
        CustomSourceType.GUYA               -> "Site URL — Guya reader (e.g. https://guya.moe)"
        CustomSourceType.MANGAFIRE          -> "Site URL — MangaFire style (e.g. https://mangafire.to)"
        CustomSourceType.MANGAPARK          -> "Site URL — MangaPark (e.g. https://mangapark.net)"
        CustomSourceType.COMIXTO            -> "Site URL — Comix.to style (e.g. https://comix.to)"
        CustomSourceType.COMICK_API         -> "Site URL — ComicK (e.g. https://comick.io)"
        CustomSourceType.BATO               -> "Site URL — Bato.to (e.g. https://bato.to)"
        CustomSourceType.NINEMANGA          -> "Site URL — NineManga (e.g. https://en.ninemanga.com)"
        CustomSourceType.MANGAHOST          -> "Site URL — MangaHost / Leitor.net (e.g. https://mangahost4.com)"
        CustomSourceType.MANGAREADER        -> "Site URL — MangaReader style (e.g. https://mangareader.to)"
        CustomSourceType.MANGAFOX           -> "Site URL — FanFox / MangaFox (e.g. https://fanfox.net)"
        CustomSourceType.TCBSCANS           -> "Site URL — TCBScans static site (e.g. https://tcbscans.me)"
        CustomSourceType.MANGANATO          -> "Site URL — MangaNato / MangaBat (e.g. https://manganato.com)"
        CustomSourceType.READERFRONT        -> "Site URL — ReaderFront GraphQL (e.g. https://jmanga.me)"
        CustomSourceType.KISSMANGA          -> "Site URL — KissManga style (e.g. https://kissmanga.in)"
        CustomSourceType.CUBARI             -> "Site URL — Cubari / Gist reader (e.g. https://cubari.moe)"
        CustomSourceType.MANGAPILL          -> "Site URL — MangaPill (e.g. https://mangapill.com)"
        CustomSourceType.MANGAHUB           -> "Site URL — MangaHub (e.g. https://mangahub.io)"
        CustomSourceType.MANGAHERE          -> "Site URL — MangaHere / Foxaholic (e.g. https://www.mangahere.cc)"
        CustomSourceType.MANGALIB           -> "Site URL — MangaLib Russian (e.g. https://mangalib.me)"
        CustomSourceType.MANGAGO            -> "Site URL — Mangago (e.g. https://www.mangago.me)"
        CustomSourceType.MANGAFREAK         -> "Site URL — MangaFreak (e.g. https://mangafreak.net)"
        CustomSourceType.MANGAOWL           -> "Site URL — MangaOwl (e.g. https://mangaowl.net)"
        CustomSourceType.NETTRUYEN          -> "Site URL — NetTruyen Vietnamese (e.g. https://nettruyenvn.com)"
        CustomSourceType.TRUYENQQ           -> "Site URL — TruyenQQ Vietnamese (e.g. https://truyenqq.com.vn)"
        CustomSourceType.MANGAKATANA        -> "Site URL — MangaKatana (e.g. https://mangakatana.com)"
        CustomSourceType.ZEISTMANGA         -> "Site URL — Blogger/ZeistManga (e.g. https://zeistmanga.com)"
        CustomSourceType.KEYOAPP            -> "Site URL — Keyoapp CMS (e.g. https://asuracomic.net)"
        CustomSourceType.HEANCMS            -> "Site URL — HeanCms (e.g. https://reaperscans.com) — API at api.{domain}"
        CustomSourceType.WPCOMICS           -> "Site URL — WpComics Vietnamese (e.g. https://wpcomics.vn)"
        CustomSourceType.MMRCMS             -> "Site URL — Mmrcms PHP (e.g. https://isekaiscan.to)"
        CustomSourceType.MADTHEME           -> "Site URL — Madtheme (e.g. https://mangakomi.io)"
        CustomSourceType.MANGABOX           -> "Site URL — Mangabox / Mangakakalot.to (e.g. https://mangakakalot.to)"
        CustomSourceType.LILIANA            -> "Site URL — Liliana CMS (e.g. https://manga-raw.club)"
        CustomSourceType.IKEN               -> "Site URL — Iken CMS (e.g. https://mangaclash.com) — API at api.{domain}"
        CustomSourceType.SCAN               -> "Site URL — Scan Scanlation CMS (e.g. https://sushiscan.net)"
        CustomSourceType.PIZZAREADER        -> "Site URL — PizzaReader (e.g. https://reader.pizzascans.net)"
        CustomSourceType.FMREADER           -> "Site URL — FmReader PHP (e.g. https://fmreader.com)"
        CustomSourceType.GATTSU             -> "Site URL — Gattsu CMS (e.g. https://gattsu-scans.com)"
        CustomSourceType.ANIMEBOOTSTRAP     -> "Site URL — AnimeBootstrap theme (e.g. https://animebootstrap.net)"
        CustomSourceType.WEBVIEW            -> "Website URL — opens in browser (e.g. https://example.com)"
        else                                -> "Website URL (e.g. https://example.com)"
    }

    companion object {
        const val TAG = "AddCustomSourceSheet"
        fun newInstance() = AddCustomSourceSheet()
    }
}
