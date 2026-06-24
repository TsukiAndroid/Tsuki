package io.github.landwarderer.futon.customsource.ui.visualpicker

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URL
import javax.inject.Inject

/**
 * ViewModel for [VisualRuleBuilderActivity].
 *
 * Manages the [PickerSession] state machine:
 *  - Step progression as the user captures selectors
 *  - Auto-filling the CARD_CONTAINER step when JS detects the parent automatically
 *  - Running a live parser test with captured selectors
 *  - Saving the final [CustomSource] + [ParserTemplate] when the user confirms
 */
@HiltViewModel
class VisualRuleBuilderViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val customSourcesRepository: CustomSourcesRepository,
    private val parserTemplateRepository: ParserTemplateRepository,
) : ViewModel() {

    // ── UI state ──────────────────────────────────────────────────────────────

    sealed interface UiState {
        data class Picking(val session: PickerSession) : UiState
        data class Saving(val session: PickerSession) : UiState
        data class Saved(val sourceName: String) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(
        UiState.Picking(PickerSession(siteUrl = "", siteName = ""))
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Toast-style one-shot messages (step confirmations, warnings). */
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    fun consumeToast() { _toastMessage.value = null }

    // ── Initialisation ────────────────────────────────────────────────────────

    fun init(siteUrl: String, siteName: String, prefilledSelectors: Map<PickerStep, String> = emptyMap()) {
        _uiState.value = UiState.Picking(
            PickerSession(
                siteUrl           = siteUrl,
                siteName          = siteName.ifBlank { URL(siteUrl).host },
                capturedSelectors = prefilledSelectors,
                currentStep       = firstIncompleteStep(prefilledSelectors),
            )
        )
    }

    private fun firstIncompleteStep(existing: Map<PickerStep, String>): PickerStep {
        return PickerStep.entries.firstOrNull { step ->
            step != PickerStep.COMPLETE && !existing.containsKey(step)
        } ?: PickerStep.COMPLETE
    }

    // ── Element selection (called from Activity when JS fires) ────────────────

    /**
     * Called when the user taps an element on the page.
     * Processes the element info for the current step and advances the state machine.
     */
    fun onElementSelected(info: ElementPickerWebView.ElementInfo) {
        val current = currentSession() ?: return
        val step = current.currentStep
        if (step == PickerStep.COMPLETE) return

        // Warning checks first
        val warningMsg = when (info.warning) {
            ElementPickerWebView.Warning.WARN_NAV  ->
                "⚠️ That looks like a navigation element, not a manga title. Try tapping the manga title text."
            ElementPickerWebView.Warning.WARN_LOGO ->
                "⚠️ That looks like a site logo. Try tapping a cover image instead."
            ElementPickerWebView.Warning.WARN_AD   ->
                "⚠️ That looks like an ad. Try tapping the manga title text."
            ElementPickerWebView.Warning.NONE      -> null
        }
        if (warningMsg != null) {
            _toastMessage.value = warningMsg
            return
        }

        // Sibling count check — selector too specific
        if (info.siblingCount <= 1 && step in listOf(PickerStep.MANGA_TITLE, PickerStep.COVER_IMAGE)) {
            _toastMessage.value = "⚠️ Only 1 match found. Try tapping the outer container instead."
        }

        val selector = info.selector
        if (!SelectorGenerator.isUsable(selector)) {
            _toastMessage.value = "⚠️ Could not generate a stable selector. Try a different element."
            return
        }

        // Capture selector for current step
        val updated = current.capturedSelectors.toMutableMap()
        updated[step] = selector

        // Auto-fill CARD_CONTAINER if JS detected it and it's not yet captured
        if (info.autoCardSelector.isNotBlank() && info.autoCardCount > 3 &&
            !updated.containsKey(PickerStep.CARD_CONTAINER)
        ) {
            updated[PickerStep.CARD_CONTAINER] = info.autoCardSelector
        }

        val nextStep = nextStep(updated, step)
        _toastMessage.value = step.confirmationMessage
        _uiState.value = UiState.Picking(
            current.copy(capturedSelectors = updated, currentStep = nextStep)
        )
    }

    private fun nextStep(captured: Map<PickerStep, String>, current: PickerStep): PickerStep {
        val steps = PickerStep.entries.filter { it != PickerStep.COMPLETE }
        val currentIdx = steps.indexOf(current)
        for (i in (currentIdx + 1)..steps.lastIndex) {
            val candidate = steps[i]
            if (!captured.containsKey(candidate)) return candidate
        }
        return PickerStep.COMPLETE
    }

    // ── Step controls ─────────────────────────────────────────────────────────

    /** Remove the selector for the current step so the user can re-tap. */
    fun undoLastTap() {
        val session = currentSession() ?: return
        val updated = session.capturedSelectors.toMutableMap()
        updated.remove(session.currentStep)
        _uiState.value = UiState.Picking(session.copy(capturedSelectors = updated))
    }

    /** Skip the current step (marks it with an empty sentinel so it doesn't block). */
    fun skipCurrentStep() {
        val session = currentSession() ?: return
        val updated = session.capturedSelectors.toMutableMap()
        // Don't store sentinel — just advance
        val nextStep = nextStep(updated, session.currentStep)
        _uiState.value = UiState.Picking(session.copy(currentStep = nextStep))
    }

    /** Jump back to re-capture a specific step. */
    fun retapStep(step: PickerStep) {
        val session = currentSession() ?: return
        val updated = session.capturedSelectors.toMutableMap()
        updated.remove(step)
        _uiState.value = UiState.Picking(session.copy(capturedSelectors = updated, currentStep = step))
    }

    // ── Parser test ───────────────────────────────────────────────────────────

    /**
     * Fetches the site's manga-list page and counts how many items match the
     * captured item/title selectors. Updates [PickerSession.testResult].
     */
    fun testSelectors() {
        val session = currentSession() ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = session.siteUrl.trimEnd('/') + "/"
                    val doc = Jsoup.connect(url)
                        .userAgent(BROWSER_UA)
                        .timeout(12_000)
                        .get()

                    val itemSel  = session.capturedSelectors[PickerStep.CARD_CONTAINER]
                    val titleSel = session.capturedSelectors[PickerStep.MANGA_TITLE]

                    val count = when {
                        !itemSel.isNullOrBlank()  -> doc.select(itemSel).size
                        !titleSel.isNullOrBlank() -> doc.select(titleSel).size
                        else -> 0
                    }
                    count
                }
            }
            val testResult = result.fold(
                onSuccess = { count ->
                    if (count > 0) TestResult.Success(count)
                    else TestResult.Failure("No items matched the selectors. Try tapping a different element.")
                },
                onFailure = { e ->
                    TestResult.Failure("Network error: ${e.message}")
                }
            )
            updateSession { it.copy(testResult = testResult) }
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    fun saveSource(listPath: String = "/") {
        val session = currentSession() ?: return
        _uiState.value = UiState.Saving(session)

        viewModelScope.launch {
            runCatching {
                val name = session.siteName.ifBlank { "Custom Source" }
                val json = SelectorGenerator.buildTemplateJson(
                    siteName          = name,
                    siteUrl           = session.siteUrl,
                    capturedSelectors = session.capturedSelectors,
                    listPath          = listPath,
                )
                val templateId = System.currentTimeMillis()
                val template = ParserTemplate(
                    id        = templateId,
                    name      = name,
                    version   = "1.0",
                    type      = "html",
                    rawJson   = json,
                )
                parserTemplateRepository.add(template)

                val source = CustomSource(
                    id               = templateId + 1L,
                    name             = name,
                    baseUrl          = session.siteUrl.trimEnd('/'),
                    type             = CustomSourceType.CUSTOM_TEMPLATE,
                    iconUrl          = session.faviconUrl,
                    parserSourceName = name,
                )
                customSourcesRepository.add(source)
                name
            }.fold(
                onSuccess = { name -> _uiState.value = UiState.Saved(name) },
                onFailure = { e  -> _uiState.value = UiState.Error(e.message ?: "Unknown error") }
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun currentSession(): PickerSession? = when (val s = _uiState.value) {
        is UiState.Picking -> s.session
        is UiState.Saving  -> s.session
        else               -> null
    }

    private fun updateSession(block: (PickerSession) -> PickerSession) {
        val current = _uiState.value
        if (current is UiState.Picking) {
            _uiState.update { UiState.Picking(block(current.session)) }
        }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
