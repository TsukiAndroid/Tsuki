package io.github.landwarderer.futon.customsource.ui

  import android.os.Bundle
  import android.view.LayoutInflater
  import android.view.View
  import android.view.ViewGroup
  import android.widget.ArrayAdapter
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

          // Build type dropdown — Auto-detect first, then all real types
          val autoLabel = getString(R.string.auto_detect_label)
          val typeLabels = listOf(autoLabel) + CustomSourceType.entries.map { it.label }
          val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_simple, typeLabels)
          typeDropdown.setAdapter(adapter)
          // Select the source's current type
          typeDropdown.setText(source.type.label, false)
          urlLayout.hint = hintForType(source.type)

          typeDropdown.setOnItemClickListener { _, _, position, _ ->
              if (position == 0) {
                  urlLayout.hint = getString(R.string.url_hint_auto_detect)
              } else {
                  urlLayout.hint = hintForType(CustomSourceType.entries[position - 1])
              }
          }

          // Re-detect: sniff the current URL and update the dropdown
          btnRedetect.setOnClickListener {
              val url = urlInput.text?.toString().orEmpty()
              urlLayout.error = null
              viewModel.redetectType(sourceId, url) { detected ->
                  typeDropdown.setText(detected.label, false)
                  urlLayout.hint = hintForType(detected)
                  val msg = getString(R.string.detected_as_toast, detected.label)
                  Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
              }
          }

          btnSave.setOnClickListener {
              val name      = nameInput.text?.toString().orEmpty()
              val url       = urlInput.text?.toString().orEmpty()
              val desc      = descInput.text?.toString().orEmpty()
              val typeLabel = typeDropdown.text?.toString().orEmpty()
              urlLayout.error = null

              val type = if (typeLabel == autoLabel) {
                  // If they left "Auto-detect" selected, keep the source's existing type
                  source.type
              } else {
                  CustomSourceType.entries.find { it.label == typeLabel } ?: source.type
              }
              viewModel.updateSource(sourceId, name, url, type, desc)
          }

          btnCancel.setOnClickListener { dismiss() }

          lifecycleScope.launch {
              viewModel.uiState.collect { state ->
                  val saving = state is CustomSourceViewModel.UiState.Detecting
                  btnSave.isEnabled     = !saving
                  btnRedetect.isEnabled = !saving
                  btnCancel.isEnabled   = !saving
                  btnSave.text = if (saving) getString(R.string.detecting_label)
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
  