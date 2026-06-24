package io.github.landwarderer.futon.customsource.ui.visualpicker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.network.webview.adblock.AdBlock
import io.github.landwarderer.futon.databinding.ActivityVisualRuleBuilderBinding
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen visual element picker activity for the Visual Rule Builder.
 *
 * Layout (portrait):
 *  ┌──────────────────────────────────┐
 *  │  WebView (~80% height)           │
 *  │  (site loads here; user taps)    │
 *  ├──────────────────────────────────┤
 *  │  Bottom sheet (~20%)             │
 *  │  • Step instruction + icon       │
 *  │  • Progress chips                │
 *  │  • Captured fields (✓)           │
 *  │  • Undo / Skip / Test+Save       │
 *  └──────────────────────────────────┘
 *
 * Entry points:
 *  - [createIntent] — fresh session for a new source URL
 *  - [createIntentForFix] — pre-filled session for fixing an existing source
 */
@AndroidEntryPoint
class VisualRuleBuilderActivity : AppCompatActivity() {

    @Inject lateinit var adBlock: AdBlock

    private val viewModel: VisualRuleBuilderViewModel by viewModels()
    private lateinit var binding: ActivityVisualRuleBuilderBinding
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private val webBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (pickerWebView()?.canGoBack() == true) pickerWebView()?.goBack()
            else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisualRuleBuilderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.visual_rule_builder_title)

        val siteUrl  = intent.getStringExtra(KEY_SITE_URL)  ?: ""
        val siteName = intent.getStringExtra(KEY_SITE_NAME) ?: ""
        val prefilledJson = intent.getStringExtra(KEY_PREFILLED_JSON) ?: ""

        // Decode any pre-filled selectors from existing source settings
        val prefilledSelectors = decodePrefilledJson(prefilledJson)
        viewModel.init(siteUrl, siteName, prefilledSelectors)

        onBackPressedDispatcher.addCallback(this, webBackCallback)

        setupWebView(siteUrl)
        setupBottomSheet()
        setupButtons()
        observeState()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(pickerWebView() ?: return, true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.opt_visual_rule_builder, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        pickerWebView()?.destroy()
        super.onDestroy()
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    private fun setupWebView(siteUrl: String) {
        val pickerWebView = ElementPickerWebView(
            context = this,
            onElementSelected = { info -> viewModel.onElementSelected(info) },
            onPageStarted     = { url  -> onPageNavigated(url) },
            adBlock           = adBlock,
        )
        // Replace the placeholder WebView in the layout with our custom one
        binding.webViewContainer.removeAllViews()
        binding.webViewContainer.addView(pickerWebView, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        // Keep a typed reference via tag
        pickerWebView.tag = TAG_PICKER_WV
        binding.webViewContainer.tag = pickerWebView

        if (siteUrl.isNotBlank()) pickerWebView.loadUrl(siteUrl)
    }

    private fun pickerWebView(): ElementPickerWebView? =
        binding.webViewContainer.tag as? ElementPickerWebView

    private fun onPageNavigated(url: String) {
        runOnUiThread {
            binding.currentUrlText.text = url
        }
    }

    // ── Bottom sheet ──────────────────────────────────────────────────────────

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheetBehavior.peekHeight =
            resources.getDimensionPixelSize(R.dimen.picker_bottom_sheet_peek_height)
        bottomSheetBehavior.isHideable = false
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnUndo.setOnClickListener { viewModel.undoLastTap() }
        binding.btnSkip.setOnClickListener { viewModel.skipCurrentStep() }
        binding.btnTestSave.setOnClickListener {
            viewModel.testSelectors()
        }
        binding.btnSaveConfirm.setOnClickListener {
            viewModel.saveSource()
        }
    }

    // ── State observation ─────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> renderState(state) }
                }
                launch {
                    viewModel.toastMessage.collect { msg ->
                        if (msg != null) {
                            Toast.makeText(this@VisualRuleBuilderActivity, msg, Toast.LENGTH_SHORT).show()
                            viewModel.consumeToast()
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: VisualRuleBuilderViewModel.UiState) {
        when (state) {
            is VisualRuleBuilderViewModel.UiState.Picking -> renderPicking(state.session)
            is VisualRuleBuilderViewModel.UiState.Saving  -> {
                binding.progressOverlay.isVisible = true
            }
            is VisualRuleBuilderViewModel.UiState.Saved -> {
                binding.progressOverlay.isVisible = false
                Toast.makeText(
                    this,
                    getString(R.string.visual_rule_builder_saved, state.sourceName),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
            is VisualRuleBuilderViewModel.UiState.Error -> {
                binding.progressOverlay.isVisible = false
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderPicking(session: PickerSession) {
        binding.progressOverlay.isVisible = false
        val step = session.currentStep
        val isComplete = step == PickerStep.COMPLETE

        // Step instruction banner
        if (isComplete) {
            binding.stepInstructionBanner.text = getString(R.string.visual_rule_builder_review_prompt)
        } else {
            binding.stepInstructionBanner.text = "${step.icon}  ${step.instruction}"
        }

        // Progress dots / chips
        binding.progressChipsGroup.removeAllViews()
        val steps = PickerStep.entries.filter { it != PickerStep.COMPLETE }
        steps.forEach { s ->
            val chip = Chip(this).apply {
                text = s.icon
                isCheckable = false
                isClickable = !isComplete
                val captured = session.capturedSelectors.containsKey(s)
                setChipBackgroundColorResource(
                    if (captured) R.color.picker_chip_captured
                    else if (s == step) R.color.picker_chip_active
                    else R.color.picker_chip_inactive
                )
                setOnClickListener { if (!isComplete) viewModel.retapStep(s) }
            }
            binding.progressChipsGroup.addView(chip)
        }

        // Captured fields summary
        binding.capturedSummaryGroup.removeAllViews()
        PickerStep.entries.filter { it != PickerStep.COMPLETE }.forEach { s ->
            val sel = session.capturedSelectors[s]
            if (!sel.isNullOrBlank()) {
                val chip = Chip(this).apply {
                    text = "✓ ${s.label}: $sel"
                    isCheckable = false
                    isCloseIconVisible = true
                    setOnCloseIconClickListener { viewModel.retapStep(s) }
                }
                binding.capturedSummaryGroup.addView(chip)
            }
        }

        // Buttons
        binding.btnUndo.isVisible = !isComplete
        binding.btnSkip.isVisible = !isComplete && !(PickerStep.entries.firstOrNull { it == step }?.isRequired ?: true)
        binding.btnTestSave.isVisible = session.capturedSelectors.size >= 2
        binding.btnSaveConfirm.isVisible = false

        // Test result
        when (val result = session.testResult) {
            null -> binding.testResultText.isVisible = false
            is TestResult.Success -> {
                binding.testResultText.text = getString(R.string.visual_rule_builder_test_success, result.count)
                binding.testResultText.isVisible = true
                binding.btnSaveConfirm.isVisible = true
            }
            is TestResult.Failure -> {
                binding.testResultText.text = getString(R.string.visual_rule_builder_test_failure, result.reason)
                binding.testResultText.isVisible = true
            }
        }
    }

    // ── Pre-filled selectors decoder ──────────────────────────────────────────

    /**
     * Decodes a comma-separated "step=selector" string from [KEY_PREFILLED_JSON]
     * into a map. Example: "MANGA_TITLE=.post-title a,COVER_IMAGE=.thumb img"
     * This simple format avoids a full JSON dependency just for the intent extras.
     */
    private fun decodePrefilledJson(raw: String): Map<PickerStep, String> {
        if (raw.isBlank()) return emptyMap()
        return buildMap {
            raw.split("|").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) return@forEach
                val key = pair.substring(0, idx)
                val sel = pair.substring(idx + 1)
                runCatching { put(PickerStep.valueOf(key), sel) }
            }
        }
    }

    companion object {

        private const val KEY_SITE_URL       = "site_url"
        private const val KEY_SITE_NAME      = "site_name"
        private const val KEY_PREFILLED_JSON = "prefilled_json"
        private const val TAG_PICKER_WV      = "picker_webview"

        /**
         * Intent for a fresh session — new source from scratch.
         */
        fun createIntent(
            context: Context,
            siteUrl: String,
            siteName: String = "",
        ): Intent = Intent(context, VisualRuleBuilderActivity::class.java).apply {
            putExtra(KEY_SITE_URL,  siteUrl)
            putExtra(KEY_SITE_NAME, siteName)
        }

        /**
         * Intent for fixing an existing source — pre-fills captured selectors
         * from the source's existing template so the user only re-taps what changed.
         *
         * [prefilledSelectors] is a map of PickerStep → CSS selector string for
         * any steps that were previously captured (from the existing ParserTemplate).
         */
        fun createIntentForFix(
            context: Context,
            siteUrl: String,
            siteName: String,
            prefilledSelectors: Map<PickerStep, String>,
        ): Intent = Intent(context, VisualRuleBuilderActivity::class.java).apply {
            putExtra(KEY_SITE_URL,  siteUrl)
            putExtra(KEY_SITE_NAME, siteName)
            // Encode as "STEP_NAME=selector" pairs joined by "|"
            val encoded = prefilledSelectors.entries.joinToString("|") { (step, sel) ->
                "${step.name}=$sel"
            }
            putExtra(KEY_PREFILLED_JSON, encoded)
        }
    }
}
