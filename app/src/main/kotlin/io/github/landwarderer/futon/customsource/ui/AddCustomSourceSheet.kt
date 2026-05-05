package io.github.landwarderer.futon.customsource.ui

  import android.os.Bundle
  import android.view.LayoutInflater
  import android.view.View
  import android.view.ViewGroup
  import android.widget.ArrayAdapter
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
          val manualTypes = CustomSourceType.entries.filter { it != CustomSourceType.KOTATSU_PARSER }
          val typeLabels = listOf(autoDetectLabel) + manualTypes.map { it.label }
          val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_simple, typeLabels)
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
                          // Show "Detected as: <type>" toast when auto-detect was used
                          state.detectedType?.let { detected ->
                              val msg = getString(R.string.detected_as_toast, detected.label)
                              Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
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
          CustomSourceType.MANGADEX_COMPATIBLE ->
              "API base URL (e.g. https://api.mangadex.org)"
          CustomSourceType.MADARA ->
              "Site URL — WordPress Madara (e.g. https://mangakakalot.com)"
          CustomSourceType.MANGATHEMESIA ->
              "Site URL — MangaThemesia (e.g. https://reaperscans.com)"
          CustomSourceType.MANGASTREAM ->
              "Site URL — MangaStream/WPManga (e.g. https://toonily.com)"
          CustomSourceType.GENKAN ->
              "Site URL — Genkan CMS (e.g. https://leviatanscans.com)"
          CustomSourceType.FOOLSLIDE2 ->
              "Site URL — FoolSlide2 (e.g. https://reader.fallenangels.com)"
          CustomSourceType.MANGANELO ->
              "Site URL — Manganelo / MangaKakalot (e.g. https://manganelo.com)"
          CustomSourceType.ZEROSCANS_API ->
              "API URL — Zeroscans JSON API (e.g. https://api.zeroscans.com)"
          CustomSourceType.LHTRANSLATION ->
              "Site URL — LHTranslation style (e.g. https://lhscans.com)"
          CustomSourceType.MANGASEE ->
              "Site URL — MangaSee / MangaLife (e.g. https://mangasee123.com)"
          CustomSourceType.GUYA ->
              "Site URL — Guya reader (e.g. https://guya.moe)"
          CustomSourceType.MANGAFIRE ->
              "Site URL — MangaFire style (e.g. https://mangafire.to)"
          CustomSourceType.MANGAPARK ->
              "Site URL — MangaPark (e.g. https://mangapark.net)"
          // WEBVIEW and any future types
          else ->
              "Website URL — opens in browser (e.g. https://example.com)"
      }

      companion object {
          const val TAG = "AddCustomSourceSheet"
          fun newInstance() = AddCustomSourceSheet()
      }
  }
  