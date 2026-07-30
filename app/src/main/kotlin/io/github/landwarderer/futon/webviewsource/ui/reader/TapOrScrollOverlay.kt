package io.github.landwarderer.futon.webviewsource.ui.reader

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import androidx.core.view.GestureDetectorCompat

/**
 * Transparent overlay view that sits above the WebView.
 *
 * - Single tap in left third  → [onTapLeft]
 * - Single tap in right third → [onTapRight]
 * - Single tap in centre      → [onTapCenter]
 * - Scroll / fling            → forwarded to [webView] so the page still scrolls
 */
class TapOrScrollOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onTapLeft: () -> Unit = {}
    var onTapRight: () -> Unit = {}
    var onTapCenter: () -> Unit = {}
    lateinit var webView: WebView

    private val gestureDetector = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = width
                when {
                    e.x < w / 3f -> onTapLeft()
                    e.x > w * 2 / 3f -> onTapRight()
                    else -> onTapCenter()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distX: Float,
                distY: Float,
            ): Boolean {
                webView.scrollBy(distX.toInt(), distY.toInt())
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                webView.flingScroll((-velocityX).toInt(), (-velocityY).toInt())
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }
}
