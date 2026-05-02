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
     * API 23-30: no-op here — call [blurImageView] once the image has loaded.
     */
    fun applyBlurBackground(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP),
            )
        }
    }

    /**
     * API 23-30 fallback: blurs an ImageView's bitmap via a fast downscale-then-upscale
     * to 8 % of original size (bilinear filtering does the heavy lifting).
     * Call this after [ImageView.setImageDrawable] / coil's image-load completes.
     */
    fun blurImageView(imageView: ImageView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return // RenderEffect handles it
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
            imageView.setImageBitmap(fastBlur(srcBitmap))
        } catch (_: Exception) {
            // Best-effort; leave unblurred on failure.
        }
    }

    /** Cheap software blur: downscale to ~8 %, then bilinear-upscale back. */
    private fun fastBlur(src: Bitmap): Bitmap {
        val scale = 0.08f
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
