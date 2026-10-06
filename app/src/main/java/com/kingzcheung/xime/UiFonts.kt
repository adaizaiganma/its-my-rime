package com.kingzcheung.xime

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat

/** Offline fonts share Latin/Chinese glyphs and metrics across Compose and the IME. */
internal object UiFonts {
    val body = FontFamily(
        Font(R.font.rime_sans_regular, FontWeight.Normal),
        Font(R.font.rime_sans_medium, FontWeight.Medium)
    )
    val display = FontFamily(Font(R.font.rime_display_regular, FontWeight.Normal))

    private val defaults = Typography()
    val typography = Typography(
        displayLarge = defaults.displayLarge.copy(fontFamily = display),
        displayMedium = defaults.displayMedium.copy(fontFamily = display),
        displaySmall = defaults.displaySmall.copy(fontFamily = display),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = display),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = display),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = display),
        titleLarge = defaults.titleLarge.copy(fontFamily = body),
        titleMedium = defaults.titleMedium.copy(fontFamily = body),
        titleSmall = defaults.titleSmall.copy(fontFamily = body),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = body),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = body),
        bodySmall = defaults.bodySmall.copy(fontFamily = body),
        labelLarge = defaults.labelLarge.copy(fontFamily = body),
        labelMedium = defaults.labelMedium.copy(fontFamily = body),
        labelSmall = defaults.labelSmall.copy(fontFamily = body)
    )

    private var regular: Typeface? = null
    private var medium: Typeface? = null
    private var displayFace: Typeface? = null

    fun displayTypeface(context: Context): Typeface =
        displayFace ?: requireNotNull(ResourcesCompat.getFont(context, R.font.rime_display_regular))
            .also { displayFace = it }

    fun bodyTypeface(context: Context, mediumWeight: Boolean = false): Typeface {
        if (mediumWeight) {
            return medium ?: requireNotNull(ResourcesCompat.getFont(context, R.font.rime_sans_medium))
                .also { medium = it }
        }
        return regular ?: requireNotNull(ResourcesCompat.getFont(context, R.font.rime_sans_regular))
            .also { regular = it }
    }
}
