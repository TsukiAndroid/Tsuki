package io.github.landwarderer.futon.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import io.github.landwarderer.futon.R
import org.koitharu.kotatsu.parsers.util.find

@Keep
enum class ColorScheme(
        @StyleRes val styleResId: Int,
        @StringRes val titleResId: Int,
) {

        DEFAULT(R.style.ThemeOverlay_Tsuki_Totoro, R.string.theme_name_totoro),
        MONET(R.style.ThemeOverlay_Tsuki_Monet, R.string.theme_name_dynamic),
        EXPRESSIVE(R.style.ThemeOverlay_Tsuki_Expressive, R.string.theme_name_expressive),
        MIKU(R.style.ThemeOverlay_Tsuki_Miku, R.string.theme_name_miku),
        RENA(R.style.ThemeOverlay_Tsuki_Asuka, R.string.theme_name_asuka),
        FROG(R.style.ThemeOverlay_Tsuki_Mion, R.string.theme_name_mion),
        BLUEBERRY(R.style.ThemeOverlay_Tsuki_Rikka, R.string.theme_name_rikka),
        SAKURA(R.style.ThemeOverlay_Tsuki_Sakura, R.string.theme_name_sakura),
        MAMIMI(R.style.ThemeOverlay_Tsuki_Mamimi, R.string.theme_name_mamimi),
        KANADE(R.style.ThemeOverlay_Tsuki_Kanade, R.string.theme_name_kanade),
        ITSUKA(R.style.ThemeOverlay_Tsuki_Itsuka, R.string.theme_name_itsuka),
        NORD(R.style.ThemeOverlay_Tsuki_Nord, R.string.theme_name_nord),
        MIDNIGHT(R.style.ThemeOverlay_Tsuki_Midnight, R.string.theme_name_midnight),
        OCEAN(R.style.ThemeOverlay_Tsuki_Ocean, R.string.theme_name_ocean),
        ;

        companion object {

                val default: ColorScheme
                        get() = if (DynamicColors.isDynamicColorAvailable()) {
                                MONET
                        } else {
                                DEFAULT
                        }

                fun getAvailableList(): List<ColorScheme> {
                        val list = ColorScheme.entries.toMutableList()
                        if (!DynamicColors.isDynamicColorAvailable()) {
                                list.remove(MONET)
                                list.remove(EXPRESSIVE)
                        }
                        return list
                }

                fun safeValueOf(name: String): ColorScheme? {
                        return ColorScheme.entries.find(name)
                }
        }
}
