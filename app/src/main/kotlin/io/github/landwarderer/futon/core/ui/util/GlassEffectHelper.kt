package io.github.landwarderer.futon.core.ui.util

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

object GlassEffectHelper {

    fun applyBlurBackground(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP),
            )
        }
    }

    fun clearBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
