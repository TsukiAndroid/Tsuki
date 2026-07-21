package io.github.landwarderer.futon.core.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Build

/**
 * Compatibility blur utility that picks the best available blur strategy per API level.
 *
 * API 31+ (Android 12+): caller should use RenderEffect.createBlurEffect() or
 *   Modifier.blur() — this function returns the source unchanged on those versions.
 * API 26-30 (Android 8-11): RenderScript ScriptIntrinsicBlur (deprecated but functional).
 * API 23-25 (Android 6-7): Pure-Kotlin Stack Blur (no Android API dependency).
 */
object BlurCompat {

    fun blurBitmap(
        context: Context,
        source: Bitmap,
        radius: Float,
    ): Bitmap {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                source // API 31+: RenderEffect handles blur; nothing to do here
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                blurWithRenderScript(context, source, radius) // API 26-30
            else ->
                blurWithStackBlur(source, radius.toInt()) // API 23-25
        }
    }

    @Suppress("DEPRECATION")
    private fun blurWithRenderScript(
        context: Context,
        source: Bitmap,
        radius: Float,
    ): Bitmap {
        val rs = android.renderscript.RenderScript.create(context)
        val input = android.renderscript.Allocation.createFromBitmap(rs, source)
        val output = android.renderscript.Allocation.createTyped(rs, input.type)
        val script = android.renderscript.ScriptIntrinsicBlur
            .create(rs, android.renderscript.Element.U8_4(rs))
        script.setRadius(radius.coerceIn(1f, 25f))
        script.setInput(input)
        script.forEach(output)
        val result = Bitmap.createBitmap(
            source.width, source.height,
            source.config ?: Bitmap.Config.ARGB_8888,
        )
        output.copyTo(result)
        rs.destroy()
        input.destroy()
        output.destroy()
        script.destroy()
        return result
    }

    private fun blurWithStackBlur(
        source: Bitmap,
        radius: Int,
    ): Bitmap {
        val r = radius.coerceIn(1, 25)
        val bitmap = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        stackBlur(pixels, w, h, r)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun stackBlur(pix: IntArray, w: Int, h: Int, radius: Int) {
        val wm = w - 1
        val hm = h - 1
        val div = radius + radius + 1
        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)
        var rsum: Int; var gsum: Int; var bsum: Int
        var x: Int; var y: Int; var i: Int
        var p: Int; var yi: Int
        val vmin = IntArray(maxOf(w, h))
        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) { dv[i] = i / divsum; i++ }
        yi = 0; var yw = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int; var goutsum: Int; var boutsum: Int
        var rinsum: Int; var ginsum: Int; var binsum: Int
        y = 0
        while (y < h) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = p and 0xff0000 shr 16
                sir[1] = p and 0x00ff00 shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - Math.abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius
            x = 0
            while (x < w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]
                boutsum -= sir[2]
                if (y == 0) vmin[x] = minOf(x + radius + 1, wm)
                p = pix[yw + vmin[x]]
                sir[0] = p and 0xff0000 shr 16
                sir[1] = p and 0x00ff00 shr 8
                sir[2] = p and 0x0000ff
                rinsum += sir[0]; ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]
                boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]
                binsum -= sir[2]
                yi++; x++
            }
            yw += w; yi = yw; y++
        }
        x = 0
        while (x < w) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            var yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = maxOf(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                rbs = r1 - Math.abs(i)
                rsum += r[yi] * rbs; gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) yp += w
                i++
            }
            yi = x; stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (pix[yi] and 0xff000000.toInt()) or
                    (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]
                boutsum -= sir[2]
                if (x == 0) vmin[y] = minOf(y + r1, hm) * w
                p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]
                boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w; y++
            }
            x++
        }
    }
}
