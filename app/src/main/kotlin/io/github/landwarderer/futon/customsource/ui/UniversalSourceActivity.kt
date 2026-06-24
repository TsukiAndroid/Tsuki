package io.github.landwarderer.futon.customsource.ui

  import android.content.res.ColorStateList
  import android.os.Bundle
  import android.util.TypedValue
  import android.view.View
  import android.widget.Toast
  import androidx.activity.viewModels
  import androidx.appcompat.app.AppCompatActivity
  import androidx.core.content.ContextCompat
  import androidx.lifecycle.Lifecycle
  import androidx.lifecycle.lifecycleScope
  import androidx.lifecycle.repeatOnLifecycle
  import com.google.android.material.R as MaterialR
  import com.google.android.material.textfield.TextInputLayout
  import dagger.hilt.android.AndroidEntryPoint
  import kotlinx.coroutines.launch
  import io.github.landwarderer.futon.R
  import io.github.landwarderer.futon.customsource.data.SiteAutoDetector
  import io.github.landwarderer.futon.customsource.data.SiteAutoDetector.Confidence
  import io.github.landwarderer.futon.databinding.ActivityUniversalSourceBinding
import io.github.landwarderer.futon.customsource.ui.visualpicker.VisualRuleBuilderActivity

  /**
   * Form-based "Universal Source" wizard.
   *
   * The user can either:
   *  A) Paste the site URL and tap "Auto-detect selectors" — the app fetches
   *     the site HTML, fingerprints the CMS theme (Madara, MangaThemesia,
   *     MangaStream, Keyoapp, MadTheme, Mmrcms, …), and routes to the
   *     battle-tested dedicated parser for that theme. Pre-fills all CSS-selector
   *     fields with a per-field confidence indicator for review.
   *  B) Fill in the fields manually.
   *
   * Tapping Create saves the source. If a known CMS is detected, the proven
   * theme parser is used directly (no template JSON needed). For unknown sites
   * the improved [TemplateHtmlParser] is used as a capable fallback.
   */
  @AndroidEntryPoint
  class UniversalSourceActivity : AppCompatActivity() {

      private lateinit var binding: ActivityUniversalSourceBinding
      private val viewModel: UniversalSourceViewModel by viewModels()

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          binding = ActivityUniversalSourceBinding.inflate(layoutInflater)
          setContentView(binding.root)

          setSupportActionBar(binding.toolbar)
          supportActionBar?.setDisplayHomeAsUpEnabled(true)
          supportActionBar?.title = getString(R.string.universal_source_title)

          binding.btnAutoDetect.setOnClickListener {
              viewModel.autoDetect(binding.editBaseUrl.text?.toString().orEmpty())
          }
          binding.btnCreate.setOnClickListener { submitForm() }
        binding.btnPickElements.setOnClickListener {
            val url = binding.editBaseUrl.text?.toString().orEmpty().trim()
            val name = binding.editName.text?.toString().orEmpty().trim()
            if (url.isBlank()) {
                Toast.makeText(this, getString(R.string.browser_source_url_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(VisualRuleBuilderActivity.createIntent(this, url, name))
        }

          lifecycleScope.launch {
              repeatOnLifecycle(Lifecycle.State.STARTED) {
                  launch { observeCreateResult() }
                  launch { observeAutoDetect() }
                  launch { observeProgressStep() }
              }
          }
      }

      override fun onSupportNavigateUp(): Boolean { finish(); return true }

      // ── Observers ─────────────────────────────────────────────────────────────

      private suspend fun observeCreateResult() {
          viewModel.result.collect { result ->
              when (result) {
                  is UniversalSourceViewModel.Result.Idle    -> Unit
                  is UniversalSourceViewModel.Result.Error   -> {
                      Toast.makeText(this@UniversalSourceActivity, result.message, Toast.LENGTH_LONG).show()
                      viewModel.resetResult()
                  }
                  is UniversalSourceViewModel.Result.Success -> {
                      Toast.makeText(
                          this@UniversalSourceActivity,
                          "\"${result.name}\" added · ${result.parserLabel}",
                          Toast.LENGTH_LONG,
                      ).show()
                      finish()
                  }
              }
          }
      }

      private suspend fun observeProgressStep() {
          viewModel.progressStep.collect { step ->
              if (step.isNotBlank()) {
                  // Keep the progress bar visible and update the status text live
                  showStatusCard(step, isError = false)
              }
          }
      }

      private suspend fun observeAutoDetect() {
          viewModel.autoDetectState.collect { state ->
              when (state) {
                  is UniversalSourceViewModel.AutoDetectState.Idle -> {
                      binding.autoDetectProgress.visibility = View.GONE
                      binding.autoDetectStatusCard.visibility = View.GONE
                      binding.btnAutoDetect.isEnabled = true
                  }
                  is UniversalSourceViewModel.AutoDetectState.Loading -> {
                      binding.autoDetectProgress.visibility = View.VISIBLE
                      binding.btnAutoDetect.isEnabled = false
                      binding.btnAutoDetect.text = getString(R.string.universal_source_auto_detecting)
                      // Show status card with current step — never show a frozen blank screen
                      showStatusCard(
                          binding.autoDetectStatusText.text?.toString()?.takeIf { it.isNotBlank() }
                              ?: "\uD83CDF19 Starting analysis...",
                          isError = false,
                      )
                  }
                  is UniversalSourceViewModel.AutoDetectState.Done -> {
                      binding.autoDetectProgress.visibility = View.GONE
                      binding.btnAutoDetect.isEnabled = true
                      binding.btnAutoDetect.text = getString(R.string.universal_source_auto_detect_btn)
                      applyDetectedFields(state.fields)
                      applyConfidenceHints(state.fields)
                      val cmsName = cmsDisplayName(state.fields.cmsType)
                      val statusMsg = if (cmsName != null) {
                          "\u2713 $cmsName theme detected \u2014 proven parser selected. Tap Create."
                      } else {
                          getString(R.string.universal_source_auto_detect_done)
                      }
                      showStatusCard(statusMsg, isError = false)
                      viewModel.resetAutoDetect()
                  }
                  is UniversalSourceViewModel.AutoDetectState.Error -> {
                      binding.autoDetectProgress.visibility = View.GONE
                      binding.btnAutoDetect.isEnabled = true
                      binding.btnAutoDetect.text = getString(R.string.universal_source_auto_detect_btn)
                      showStatusCard(state.message, isError = true)
                      viewModel.resetAutoDetect()
                  }
              }
          }
      }

      // ── Field population ──────────────────────────────────────────────────────

      private fun applyDetectedFields(fields: SiteAutoDetector.DetectedFields) {
          if (fields.siteName.isNotEmpty() && binding.editName.text.isNullOrBlank()) {
              binding.editName.setText(fields.siteName)
          }
          if (fields.listPath.isNotEmpty())          binding.editListPath.setText(fields.listPath)
          if (fields.searchPath.isNotEmpty())        binding.editSearchPath.setText(fields.searchPath)
          if (fields.cardSelector.isNotEmpty())      binding.editCardSelector.setText(fields.cardSelector)
          if (fields.titleSelector.isNotEmpty())     binding.editTitleSelector.setText(fields.titleSelector)
          if (fields.coverSelector.isNotEmpty())     binding.editCoverSelector.setText(fields.coverSelector)
          if (fields.detailTitle.isNotEmpty())       binding.editDetailTitle.setText(fields.detailTitle)
          if (fields.description.isNotEmpty())       binding.editDescription.setText(fields.description)
          if (fields.chapterSelector.isNotEmpty())   binding.editChapterSelector.setText(fields.chapterSelector)
          if (fields.pageImageSelector.isNotEmpty()) binding.editPageImage.setText(fields.pageImageSelector)
      }

      // ── Confidence hints ──────────────────────────────────────────────────────

      /**
       * Applies a coloured helper-text badge under each selector field to show
       * how reliable the auto-detected value is.
       *
       *  ✓ High confidence  → green  (specific class/ID selector found)
       *  ⚠ Best guess       → amber  (generic tag-only selector; review recommended)
       *  (blank)             → no badge (field was not detected)
       */
      private fun applyConfidenceHints(fields: SiteAutoDetector.DetectedFields) {
          data class FieldSpec(
              val layout: TextInputLayout,
              val key: String,
              val originalHelper: String,
          )

          val specs = listOf(
              FieldSpec(binding.layoutCardSelector,    "cardSelector",      getString(R.string.field_card_selector_helper)),
              FieldSpec(binding.layoutTitleSelector,   "titleSelector",     getString(R.string.field_title_selector_helper)),
              FieldSpec(binding.layoutCoverSelector,   "coverSelector",     getString(R.string.field_cover_selector_helper)),
              FieldSpec(binding.layoutDetailTitle,     "detailTitle",       getString(R.string.field_detail_title_helper)),
              FieldSpec(binding.layoutDescription,     "description",       getString(R.string.field_description_helper)),
              FieldSpec(binding.layoutChapterSelector, "chapterSelector",   getString(R.string.field_chapter_selector_helper)),
              FieldSpec(binding.layoutPageImage,       "pageImageSelector", getString(R.string.field_page_image_helper)),
              FieldSpec(binding.layoutListPath,        "listPath",          getString(R.string.field_list_path_helper)),
              FieldSpec(binding.layoutSearchPath,      "searchPath",        getString(R.string.field_search_path_helper)),
          )

          for (spec in specs) {
              val confidence = fields.fieldConfidence[spec.key] ?: Confidence.LOW
              when (confidence) {
                  Confidence.HIGH -> {
                      spec.layout.helperText = getString(R.string.confidence_high)
                      spec.layout.setHelperTextColor(
                          ColorStateList.valueOf(resolveColor(MaterialR.attr.colorTertiary))
                      )
                  }
                  Confidence.MEDIUM -> {
                      spec.layout.helperText = getString(R.string.confidence_medium)
                      spec.layout.setHelperTextColor(
                          ColorStateList.valueOf(resolveColor(com.google.android.material.R.attr.colorSecondary))
                      )
                  }
                  Confidence.LOW -> {
                      spec.layout.helperText = spec.originalHelper
                      spec.layout.setHelperTextColor(null)
                  }
              }
          }
      }

      private fun resolveColor(attr: Int): Int {
          val tv = TypedValue()
          theme.resolveAttribute(attr, tv, true)
          return tv.data
      }

      // ── Status card ───────────────────────────────────────────────────────────

      private fun showStatusCard(message: String, isError: Boolean) {
          binding.autoDetectStatusText.text = message
          val colorAttr = if (isError) MaterialR.attr.colorErrorContainer
                          else         MaterialR.attr.colorSecondaryContainer
          val tv = TypedValue()
          theme.resolveAttribute(colorAttr, tv, true)
          binding.autoDetectStatusCard.setCardBackgroundColor(tv.data)
          binding.autoDetectStatusCard.visibility = View.VISIBLE
      }

      // ── CMS display name (for status card feedback) ───────────────────────────

      /**
       * Returns a short human-readable name for the detected CMS theme so the
       * auto-detect status card can tell the user which proven parser was selected.
       * Returns null for unknown/generic sites (status card shows the generic message).
       */
      private fun cmsDisplayName(cmsType: SiteAutoDetector.CmsType): String? = when (cmsType) {
          SiteAutoDetector.CmsType.MADARA          -> "WordPress Madara"
          SiteAutoDetector.CmsType.MANGA_THEMESIA  -> "MangaThemesia"
          SiteAutoDetector.CmsType.MANGA_STREAM    -> "MangaStream"
          SiteAutoDetector.CmsType.KEYOAPP         -> "Keyoapp"
          SiteAutoDetector.CmsType.MAD_THEME       -> "Madtheme"
          SiteAutoDetector.CmsType.MMRCMS          -> "MMRCMS"
          SiteAutoDetector.CmsType.WORDPRESS_GENERIC,
          SiteAutoDetector.CmsType.UNKNOWN         -> null
      }

      // ── Submit ────────────────────────────────────────────────────────────────

      private fun submitForm() {
          binding.layoutName.error      = null
          binding.layoutBaseUrl.error   = null
          binding.layoutPageImage.error = null

          viewModel.create(
              name              = binding.editName.text?.toString().orEmpty(),
              baseUrl           = binding.editBaseUrl.text?.toString().orEmpty(),
              listPath          = binding.editListPath.text?.toString().orEmpty(),
              searchPath        = binding.editSearchPath.text?.toString().orEmpty(),
              cardSelector      = binding.editCardSelector.text?.toString().orEmpty(),
              titleSelector     = binding.editTitleSelector.text?.toString().orEmpty(),
              coverSelector     = binding.editCoverSelector.text?.toString().orEmpty(),
              detailTitle       = binding.editDetailTitle.text?.toString().orEmpty(),
              description       = binding.editDescription.text?.toString().orEmpty(),
              chapterSelector   = binding.editChapterSelector.text?.toString().orEmpty(),
              pageImageSelector = binding.editPageImage.text?.toString().orEmpty(),
          )
      }
  }
  