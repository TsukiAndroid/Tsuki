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
import io.github.landwarderer.futon.customsource.domain.CustomSourceType

@AndroidEntryPoint
class EditCustomSourceSheet : BottomSheetDialogFragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

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

        // Build type dropdown — KOTATSU_PARSER can only be set via Re-detect; exclude from
        // manual selection so users cannot accidentally wipe the parserSourceName.
        val autoLabel = getString(R.string.auto_detect_label)
        val manualTypes = CustomSourceType.entries.filter { it != CustomSourceType.KOTATSU_PARSER }
        val typeLabels = listOf(autoLabel) + manualTypes.map { it.label }
        // Non-filtering adapter — see AddCustomSourceSheet for full explanation.
        val adapter = object : ArrayAdapter<String>(requireContext(), R.layout.item_dropdown_simple, typeLabels) {
            private val noOpFilter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
                    values = typeLabels
                    count = typeLabels.size
                }
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    notifyDataSetChanged()
                }
            }
            override fun getFilter(): Filter = noOpFilter
        }
        typeDropdown.setAdapter(adapter)

        // Pre-select the source's current type; fall back to Auto-detect for KOTATSU_PARSER
        // (it can only be re-detected, not manually chosen)
        val initialLabel = if (source.type == CustomSourceType.KOTATSU_PARSER) {
            autoLabel
        } else {
            source.type.label
        }
        typeDropdown.setText(initialLabel, false)
        urlLayout.hint = if (source.type == CustomSourceType.KOTATSU_PARSER) {
            getString(R.string.url_hint_auto_detect)
        } else {
            hintForType(source.type)
        }

        typeDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                urlLayout.hint = getString(R.string.url_hint_auto_detect)
            } else {
                urlLayout.hint = hintForType(manualTypes[position - 1])
            }
        }

        // Re-detect: runs the full Kotatsu + CMS pipeline and updates the dropdown.
        // The result is also persisted immediately so "Save" doesn't lose parserSourceName.
        btnRedetect.setOnClickListener {
            val url = urlInput.text?.toString().orEmpty()
            urlLayout.error = null
            viewModel.redetectType(sourceId, url) { detected ->
                val label = if (detected == CustomSourceType.KOTATSU_PARSER) autoLabel else detected.label
                typeDropdown.setText(label, false)
                urlLayout.hint = if (detected == CustomSourceType.KOTATSU_PARSER) {
                    getString(R.string.url_hint_auto_detect)
                } else {
                    hintForType(detected)
                }
                // Show an informative toast — for Kotatsu matches include the parser name
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
            val name      = nameInput.text?.toString().orEmpty()
            val url       = urlInput.text?.toString().orEmpty()
            val desc      = descInput.text?.toString().orEmpty()
            val typeLabel = typeDropdown.text?.toString().orEmpty()
            urlLayout.error = null

            val type = when {
                typeLabel == autoLabel -> {
                    // "Auto-detect" in the dropdown means the user hasn't manually overridden
                    // the type. Use whatever is currently stored (which may already be
                    // KOTATSU_PARSER from a Re-detect run, or the original type).
                    viewModel.findById(sourceId)?.type ?: source.type
                }
                else -> manualTypes.find { it.label == typeLabel } ?: source.type
            }
            viewModel.updateSource(sourceId, name, url, type, desc)
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
