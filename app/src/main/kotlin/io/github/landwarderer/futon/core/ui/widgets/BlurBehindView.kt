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
  import androidx.annotation.RequiresApi
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
      // Tint overlay drawn on top of the blurred snapshot (0 = none, 255 = fully opaque)
      private var blurTintAlpha = 0
      private val tintPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }

      // ── Performance controls (adjustable at runtime) ─────────────────────────
      /** Fraction of the source view size used for the blur sample (0.10–0.50). */
      private var captureScale = 0.25f
      /** Minimum time between blur captures in milliseconds (controls effective fps). */
      private var minFrameIntervalMs = 33L
      /** When true, skip re-capture if a quick 8×8 hash of the cropped region is unchanged. */
      private var skipWhenIdle = false
      private var lastFrameHash = -1L

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
          if (blurIntensity > 0 && !isCapturing && now - lastUpdateMs >= minFrameIntervalMs) {
              lastUpdateMs = now
              post(::captureAndBlur)
          }
          true
      }

      // ── Lifecycle ─────────────────────────────────────────────────────────────

      override fun onAttachedToWindow() {
          super.onAttachedToWindow()
          if (blurIntensity > 0) {
              viewTreeObserver.addOnPreDrawListener(preDrawListener)
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) updateRenderEffect()
          }
      }

      override fun onDetachedFromWindow() {
          viewTreeObserver.removeOnPreDrawListener(preDrawListener)
          releaseRenderScriptResources()
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
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
          // Draw the frosted-glass tint overlay on top of the blur
          if (blurTintAlpha > 0) {
              tintPaint.alpha = blurTintAlpha
              canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
          }
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
          // API 31+: drive blur via RenderEffect on the view itself; no RenderScript needed.
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) updateRenderEffect()
          if (blurIntensity == 0) {
              currentBitmap?.recycle(); currentBitmap = null
              invalidate()
          }
      }

      /**
       * API 31+: apply or remove a hardware-accelerated [RenderEffect] blur on this view.
       * The snapshot bitmap drawn in [onDraw] provides the content; the GPU handles the blur.
       * This replaces the deprecated RenderScript path which fails silently on Android 12+.
       */
      @RequiresApi(Build.VERSION_CODES.S)
      private fun updateRenderEffect() {
          if (blurIntensity <= 0) {
              setRenderEffect(null)
          } else {
              val radius = (blurIntensity / 100f * 25f).coerceIn(1f, 25f)
              setRenderEffect(
                  android.graphics.RenderEffect.createBlurEffect(
                      radius, radius, android.graphics.Shader.TileMode.CLAMP,
                  ),
              )
          }
      }

      /**
       * 0-100 percentage of a white tint overlay drawn on top of the blur.
       * 0 = pure blur, 30 = light frosted-glass, 100 = fully opaque white.
       */
      fun setBlurTint(tintPercent: Int) {
          blurTintAlpha = (tintPercent.coerceIn(0, 100) / 100f * 255).toInt()
          if (currentBitmap != null) invalidate()
      }

      /**
       * Set how often the blur refreshes.
       * [fps] 5-30; clamped. Lower = less CPU/GPU use on older devices.
       */
      fun setFrameRate(fps: Int) {
          minFrameIntervalMs = (1000L / fps.coerceIn(5, 60))
      }

      /**
       * Set the fraction of source size used when capturing the blur sample.
       * [qualityPercent] 10-25 maps to 0.10f-0.25f. Lower = smaller bitmap = faster blur.
       */
      fun setCaptureQuality(qualityPercent: Int) {
          val newScale = qualityPercent.coerceIn(10, 25) / 100f
          if (newScale != captureScale) {
              captureScale = newScale
              // Force a fresh capture with the new scale
              lastFrameHash = -1L
          }
      }

      /**
       * When [enabled], the blur view checks an 8×8 pixel hash of the region behind it
       * before every render pass.  If the content hasn't changed (e.g. user isn't scrolling)
       * the expensive RenderScript pass is skipped entirely — zero visual difference.
       */
      fun setIdleSkip(enabled: Boolean) {
          skipWhenIdle = enabled
          if (!enabled) lastFrameHash = -1L
      }

      /** Fast 8×8 downsample XOR hash used for idle-skip comparison. */
      private fun quickHash(bmp: Bitmap): Long {
          val tiny = Bitmap.createScaledBitmap(bmp, 8, 8, false)
          var h = 0L
          for (y in 0 until 8) for (x in 0 until 8) h = h * 31L + tiny.getPixel(x, y)
          tiny.recycle()
          return h
      }

      // ── Capture & Blur ────────────────────────────────────────────────────────

      private fun captureAndBlur() {
          if (!isAttachedToWindow || width <= 0 || height <= 0 || blurIntensity <= 0) return
          val source = contentSource?.get() ?: return
          if (source.width <= 0 || source.height <= 0) return

          isCapturing = true

          // 1. Downscale source to 25 % for a fast capture + blur pipeline.
          //    Temporarily hide self so our own stale content doesn't appear in the snapshot
          //    (this matters when the source is the root CoordinatorLayout, which includes us).
          val wasVisible = visibility == VISIBLE
          if (wasVisible) visibility = INVISIBLE

          val scale = captureScale
          val sw = (source.width * scale).toInt().coerceAtLeast(1)
          val sh = (source.height * scale).toInt().coerceAtLeast(1)
          val srcBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
          val ok = runCatching {
              val c = Canvas(srcBmp)
              c.scale(scale, scale)
              source.draw(c)
          }.isSuccess

          if (wasVisible) visibility = VISIBLE
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

          // 3b. Idle-skip: hash a tiny 8×8 downsample of the crop and bail if unchanged
          if (skipWhenIdle) {
              val hash = quickHash(cropped)
              if (hash == lastFrameHash) { cropped.recycle(); isCapturing = false; return }
              lastFrameHash = hash
          }

          // 4. Blur with RenderScript (API < 31 only).
          //    On API 31+ the RenderEffect set in updateRenderEffect() handles blurring at draw
          //    time on the GPU, so we skip the expensive RenderScript pass entirely.
          val blurred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              cropped
          } else {
              blurBitmap(cropped, blurIntensity)
          }
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
  