package io.github.landwarderer.futon.browser.detection

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.landwarderer.futon.R

/**
 * Renders the three escalating UI tiers described by the universal manga-site
 * detection spec, driven purely by a [DetectionSession]'s [DetectionPromptLevel]:
 *
 *  - LEARNING (40-69): a small pulsing "moon" indicator -- purely informational,
 *    never interrupts browsing.
 *  - HINT (70-99): a dismissible one-line banner suggesting the site looks
 *    supported.
 *  - ADD_SOURCE (100+): a full bottom sheet summarising what was detected, with
 *    Add / Test First / Not now / Never-for-this-site actions.
 *
 * Pure View plumbing -- no detection logic lives here. Callers own the actual
 * views (toolbar icon, banner) and pass them in so this stays reusable across
 * WebView-hosting screens without depending on one Activity's layout.
 */
class MangaSitePrompt(private val activity: Activity) {

    private var pulseAnimator: ValueAnimator? = null

    // ── Level 1: pulsing moon icon ───────────────────────────────────────────

    fun showLearningIcon(icon: View) {
        if (icon.isVisible && pulseAnimator?.isRunning == true) return
        icon.isVisible = true
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0.35f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    fun hideLearningIcon(icon: View) {
        pulseAnimator?.cancel()
        pulseAnimator = null
        icon.isVisible = false
        icon.alpha = 1f
    }

    // ── Level 2: dismissible hint banner ─────────────────────────────────────

    fun showHintBanner(
        bannerRoot: View,
        messageView: TextView,
        dismissButton: View,
        message: String,
        onDismiss: () -> Unit,
    ) {
        messageView.text = message
        bannerRoot.isVisible = true
        dismissButton.setOnClickListener {
            bannerRoot.isVisible = false
            onDismiss()
        }
    }

    fun hideHintBanner(bannerRoot: View) {
        bannerRoot.isVisible = false
    }

    // ── Level 3: full add-source bottom sheet ────────────────────────────────

    fun showAddSourcePrompt(
        session: DetectionSession,
        onAddSource: () -> Unit,
        onTestFirst: () -> Unit,
        onNotNow: () -> Unit,
        onNeverForSite: () -> Unit,
    ) {
        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (activity.resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad, pad, pad)
        }

        fun checklistRow(label: String, found: Boolean): TextView = TextView(activity).apply {
            text = "${if (found) "✓" else "○"}  $label"
            textSize = 15f
            setPadding(0, (activity.resources.displayMetrics.density * 4).toInt(), 0, 0)
        }

        root.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.manga_site_prompt_title, session.siteTitle.ifBlank { session.domain })
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        root.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.manga_site_prompt_confidence, session.confidence)
                textSize = 13f
                alpha = 0.7f
                setPadding(0, (activity.resources.displayMetrics.density * 2).toInt(), 0, (activity.resources.displayMetrics.density * 12).toInt())
            },
        )
        root.addView(checklistRow(activity.getString(R.string.manga_site_prompt_check_list), session.mangaListDetected))
        root.addView(checklistRow(activity.getString(R.string.manga_site_prompt_check_detail), session.mangaDetailDetected))
        root.addView(checklistRow(activity.getString(R.string.manga_site_prompt_check_reader), session.chapterReaderDetected))
        root.addView(checklistRow(activity.getString(R.string.manga_site_prompt_check_search), session.searchDetected))

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (activity.resources.displayMetrics.density * 16).toInt(), 0, 0)
        }
        buttonRow.addView(
            Button(activity).apply {
                text = activity.getString(R.string.manga_site_prompt_add_source)
                setOnClickListener { onAddSource(); dialog.dismiss() }
            },
        )
        buttonRow.addView(
            Button(activity).apply {
                text = activity.getString(R.string.manga_site_prompt_test_first)
                setOnClickListener { onTestFirst() }
            },
        )
        root.addView(buttonRow)

        val secondaryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        secondaryRow.addView(
            Button(activity).apply {
                text = activity.getString(R.string.manga_site_prompt_not_now)
                setOnClickListener { onNotNow(); dialog.dismiss() }
            },
        )
        secondaryRow.addView(
            Button(activity).apply {
                text = activity.getString(R.string.manga_site_prompt_never)
                setOnClickListener { onNeverForSite(); dialog.dismiss() }
            },
        )
        root.addView(secondaryRow)

        dialog.setContentView(root)
        dialog.show()
    }
}
