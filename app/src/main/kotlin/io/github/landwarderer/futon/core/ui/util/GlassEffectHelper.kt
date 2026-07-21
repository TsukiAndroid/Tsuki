package io.github.landwarderer.futon.core.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.widget.ImageView
import androidx.core.view.WindowCompat

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
     * Blur an ImageView's current drawable using the best available method for the API level.
     *
     * API 31+: no-op — RenderEffect set via [applyBlurBackground] handles it on the GPU.
     * API 26-30: BlurCompat → RenderScript ScriptIntrinsicBlur (deprecated but functional).
     * API ≤ 25: existing Gaussian blur via RenderScript (unchanged — already working on API 25).
     *
     * Performance: bitmap is downscaled to at most 200×300 px before blurring, then upscaled
     * back to the original size. Run this off the main thread via [blurBitmapForBackground]
     * and post the result to the ImageView on the main thread.
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
            val blurredBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26-30: use BlurCompat with downscaling for performance and correctness
                blurBitmapWithCompat(imageView.context, srcBitmap, intensity)
            } else {
                // API ≤ 25: keep the existing RenderScript path — DO NOT TOUCH (already working)
                gaussianBlur(imageView.context, srcBitmap, intensity)
            }
            imageView.setImageBitmap(blurredBitmap)
        } catch (_: Exception) {
            // Best-effort; leave unblurred on failure.
        }
    }

    /**
     * Returns a blurred copy of [srcBitmap] suitable for use as a background on API 26-30.
     * Safe to call on a background thread. Returns null on API 31+ (caller should use
     * [applyBlurBackground] / RenderEffect instead).
     *
     * Strategy: downscale to at most 200×300 px → BlurCompat → upscale back to original size.
     */
    fun blurBitmapForBackground(
        context: Context,
        srcBitmap: Bitmap,
        intensity: Int,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
        if (intensity <= 0) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                blurBitmapWithCompat(context, srcBitmap, intensity)
            } else {
                // API ≤ 25: existing Gaussian path — DO NOT TOUCH
                gaussianBlur(context, srcBitmap, intensity)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * BlurCompat-based path for API 26-30.
     * Downscales to max 200×300 for speed, blurs, then upscales back.
     */
    private fun blurBitmapWithCompat(context: Context, src: Bitmap, intensity: Int): Bitmap {
        // Downscale to at most 200×300 px for fast blurring
        val maxW = 200; val maxH = 300
        val scaleW = maxW.toFloat() / src.width.coerceAtLeast(1)
        val scaleH = maxH.toFloat() / src.height.coerceAtLeast(1)
        val scale = minOf(scaleW, scaleH, 1f) // never upscale
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)

        val blurRadius = (intensity / 100f * 25f).coerceIn(1f, 25f)
        val blurred = BlurCompat.blurBitmap(context, small, blurRadius)
        small.recycle()

        // Upscale back to original size
        val result = Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
        blurred.recycle()
        return result
    }

    /**
     * API 23-25 fallback: smooth Gaussian blur via RenderScript ScriptIntrinsicBlur.
     * Steps: downscale to 30% → RS blur (radius proportional to intensity, max 25) →
     * bilinear upscale back to full size.
     * DO NOT TOUCH — already working correctly on API 25 (Huawei P9 Lite test device).
     */
    @Suppress("DEPRECATION")
    private fun gaussianBlur(context: Context, src: Bitmap, intensity: Int): Bitmap {
        // Step 1: downscale to 30% — fast and adds to overall blur depth.
        val scale = 0.3f
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)

        // Step 2: RenderScript Gaussian blur (API 17+, deprecated but safe through API 30).
        val blurRadius = (intensity / 100f * 25f).coerceIn(1f, 25f)
        val blurred = Bitmap.createBitmap(
            small.width, small.height,
            small.config ?: Bitmap.Config.ARGB_8888,
        )
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

    /**
     * Apply the best available blur/tint to the system navigation bar for each API level.
     *
     * API 31+ (Android 12+): hardware blur via [Window.setNavigationBarBlurRadius] + tinted color.
     * API 26-30 (Android 8-11): edge-to-edge mode + opaque dark tint (no system blur API).
     * API < 26: dark semi-transparent color (safest fallback).
     *
     * [blurRadius] px radius (meaningful only on API 31+).
     * [tintOpacity] 0.0–1.0 alpha for the overlay color.
     */
    fun applyNavigationBarBlur(
        window: Window,
        blurRadius: Int,
        tintOpacity: Float,
    ) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                window.setNavigationBarBlurRadius(blurRadius)
                window.navigationBarColor = Color.argb(
                    (tintOpacity * 255).toInt(), 0, 0, 0,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.navigationBarColor = Color.argb(178, 0, 0, 0)
            }
            else -> {
                window.navigationBarColor = Color.argb(180, 0, 0, 0)
            }
        }
    }

    fun clearBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
