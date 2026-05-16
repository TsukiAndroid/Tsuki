package io.github.landwarderer.futon.browser.learning

data class LearningBannerState(
    val message: String,
    val checklist: List<Pair<PageType, Boolean>>,
    val isReadyForGeneration: Boolean,
    val isDismissed: Boolean,
)
