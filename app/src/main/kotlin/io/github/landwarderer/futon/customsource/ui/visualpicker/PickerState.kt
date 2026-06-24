package io.github.landwarderer.futon.customsource.ui.visualpicker

/**
 * Represents a single step in the Visual Rule Builder guided picker flow.
 *
 * Steps are shown in sequence. The user taps an element on the page for each
 * step and Tsuki records the resulting CSS selector. Steps with [isRequired]
 * == false can be skipped via the "Skip" button.
 */
enum class PickerStep(
    val label: String,
    val icon: String,
    val instruction: String,
    val confirmationMessage: String,
    val isRequired: Boolean = true,
) {
    MANGA_TITLE(
        label = "Manga title",
        icon = "🌙",
        instruction = "Tap on any manga TITLE in the list",
        confirmationMessage = "✓ Title selector captured!",
    ),
    COVER_IMAGE(
        label = "Cover image",
        icon = "🖼",
        instruction = "Tap on any manga COVER IMAGE in the list",
        confirmationMessage = "✓ Cover selector captured!",
    ),
    CARD_CONTAINER(
        label = "Card container",
        icon = "📦",
        instruction = "Now tap on the SAME manga card container (the box that wraps both title and cover)",
        confirmationMessage = "✓ Card selector captured!",
        isRequired = false,
    ),
    CHAPTER_TITLE(
        label = "Chapter title",
        icon = "📖",
        instruction = "Now open any manga and tap on a CHAPTER title",
        confirmationMessage = "✓ Chapter selector captured!",
    ),
    PAGE_IMAGE(
        label = "Page image",
        icon = "🗒",
        instruction = "Now open any chapter and tap on a PAGE IMAGE",
        confirmationMessage = "✓ Page image selector captured!",
    ),
    COMPLETE(
        label = "Done",
        icon = "✅",
        instruction = "Review your selectors and save the source.",
        confirmationMessage = "",
    ),
}

/**
 * Mutable session state for one Visual Rule Builder run.
 *
 * Created fresh when the user opens [VisualRuleBuilderActivity].
 * Survives WebView page navigations because it lives in the ViewModel.
 */
data class PickerSession(
    val siteUrl: String,
    val siteName: String,
    val faviconUrl: String? = null,
    val capturedSelectors: Map<PickerStep, String> = emptyMap(),
    val currentStep: PickerStep = PickerStep.MANGA_TITLE,
    val testResult: TestResult? = null,
)

/**
 * Outcome of the "Test Parser" action run by [VisualRuleBuilderViewModel].
 */
sealed interface TestResult {
    data class Success(val count: Int) : TestResult
    data class Failure(val reason: String) : TestResult
}
