package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
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
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.domain.CustomSourceType

@AndroidEntryPoint
class AddCustomSourceSheet : BottomSheetDialogFragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

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

        // "Auto-detect" is first; KOTATSU_PARSER is set automatically and excluded from manual selection
        val autoDetectLabel = getString(R.string.auto_detect_label)
        val manualTypes = CustomSourceType.entries.filter {
            it != CustomSourceType.KOTATSU_PARSER && it != CustomSourceType.CUSTOM_TEMPLATE
        }
        val typeLabels = listOf(autoDetectLabel) + manualTypes.map { it.label }
        // Use a non-filtering adapter so ALL entries appear regardless of the current field text.
        // MaterialAutoCompleteTextView re-runs the adapter filter when the dropdown opens, using
        // the current field text as the constraint. The default "Auto-detect" text would filter
        // out every parser label (none start with "Auto-detect"), hiding the full list.
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
        // Default: auto-detect — most users will never need to change this
        typeDropdown.setText(autoDetectLabel, false)
        urlLayout.hint = getString(R.string.url_hint_auto_detect)

        typeDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                urlLayout.hint = getString(R.string.url_hint_auto_detect)
            } else {
                val selectedType = manualTypes[position - 1]
                urlLayout.hint = hintForType(selectedType)
            }
        }

        btnAdd.setOnClickListener {
            val name      = nameInput.text?.toString().orEmpty()
            val url       = urlInput.text?.toString().orEmpty()
            val desc      = descInput.text?.toString().orEmpty()
            val typeLabel = typeDropdown.text?.toString().orEmpty()
            urlLayout.error = null

            if (typeLabel == autoDetectLabel) {
                viewModel.detectAndAddSource(name, url, desc)
            } else {
                val type = manualTypes.find { it.label == typeLabel }
                    ?: CustomSourceType.WEBVIEW
                viewModel.addSource(name, url, type, desc)
            }
        }

        btnCancel.setOnClickListener { dismiss() }

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
                        // Show detection result toast when auto-detect was used
                        state.detectedType?.let { detected ->
                            val msg = if (detected == CustomSourceType.KOTATSU_PARSER && state.parserName != null) {
                                getString(R.string.detected_kotatsu_toast, state.parserName)
                            } else {
                                getString(R.string.detected_as_toast, detected.label)
                            }
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        }
                        // Warn if the site was unreachable during detection
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
