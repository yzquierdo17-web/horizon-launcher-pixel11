package com.horizon.launcher

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Inter (variable font, SIL Open Font License) at the weights Horizon uses. */
@OptIn(ExperimentalTextApi::class)
val InterFamily: FontFamily = FontFamily(
    listOf(200, 300, 400, 500, 600, 700, 800).map { w ->
        Font(
            R.font.inter,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

/** The font chosen in Settings → Look (Inter by default; null = the phone's font). */
val LocalAppFont = staticCompositionLocalOf<FontFamily?> { InterFamily }

/** Material type scale with the chosen font everywhere. */
fun typographyFor(font: FontFamily?): Typography {
    val base = Typography()
    if (font == null) return base
    fun TextStyle.f() = copy(fontFamily = font)
    return Typography(
        displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
        headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
        titleLarge = base.titleLarge.f(), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
        bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
        labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
    )
}
