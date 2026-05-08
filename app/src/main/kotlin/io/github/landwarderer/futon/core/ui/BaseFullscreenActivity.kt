package io.github.landwarderer.futon.core.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.viewbinding.ViewBinding
import io.github.landwarderer.futon.R

abstract class BaseFullscreenActivity<B : ViewBinding> :
        BaseActivity<B>() {

        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                // systemUiController is now initialised by BaseActivity.onCreate() above.
                with(window) {
                        statusBarColor = Color.TRANSPARENT
                        navigationBarColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
                                ContextCompat.getColor(this@BaseFullscreenActivity, R.color.dim)
                        } else {
                                Color.TRANSPARENT
                        }
                        // API 31+ supports ALWAYS mode which extends into the cutout in every
                        // orientation (portrait and landscape). SHORT_EDGES only extends in landscape
                        // on many OEMs, leaving a letterbox gap in portrait on notch/punch-hole devices.
                        // Keep SHORT_EDGES for API 28–30 where ALWAYS is not available.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                attributes.layoutInDisplayCutoutMode =
                                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                attributes.layoutInDisplayCutoutMode =
                                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                }
                systemUiController.setSystemUiVisible(true)
        }

        /**
         * The reader (and any other fullscreen activity) manages system bar visibility
         * through its own show/hide logic ([ReaderActivity.setUiIsVisible]).
         * Suppress the global "app-wide immersive mode" so the two systems don't fight
         * each other — the reader's own controls take full precedence.
         */
        override fun applyImmersiveMode() = Unit
}
