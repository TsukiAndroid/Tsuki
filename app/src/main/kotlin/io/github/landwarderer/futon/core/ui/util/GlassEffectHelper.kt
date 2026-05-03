package io.github.landwarderer.futon.core.ui.util

import android.content.Context
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
     * API 23-30 fallback: smooth Gaussian blur via RenderScript ScriptIntrinsicBlur.
     * Steps: downscale to 30% → RS blur (radius proportional to intensity, max 25) →
     * bilinear upscale back to full size. This gives the soft Dantotsu-style look
     * instead of the blocky pixelated artefacts from extreme downscaling alone.
     * No-op on API 31+ where RenderEffect handles blurring via [applyBlurBackground].
     */
    fun blurImageView(imageView: ImageView, intensity: Int = 75) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
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
            imageView.setImageBitmap(gaussianBlur(imageView.context, srcBitmap, intensity))
        } catch (_: Exception) {
            // Best-effort; leave unblurred on failure.
        }
    }

    @Suppress("DEPRECATION")
    private fun gaussianBlur(context: Context, src: Bitmap, intensity: Int): Bitmap {
        // Step 1: downscale to 30% — fast and adds to overall blur depth.
        val scale = 0.3f
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)

        // Step 2: RenderScript Gaussian blur (API 17+, deprecated but safe through API 30).
        val blurRadius = (intensity / 100f * 25f).coerceIn(1f, 25f)
        val blurred = Bitmap.createBitmap(small.width, small.height,
            small.config ?: Bitmap.Config.ARGB_8888)
        val rs = android.renderscript.RenderScript.create(context)
        try {
            val input = android.renderscript.Allocation.createFromBitmap(
                rs, small,
                android.renderscript.Allocation.MipmapControl.MIPMAP_NONE,
                android.renderscript.Allocation.USAGE_SCRIPT,
            )
            val output = android.renderscript.Allocation.createTyped(rs, input.type)
            val script = android.renderscript.ScriptIntrinsicBlur.create(
                rs, android.renderscript.Element.U8_4(rs),
            )
            script.setRadius(blurRadius)
            script.setInput(input)
            script.forEach(output)
            output.copyTo(blurred)
            input.destroy()
            output.destroy()
            script.destroy()
        } finally {
            rs.destroy()
        }
        small.recycle()

        // Step 3: bilinear upscale back — smooths out any remaining block artefacts.
        val result = Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
        blurred.recycle()
        return result
    }

    fun clearBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
