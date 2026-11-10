package app.filesend.android

import androidx.compose.ui.graphics.Color

// Jetons de couleur : memes valeurs que windows/app/ui/style.css (verifies par tools/check-contrast.mjs,
// qui controle aussi l'egalite des deux palettes). Format 0xAARRGGBB.
internal const val LIGHT_TEXT = 0xFF1A1A1FL
internal const val LIGHT_MUTED = 0xFF3B3B44L
internal const val LIGHT_SOLID = 0xFFF3F3F6L
internal const val LIGHT_SURFACE = 0x8CFFFFFFL
internal const val LIGHT_STROKE = 0xBFFFFFFFL
internal const val LIGHT_ACCENT = 0xFF0A64D6L
internal const val LIGHT_ON_ACCENT = 0xFFFFFFFFL

internal const val DARK_TEXT = 0xFFF4F4F8L
internal const val DARK_MUTED = 0xFFD2D2DCL
internal const val DARK_SOLID = 0xFF1F1F24L
internal const val DARK_SURFACE = 0x17FFFFFFL
internal const val DARK_STROKE = 0x2EFFFFFFL
internal const val DARK_ACCENT = 0xFF5AA9FFL
internal const val DARK_ON_ACCENT = 0xFF04121FL

class GlassColors(
    val text: Color,
    val muted: Color,
    /** Fond opaque de repli quand le flou n'est pas disponible. */
    val solid: Color,
    val surface: Color,
    val stroke: Color,
    val accent: Color,
    val onAccent: Color,
)

fun glassColors(dark: Boolean): GlassColors =
    if (dark) GlassColors(
        Color(DARK_TEXT), Color(DARK_MUTED), Color(DARK_SOLID), Color(DARK_SURFACE),
        Color(DARK_STROKE), Color(DARK_ACCENT), Color(DARK_ON_ACCENT),
    ) else GlassColors(
        Color(LIGHT_TEXT), Color(LIGHT_MUTED), Color(LIGHT_SOLID), Color(LIGHT_SURFACE),
        Color(LIGHT_STROKE), Color(LIGHT_ACCENT), Color(LIGHT_ON_ACCENT),
    )

/**
 * Flou d'arriere-plan de la fenetre (Android 12+, Window.setBackgroundBlurRadius) : actif seulement
 * si le systeme l'autorise. Sinon (Android 10 et 11, economie d'energie, option desactivee) l'ecran
 * dessine un fond opaque (AC-F5-3). 80 px : valeur conseillee pour un effet de verre depoli
 * (source.android.com/docs/core/display/window-blurs, consulte le 2026-11-08).
 */
object GlassPolicy {
    const val BLUR_RADIUS_PX = 80

    fun blurRadius(sdkInt: Int, crossWindowBlurEnabled: Boolean): Int =
        if (sdkInt >= 31 && crossWindowBlurEnabled) BLUR_RADIUS_PX else 0
}
