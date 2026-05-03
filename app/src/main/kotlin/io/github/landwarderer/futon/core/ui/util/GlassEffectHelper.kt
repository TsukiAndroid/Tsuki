package io.github.landwarderer.futon.core.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.View
import android.widget.ImageView

object GlassEffectHelper {

    /**
     * API 31+: attach a hardware RenderEffect blur to the view (GPU, real-time).
     * [intensity] 0–100 (default 75). At 0 the blur is removed; at 100 radius = 40 px.
     * Safe no-op on API < 31.
     */
    fun applyBlurBackground(view: View, intensity: Int = 75) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (intensity <= 0) {
                view.setRenderEffect(null)
            } else {
                val radius = intensity.coerceIn(1, 100) * 0.4f
                view.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
                )
            }
        }
    }

    /**
     * API 23-30 fallback: blurs an ImageView's bitmap via a fast downscale-then-upscale.
     * [intensity] 0–100 (default 75). Higher = more blur. No-op on API 31+ (RenderEffect
     * handles it there via [applyBlurBackground]).
     */
    fun blurImageView(imageView: ImageView, intensity: Int = 75) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return // RenderEffect handles it
        if (intensity <= 0) return
        val drawable = imageView.drawable ?: return
        try {
            val srcBitmap: Bitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    drawable.draw(Canvas(bmp))
                    bmp
                }
            }
            imageView.setImageBitmap(fastBlur(srcBitmap, intensity))
        } catch (_: Exception) {
            // Best-effort; leave unblurred on failure.
        }
    }

    /**
     * Cheap software blur: downscale to [scale]% then bilinear-upscale back.
     * [intensity] 0–100: 0 = no blur (scale 1.0), 100 = max blur (scale 0.03).
     */
    private fun fastBlur(src: Bitmap, intensity: Int): Bitmap {
        // Cubic ease-in: at 75% intensity → scale≈0.055 (heavy blur, near original 0.08)
        val t = 1f - intensity.coerceIn(0, 100) / 100f
        val scale = (0.04f + 0.96f * t * t * t).coerceAtLeast(0.04f)
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val result = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        small.recycle()
        return result
    }

    fun clearBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
