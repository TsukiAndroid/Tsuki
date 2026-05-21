package io.github.landwarderer.futon.customsource.ui

  import android.os.Bundle
  import android.widget.Toast
  import androidx.activity.viewModels
  import androidx.appcompat.app.AppCompatActivity
  import androidx.lifecycle.Lifecycle
  import androidx.lifecycle.lifecycleScope
  import androidx.lifecycle.repeatOnLifecycle
  import com.google.android.material.textfield.TextInputEditText
  import com.google.android.material.textfield.TextInputLayout
  import dagger.hilt.android.AndroidEntryPoint
  import kotlinx.coroutines.launch
  import io.github.landwarderer.futon.R
  import io.github.landwarderer.futon.databinding.ActivityUniversalSourceBinding

  /**
   * Form-based "Universal Source" wizard.
   *
   * The user fills in ~11 CSS-selector fields (no coding required) and taps
   * Create. The ViewModel builds a [ParserTemplate] JSON from those fields,
   * saves it, and registers a [CustomSource] backed by the new template.
   * The existing [TemplateHtmlParser] then handles all scraping automatically.
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

          binding.btnCreate.setOnClickListener { submitForm() }

          lifecycleScope.launch {
              repeatOnLifecycle(Lifecycle.State.STARTED) {
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
          }
      }

      override fun onSupportNavigateUp(): Boolean {
          finish()
          return true
      }

      private fun submitForm() {
          // Clear any previous errors
          binding.layoutName.error    = null
          binding.layoutBaseUrl.error = null
          binding.layoutPageImage.error = null

          viewModel.create(
              name             = binding.editName.text?.toString().orEmpty(),
              baseUrl          = binding.editBaseUrl.text?.toString().orEmpty(),
              listPath         = binding.editListPath.text?.toString().orEmpty(),
              searchPath       = binding.editSearchPath.text?.toString().orEmpty(),
              cardSelector     = binding.editCardSelector.text?.toString().orEmpty(),
              titleSelector    = binding.editTitleSelector.text?.toString().orEmpty(),
              coverSelector    = binding.editCoverSelector.text?.toString().orEmpty(),
              detailTitle      = binding.editDetailTitle.text?.toString().orEmpty(),
              description      = binding.editDescription.text?.toString().orEmpty(),
              chapterSelector  = binding.editChapterSelector.text?.toString().orEmpty(),
              pageImageSelector = binding.editPageImage.text?.toString().orEmpty(),
          )
      }
  }
  