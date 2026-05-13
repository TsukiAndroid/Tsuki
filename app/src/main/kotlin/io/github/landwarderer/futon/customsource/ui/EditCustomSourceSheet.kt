package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate

@AndroidEntryPoint
class EditCustomSourceSheet : BottomSheetDialogFragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

    /**
     * Represents one row in the parser picker dropdown (same model as AddCustomSourceSheet).
     */
    private sealed class ParserEntry(val displayLabel: String) {
        object AutoDetect : ParserEntry("Auto-detect (Recommended)")
        class SectionHeader(label: String) : ParserEntry(label)
        class BuiltIn(val type: CustomSourceType) : ParserEntry(type.label)
        class Imported(val template: ParserTemplate) : ParserEntry(template.name)
    }

    /** Tracks which entry the user has selected; pre-filled from the existing source. */
    private var selectedEntry: ParserEntry = ParserEntry.AutoDetect

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_edit_custom_source, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sourceId = arguments?.getLong(ARG_SOURCE_ID) ?: run { dismiss(); return }

        val nameInput    = view.findViewById<TextInputEditText>(R.id.input_source_name)
        val urlInput     = view.findViewById<TextInputEditText>(R.id.input_source_url)
        val urlLayout    = view.findViewById<TextInputLayout>(R.id.layout_source_url)
        val descInput    = view.findViewById<TextInputEditText>(R.id.input_source_description)
        val typeDropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_source_type)
        val btnSave      = view.findViewById<MaterialButton>(R.id.btn_save)
        val btnCancel    = view.findViewById<MaterialButton>(R.id.btn_cancel)
        val btnRedetect  = view.findViewById<MaterialButton>(R.id.btn_redetect)

        // Pre-fill with the existing source data
        val source = viewModel.findById(sourceId) ?: run { dismiss(); return }
        nameInput.setText(source.name)
        urlInput.setText(source.baseUrl)
        descInput.setText(source.description.orEmpty())

        // Build dropdown entries including both built-in parsers and imported templates
        val templates = ParserTemplateRepository.peekAll()
        val entries = buildEntries(templates)

        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            R.layout.item_dropdown_simple,
            entries.map { it.displayLabel },
        ) {
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
            override fun isEnabled(position: Int) = entries[position] !is ParserEntry.SectionHeader
        }
        typeDropdown.setAdapter(adapter)

        // Pre-select the source's current type.
        // KOTATSU_PARSER → auto-detect label (it can only be set via Re-detect).
        // CUSTOM_TEMPLATE → show the template name if we can find it; otherwise the generic label.
        selectedEntry = preSelectEntry(source, entries)
        typeDropdown.setText(selectedEntry.displayLabel, false)
        urlLayout.hint = hintForEntry(selectedEntry)

        typeDropdown.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            if (entry is ParserEntry.SectionHeader) return@setOnItemClickListener
            selectedEntry = entry
            urlLayout.hint = hintForEntry(entry)
        }

        // Re-detect: runs the full Kotatsu + CMS pipeline and updates the dropdown.
        btnRedetect.setOnClickListener {
            val url = urlInput.text?.toString().orEmpty()
            urlLayout.error = null
            viewModel.redetectType(sourceId, url) { detected ->
                val entry = when (detected) {
                    CustomSourceType.KOTATSU_PARSER -> ParserEntry.AutoDetect
                    CustomSourceType.CUSTOM_TEMPLATE -> {
                        val updatedSource = viewModel.findById(sourceId)
                        val templateName = updatedSource?.parserSourceName
                        templates.find { it.name == templateName }
                            ?.let { ParserEntry.Imported(it) }
                            ?: ParserEntry.AutoDetect
                    }
                    else -> entries.filterIsInstance<ParserEntry.BuiltIn>()
                        .find { it.type == detected }
                        ?: ParserEntry.AutoDetect
                }
                selectedEntry = entry
                typeDropdown.setText(entry.displayLabel, false)
                urlLayout.hint = hintForEntry(entry)

                val updatedSource = viewModel.findById(sourceId)
                val msg = if (detected == CustomSourceType.KOTATSU_PARSER &&
                    updatedSource?.parserSourceName != null
                ) {
                    getString(R.string.detected_kotatsu_toast, updatedSource.parserSourceName)
                } else {
                    getString(R.string.detected_as_toast, detected.label)
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        btnSave.setOnClickListener {
            val name = nameInput.text?.toString().orEmpty()
            val url  = urlInput.text?.toString().orEmpty()
            val desc = descInput.text?.toString().orEmpty()
            urlLayout.error = null

            when (val e = selectedEntry) {
                is ParserEntry.AutoDetect -> {
                    // Preserve whatever is currently stored (may be KOTATSU_PARSER from re-detect)
                    val storedType = viewModel.findById(sourceId)?.type ?: source.type
                    viewModel.updateSource(sourceId, name, url, storedType, desc)
                }
                is ParserEntry.BuiltIn -> viewModel.updateSource(sourceId, name, url, e.type, desc)
                is ParserEntry.Imported -> viewModel.updateSource(
                    sourceId, name, url, CustomSourceType.CUSTOM_TEMPLATE, desc,
                    parserSourceName = e.template.name,
                )
                is ParserEntry.SectionHeader -> { /* no-op */ }
            }
        }

        btnCancel.setOnClickListener { dismiss() }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val busy = state is CustomSourceViewModel.UiState.Detecting
                btnSave.isEnabled     = !busy
                btnRedetect.isEnabled = !busy
                btnCancel.isEnabled   = !busy
                btnSave.text = if (busy) getString(R.string.detecting_label)
                               else getString(R.string.save_changes_label)

                when (state) {
                    is CustomSourceViewModel.UiState.Error -> {
                        urlLayout.error = state.message
                        viewModel.resetState()
                    }
                    is CustomSourceViewModel.UiState.SourceUpdated -> {
                        state.detectedType?.let { detected ->
                            val msg = getString(R.string.detected_as_toast, detected.label)
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        }
                        dismiss()
                        viewModel.resetState()
                    }
                    else -> {}
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildEntries(templates: List<ParserTemplate>): List<ParserEntry> {
        val list = mutableListOf<ParserEntry>()
        list.add(ParserEntry.AutoDetect)
        list.add(ParserEntry.SectionHeader("── Built-in Parsers ──"))
        CustomSourceType.entries
            .filter { it != CustomSourceType.KOTATSU_PARSER && it != CustomSourceType.CUSTOM_TEMPLATE }
            .forEach { list.add(ParserEntry.BuiltIn(it)) }
        if (templates.isNotEmpty()) {
            list.add(ParserEntry.SectionHeader("── Imported Parsers ──"))
            templates.forEach { list.add(ParserEntry.Imported(it)) }
        }
        return list
    }

    /**
     * Determines the initial selection for the dropdown based on the existing source.
     * - KOTATSU_PARSER → Auto-detect (cannot be manually selected)
     * - CUSTOM_TEMPLATE → look up the template by [CustomSource.parserSourceName]
     * - Any other type → the matching BuiltIn entry
     */
    private fun preSelectEntry(source: CustomSource, entries: List<ParserEntry>): ParserEntry {
        return when (source.type) {
            CustomSourceType.KOTATSU_PARSER -> ParserEntry.AutoDetect
            CustomSourceType.CUSTOM_TEMPLATE -> {
                val templateName = source.parserSourceName
                entries.filterIsInstance<ParserEntry.Imported>()
                    .find { it.template.name == templateName }
                    ?: ParserEntry.AutoDetect
            }
            else -> entries.filterIsInstance<ParserEntry.BuiltIn>()
                .find { it.type == source.type }
                ?: ParserEntry.AutoDetect
        }
    }

    private fun hintForEntry(entry: ParserEntry): String = when (entry) {
        is ParserEntry.AutoDetect -> getString(R.string.url_hint_auto_detect)
        is ParserEntry.Imported   -> "Site URL for ${entry.template.name} (e.g. https://example.com)"
        is ParserEntry.BuiltIn    -> hintForType(entry.type)
        else                      -> getString(R.string.url_hint_auto_detect)
    }

    private fun hintForType(type: CustomSourceType): String = when (type) {
        CustomSourceType.MANGADEX_COMPATIBLE -> "API base URL (e.g. https://api.mangadex.org)"
        CustomSourceType.MADARA              -> "Site URL — WordPress Madara (e.g. https://mangakakalot.com)"
        CustomSourceType.MANGATHEMESIA       -> "Site URL — MangaThemesia (e.g. https://reaperscans.com)"
        CustomSourceType.MANGASTREAM         -> "Site URL — MangaStream/WPManga (e.g. https://toonily.com)"
        CustomSourceType.GENKAN              -> "Site URL — Genkan CMS (e.g. https://leviatanscans.com)"
        CustomSourceType.FOOLSLIDE2          -> "Site URL — FoolSlide2 (e.g. https://reader.fallenangels.com)"
        CustomSourceType.MANGANELO           -> "Site URL — Manganelo / MangaKakalot (e.g. https://manganelo.com)"
        CustomSourceType.ZEROSCANS_API       -> "API URL — Zeroscans JSON API (e.g. https://api.zeroscans.com)"
        CustomSourceType.LHTRANSLATION       -> "Site URL — LHTranslation style (e.g. https://lhscans.com)"
        CustomSourceType.MANGASEE            -> "Site URL — MangaSee / MangaLife (e.g. https://mangasee123.com)"
        CustomSourceType.GUYA                -> "Site URL — Guya reader (e.g. https://guya.moe)"
        CustomSourceType.MANGAFIRE           -> "Site URL — MangaFire style (e.g. https://mangafire.to)"
        CustomSourceType.MANGAPARK           -> "Site URL — MangaPark (e.g. https://mangapark.net)"
        CustomSourceType.COMIXTO             -> "Site URL — Comix.to style (e.g. https://comix.to)"
        CustomSourceType.COMICK_API          -> "Site URL — ComicK (e.g. https://comick.io)"
        CustomSourceType.BATO                -> "Site URL — Bato.to (e.g. https://bato.to)"
        CustomSourceType.NINEMANGA           -> "Site URL — NineManga (e.g. https://en.ninemanga.com)"
        CustomSourceType.MANGAHOST           -> "Site URL — MangaHost / Leitor.net (e.g. https://mangahost4.com)"
        CustomSourceType.MANGAREADER         -> "Site URL — MangaReader style (e.g. https://mangareader.to)"
        CustomSourceType.MANGAFOX            -> "Site URL — FanFox / MangaFox (e.g. https://fanfox.net)"
        CustomSourceType.TCBSCANS            -> "Site URL — TCBScans static site (e.g. https://tcbscans.me)"
        CustomSourceType.MANGANATO           -> "Site URL — MangaNato / MangaBat (e.g. https://manganato.com)"
        CustomSourceType.READERFRONT         -> "Site URL — ReaderFront GraphQL (e.g. https://jmanga.me)"
        CustomSourceType.KISSMANGA           -> "Site URL — KissManga style (e.g. https://kissmanga.in)"
        CustomSourceType.CUBARI              -> "Site URL — Cubari / Gist reader (e.g. https://cubari.moe)"
        CustomSourceType.MANGAPILL           -> "Site URL — MangaPill (e.g. https://mangapill.com)"
        CustomSourceType.MANGAHUB            -> "Site URL — MangaHub (e.g. https://mangahub.io)"
        CustomSourceType.MANGAHERE           -> "Site URL — MangaHere / Foxaholic (e.g. https://www.mangahere.cc)"
        CustomSourceType.MANGALIB            -> "Site URL — MangaLib Russian (e.g. https://mangalib.me)"
        CustomSourceType.MANGAGO             -> "Site URL — Mangago (e.g. https://www.mangago.me)"
        CustomSourceType.MANGAFREAK          -> "Site URL — MangaFreak (e.g. https://mangafreak.net)"
        CustomSourceType.MANGAOWL            -> "Site URL — MangaOwl (e.g. https://mangaowl.net)"
        CustomSourceType.NETTRUYEN           -> "Site URL — NetTruyen Vietnamese (e.g. https://nettruyenvn.com)"
        CustomSourceType.TRUYENQQ            -> "Site URL — TruyenQQ Vietnamese (e.g. https://truyenqq.com.vn)"
        CustomSourceType.MANGAKATANA         -> "Site URL — MangaKatana (e.g. https://mangakatana.com)"
        CustomSourceType.ZEISTMANGA          -> "Site URL — Blogger/ZeistManga (e.g. https://zeistmanga.com)"
        CustomSourceType.KEYOAPP             -> "Site URL — Keyoapp CMS (e.g. https://asuracomic.net)"
        CustomSourceType.HEANCMS             -> "Site URL — HeanCms (e.g. https://reaperscans.com) — API at api.{domain}"
        CustomSourceType.WPCOMICS            -> "Site URL — WpComics Vietnamese (e.g. https://wpcomics.vn)"
        CustomSourceType.MMRCMS              -> "Site URL — Mmrcms PHP (e.g. https://isekaiscan.to)"
        CustomSourceType.MADTHEME            -> "Site URL — Madtheme (e.g. https://mangakomi.io)"
        CustomSourceType.MANGABOX            -> "Site URL — Mangabox / Mangakakalot.to (e.g. https://mangakakalot.to)"
        CustomSourceType.LILIANA             -> "Site URL — Liliana CMS (e.g. https://manga-raw.club)"
        CustomSourceType.IKEN                -> "Site URL — Iken CMS (e.g. https://mangaclash.com) — API at api.{domain}"
        CustomSourceType.SCAN                -> "Site URL — Scan Scanlation CMS (e.g. https://sushiscan.net)"
        CustomSourceType.PIZZAREADER         -> "Site URL — PizzaReader (e.g. https://reader.pizzascans.net)"
        CustomSourceType.FMREADER            -> "Site URL — FmReader PHP (e.g. https://fmreader.com)"
        CustomSourceType.GATTSU              -> "Site URL — Gattsu CMS (e.g. https://gattsu-scans.com)"
        CustomSourceType.ANIMEBOOTSTRAP      -> "Site URL — AnimeBootstrap theme (e.g. https://animebootstrap.net)"
        CustomSourceType.WEBVIEW             -> "Website URL — opens in browser (e.g. https://example.com)"
        else                                 -> "Website URL (e.g. https://example.com)"
    }

    companion object {
        const val TAG = "EditCustomSourceSheet"
        private const val ARG_SOURCE_ID = "source_id"

        fun newInstance(sourceId: Long) = EditCustomSourceSheet().apply {
            arguments = Bundle().apply { putLong(ARG_SOURCE_ID, sourceId) }
        }
    }
}
