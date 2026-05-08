package io.github.landwarderer.futon.core.util.ext

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.InsetsType

fun Insets.end(view: View): Int {
	return if (view.isRtl) left else right
}

fun Insets.start(view: View): Int {
	return if (view.isRtl) right else left
}

@Deprecated("")
val WindowInsetsCompat.systemBarsInsets: Insets
	get() = getInsets(WindowInsetsCompat.Type.systemBars())

@Deprecated("")
fun WindowInsetsCompat.consumeSystemBarsInsets(
	left: Boolean = false,
	top: Boolean = false,
	right: Boolean = false,
	bottom: Boolean = false,
): WindowInsetsCompat {
	val barsInsets = systemBarsInsets
	val insets = Insets.of(
		if (left) 0 else barsInsets.left,
		if (top) 0 else barsInsets.top,
		if (right) 0 else barsInsets.right,
		if (bottom) 0 else barsInsets.bottom,
	)
	return WindowInsetsCompat.Builder(this)
		.setInsets(WindowInsetsCompat.Type.systemBars(), insets)
		.build()
}

fun WindowInsetsCompat.consume(
	v: View,
	@InsetsType typeMask: Int,
	start: Boolean = false,
	top: Boolean = false,
	end: Boolean = false,
	bottom: Boolean = false,
): WindowInsetsCompat {
	val insets = getInsets(typeMask)
	val newInsets = Insets.of(
		/* left = */ if (if (v.isRtl) end else start) 0 else insets.left,
		/* top = */ if (top) 0 else insets.top,
		/* right = */ if (if (v.isRtl) start else end) 0 else insets.right,
		/* bottom = */ if (bottom) 0 else insets.bottom,
	)
	return WindowInsetsCompat.Builder(this)
		.setInsets(typeMask, newInsets)
		.build()
}

fun WindowInsetsCompat.consumeAll(
	@InsetsType typeMask: Int,
): WindowInsetsCompat = WindowInsetsCompat.Builder(this)
	.setInsets(typeMask, Insets.NONE)
	.build()

@Deprecated("")
fun WindowInsetsCompat.consumeSystemBarsInsets(
	view: View,
	start: Boolean = false,
	top: Boolean = false,
	end: Boolean = false,
	bottom: Boolean = false,
): WindowInsetsCompat = consume(view, WindowInsetsCompat.Type.systemBars(), start, top, end, bottom)

@Deprecated("")
fun WindowInsetsCompat.consumeAllSystemBarsInsets() = consumeAll(WindowInsetsCompat.Type.systemBars())

@Deprecated("")
fun Insets.consume(
	view: View,
	start: Boolean = false,
	top: Boolean = false,
	end: Boolean = false,
	bottom: Boolean = false,
): Insets = Insets.of(
	/* left = */ if (if (view.isRtl) end else start) 0 else this.left,
	/* top = */ if (top) 0 else this.top,
	/* right = */ if (if (view.isRtl) start else end) 0 else this.right,
	/* bottom = */ if (bottom) 0 else this.bottom,
)

/**
 * Install a [WindowInsetsAnimationCompat.Callback] on this view so that its
 * padding smoothly tracks the on-screen keyboard as it animates in and out.
 *
 * The callback mirrors the same `systemBars | ime` padding logic used in each
 * activity's [onApplyWindowInsets], so the static and animated states always
 * produce identical results — the only difference is that during the animation
 * the padding updates every frame instead of jumping at the end.
 *
 * On API 30+ the platform exposes the IME animation interpolator for
 * frame-perfect synchronisation (the layout slides with the keyboard).
 * On API 23–29 the compat library has no IME animation info from the system,
 * so the callback fires once and the padding jumps instantly — exactly the
 * same behaviour as before, with no regression.
 *
 * @param basePadding additional constant padding applied on every side on top
 *   of the inset values (mirrors the `R.dimen.screen_padding` constant used
 *   in the password screens).
 */
fun View.syncImeAnimationToPadding(basePadding: Int = 0) {
	ViewCompat.setWindowInsetsAnimationCallback(
		this,
		object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
			override fun onProgress(
				insets: WindowInsetsCompat,
				runningAnimations: List<WindowInsetsAnimationCompat>,
			): WindowInsetsCompat {
				val type = WindowInsetsCompat.Type.systemBars() or
					WindowInsetsCompat.Type.ime()
				val bars = insets.getInsets(type)
				setPadding(
					bars.left + basePadding,
					bars.top + basePadding,
					bars.right + basePadding,
					bars.bottom + basePadding,
				)
				return insets
			}
		},
	)
}
