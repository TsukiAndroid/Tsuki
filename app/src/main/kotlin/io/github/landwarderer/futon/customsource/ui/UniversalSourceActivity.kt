package io.github.landwarderer.futon.customsource.ui

  import android.os.Bundle
  import android.util.TypedValue
  import android.view.View
  import android.widget.Toast
  import androidx.activity.viewModels
  import androidx.appcompat.app.AppCompatActivity
  import androidx.lifecycle.Lifecycle
  import androidx.lifecycle.lifecycleScope
  import androidx.lifecycle.repeatOnLifecycle
  import com.google.android.material.R as MaterialR
  import dagger.hilt.android.AndroidEntryPoint
  import kotlinx.coroutines.launch
  import io.github.landwarderer.futon.R
  import io.github.landwarderer.futon.customsource.data.SiteAutoDetector
  import io.github.landwarderer.futon.databinding.ActivityUniversalSourceBinding

  /**
   * Form-based "Universal Source" wizard.
   *
   * The user can either:
   *  A) Paste the site URL and tap "Auto-detect selectors" — the app fetches
   *     the site HTML and pre-fills all CSS-selector fields automatically.
   *  B) Fill in the fields manually.
   *
   * Tapping Create calls the ViewModel which builds a [ParserTemplate] JSON,
   * saves it, and registers a [CustomSource]. The existing [TemplateHtmlParser]
   * then handles all scraping automatically.
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

          lifecycleScope.launch {
              repeatOnLifecycle(Lifecycle.State.STARTED) {
                  launch { observeCreateResult() }
                  launch { observeAutoDetect() }
              }
          }
      }

      override fun onSupportNavigateUp(): Boolean {
          finish()
          return true
      }

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
                          getString(R.string.universal_source_created, result.name),
                          Toast.LENGTH_SHORT,
                      ).show()
                      finish()
                  }
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
                      binding.autoDetectStatusCard.visibility = View.GONE
                      binding.btnAutoDetect.isEnabled = false
                      binding.btnAutoDetect.text = getString(R.string.universal_source_auto_detecting)
                  }
                  is UniversalSourceViewModel.AutoDetectState.Done -> {
                      binding.autoDetectProgress.visibility = View.GONE
                      binding.btnAutoDetect.isEnabled = true
                      binding.btnAutoDetect.text = getString(R.string.universal_source_auto_detect_btn)
                      applyDetectedFields(state.fields)
                      showStatusCard(getString(R.string.universal_source_auto_detect_done), isError = false)
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

      // ── Helpers ───────────────────────────────────────────────────────────────

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

      private fun showStatusCard(message: String, isError: Boolean) {
          binding.autoDetectStatusText.text = message
          val colorAttr = if (isError) MaterialR.attr.colorErrorContainer
                          else         MaterialR.attr.colorSecondaryContainer
          val tv = TypedValue()
          theme.resolveAttribute(colorAttr, tv, true)
          binding.autoDetectStatusCard.setCardBackgroundColor(tv.data)
          binding.autoDetectStatusCard.visibility = View.VISIBLE
      }

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
  