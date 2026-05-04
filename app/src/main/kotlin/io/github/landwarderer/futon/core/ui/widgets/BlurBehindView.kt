package io.github.landwarderer.futon.core.ui.widgets

  import android.content.Context
  import android.graphics.Bitmap
  import android.graphics.Canvas
  import android.graphics.Paint
  import android.graphics.RectF
  import android.os.Build
  import android.util.AttributeSet
  import android.view.View
  import android.view.ViewTreeObserver
  import java.lang.ref.WeakReference

  /**
   * Renders a real-time blurred snapshot of the content behind this view,
   * producing a frosted-glass effect for nav bars and search bars on Android 6+.
   *
   * Place this view *behind* the bar you want to frost (lower z-order in the layout).
   * Call [setContentSource] with the fragment-container view (excludes the bars themselves).
   * Call [setBlurIntensity] 1-100 to enable, 0 to clear and disable.
   *
   * On API 17+ the capture is downscaled to 25 % and blurred with
   * RenderScript ScriptIntrinsicBlur, throttled to ~30 fps.
   */
  @Suppress("DEPRECATION")
  class BlurBehindView @JvmOverloads constructor(
      context: Context,
      attrs: AttributeSet? = null,
  ) : View(context, attrs) {

      private var contentSource: WeakReference<View>? = null
      private var blurIntensity = 0

      private var currentBitmap: Bitmap? = null
      private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      private var isCapturing = false
      private var lastUpdateMs = 0L

      // Cached RenderScript objects – reused across frames to avoid per-frame allocation overhead
      private var renderScript: android.renderscript.RenderScript? = null
      private var inputAlloc: android.renderscript.Allocation? = null
      private var outputAlloc: android.renderscript.Allocation? = null
      private var blurScript: android.renderscript.ScriptIntrinsicBlur? = null
      private var cachedAllocW = -1
      private var cachedAllocH = -1

      // ── OnPreDrawListener (~30 fps, throttled) ────────────────────────────────

      private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
          val now = System.currentTimeMillis()
          if (blurIntensity > 0 && !isCapturing && now - lastUpdateMs >= 33L) {
              lastUpdateMs = now
              post(::captureAndBlur)
          }
          true
      }

      // ── Lifecycle ─────────────────────────────────────────────────────────────

      override fun onAttachedToWindow() {
          super.onAttachedToWindow()
          if (blurIntensity > 0) viewTreeObserver.addOnPreDrawListener(preDrawListener)
      }

      override fun onDetachedFromWindow() {
          viewTreeObserver.removeOnPreDrawListener(preDrawListener)
          releaseRenderScriptResources()
          currentBitmap?.recycle(); currentBitmap = null
          super.onDetachedFromWindow()
      }

      private fun releaseRenderScriptResources() {
          inputAlloc?.destroy(); inputAlloc = null
          outputAlloc?.destroy(); outputAlloc = null
          blurScript?.destroy(); blurScript = null
          renderScript?.destroy(); renderScript = null
          cachedAllocW = -1; cachedAllocH = -1
      }

      // ── Drawing ───────────────────────────────────────────────────────────────

      override fun onDraw(canvas: Canvas) {
          val bmp = currentBitmap ?: return
          canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
      }

      // ── Public API ────────────────────────────────────────────────────────────

      /** The view whose content is captured as the blur source (e.g. the fragment container). */
      fun setContentSource(view: View) {
          contentSource = WeakReference(view)
      }

      /**
       * 0 = off (clears blur), 1-100 = blur strength.
       * Takes effect immediately; rendered image updates at ~30 fps.
       */
      fun setBlurIntensity(intensity: Int) {
          val prev = blurIntensity
          blurIntensity = intensity.coerceIn(0, 100)
          if (isAttachedToWindow) {
              if (blurIntensity > 0 && prev == 0) viewTreeObserver.addOnPreDrawListener(preDrawListener)
              if (blurIntensity == 0 && prev > 0) viewTreeObserver.removeOnPreDrawListener(preDrawListener)
          }
          if (blurIntensity == 0) {
              currentBitmap?.recycle(); currentBitmap = null
              invalidate()
          }
      }

      // ── Capture & Blur ────────────────────────────────────────────────────────

      private fun captureAndBlur() {
          if (!isAttachedToWindow || width <= 0 || height <= 0 || blurIntensity <= 0) return
          val source = contentSource?.get() ?: return
          if (source.width <= 0 || source.height <= 0) return

          isCapturing = true

          // 1. Downscale source to 25 % for a fast capture + blur pipeline
          val scale = 0.25f
          val sw = (source.width * scale).toInt().coerceAtLeast(1)
          val sh = (source.height * scale).toInt().coerceAtLeast(1)
          val srcBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
          val ok = runCatching {
              val c = Canvas(srcBmp)
              c.scale(scale, scale)
              source.draw(c)
          }.isSuccess
          if (!ok) { srcBmp.recycle(); isCapturing = false; return }

          // 2. Compute this view's position within the source coordinate space
          val selfLoc = IntArray(2); getLocationOnScreen(selfLoc)
          val srcLoc  = IntArray(2); source.getLocationOnScreen(srcLoc)
          val x  = ((selfLoc[0] - srcLoc[0]) * scale).toInt().coerceIn(0, sw - 1)
          val y  = ((selfLoc[1] - srcLoc[1]) * scale).toInt().coerceIn(0, sh - 1)
          val cw = (width  * scale).toInt().coerceAtLeast(1).coerceAtMost(sw - x)
          val ch = (height * scale).toInt().coerceAtLeast(1).coerceAtMost(sh - y)
          if (cw <= 0 || ch <= 0) { srcBmp.recycle(); isCapturing = false; return }

          // 3. Crop to the area behind this view
          val cropped = Bitmap.createBitmap(srcBmp, x, y, cw, ch)
          srcBmp.recycle()

          // 4. Blur with RenderScript
          val blurred = blurBitmap(cropped, blurIntensity)
          if (blurred !== cropped) cropped.recycle()

          // 5. Scale blurred result up to view dimensions
          val result = if (blurred.width != width || blurred.height != height) {
              Bitmap.createScaledBitmap(
                  blurred, width.coerceAtLeast(1), height.coerceAtLeast(1), true
              ).also { if (it !== blurred) blurred.recycle() }
          } else blurred

          // 6. Swap current bitmap
          val old = currentBitmap
          currentBitmap = result
          old?.recycle()

          isCapturing = false
          invalidate()
      }

      /**
       * Gaussian blur via RenderScript ScriptIntrinsicBlur (API 17+).
       * Caches the RenderScript, Allocation and Script objects for reuse.
       */
      private fun blurBitmap(src: Bitmap, intensity: Int): Bitmap {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return src
          val radius = (intensity / 100f * 25f).coerceIn(1f, 25f)
          val output = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)

          if (renderScript == null) {
              renderScript = runCatching {
                  android.renderscript.RenderScript.create(context.applicationContext)
              }.getOrNull()
          }
          val rs = renderScript ?: return src

          return runCatching {
              // Recreate allocations only when bitmap size changes (e.g. rotation)
              if (src.width != cachedAllocW || src.height != cachedAllocH) {
                  inputAlloc?.destroy(); outputAlloc?.destroy(); blurScript?.destroy()
                  inputAlloc = android.renderscript.Allocation.createFromBitmap(
                      rs, src,
                      android.renderscript.Allocation.MipmapControl.MIPMAP_NONE,
                      android.renderscript.Allocation.USAGE_SCRIPT,
                  )
                  outputAlloc = android.renderscript.Allocation.createTyped(rs, inputAlloc!!.type)
                  blurScript  = android.renderscript.ScriptIntrinsicBlur.create(
                      rs, android.renderscript.Element.U8_4(rs),
                  )
                  cachedAllocW = src.width; cachedAllocH = src.height
              } else {
                  inputAlloc!!.copyFrom(src)
              }
              blurScript!!.setRadius(radius)
              blurScript!!.setInput(inputAlloc)
              blurScript!!.forEach(outputAlloc)
              outputAlloc!!.copyTo(output)
              output
          }.getOrDefault(src)
      }
  }
  