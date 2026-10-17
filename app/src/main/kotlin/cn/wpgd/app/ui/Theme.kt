package cn.wpgd.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The design colours by role, one field per token of `design/tokens.json` (via [DesignTokens]). */
@Immutable
data class WpgdColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val ink: Color,
    val ink2: Color,
    val primary: Color,
    val accent: Color,
    val teamUs: Color,
    val teamThem: Color,
    val danger: Color,
    val wild: Color,
    val card: Color,
    val sel: Color,
    val selFill: Color,
    val suitRed: Color,
    val suitBlack: Color,
    val club4: Color,
    val diamond4: Color,
) {
    companion object {
        fun of(tokens: Map<String, Color>): WpgdColors {
            fun t(name: String) = tokens.getValue(name)
            return WpgdColors(
                bg = t("bg"), surface = t("surface"), surface2 = t("surface2"), line = t("line"), ink = t("ink"),
                ink2 = t("ink2"), primary = t("primary"), accent = t("accent"), teamUs = t("team.us"),
                teamThem = t("team.them"), danger = t("danger"), wild = t("wild"), card = t("card"), sel = t("sel"),
                selFill = t("selFill"), suitRed = t("suit.red"), suitBlack = t("suit.black"),
                club4 = t("suit.club4"), diamond4 = t("suit.diamond4"),
            )
        }
    }
}

/** Type scale (sp) and spacing scale (dp) by token name. */
object Tokens {
    fun type(name: String): TextUnit = DesignTokens.typeSp.getValue(name).sp

    /** `--sp-1…11` of the design: 2 4 8 12 16 20 24 32 40 64 72. */
    fun space(step: Int): Dp = DesignTokens.spacingDp[step - 1].dp

    fun radius(name: String): Dp = DesignTokens.radiiDp.getValue(name).dp

    /** The minimum touch target (design README: ≥ 44 pt / 48 dp). */
    val touch: Dp = 48.dp
}

private val LocalWpgdColors = staticCompositionLocalOf { WpgdColors.of(DesignTokens.light) }

object Wpgd {
    val colors: WpgdColors
        @Composable get() = LocalWpgdColors.current
}

/** Paper-white light theme, ink dark theme, vermillion accent; follows the system setting. */
@Composable
fun WpgdTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = WpgdColors.of(if (dark) DesignTokens.dark else DesignTokens.light)
    // The factory only picks the defaults of the colors not set here; the shared tokens apply to both.
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.primary, onPrimary = c.bg, background = c.bg, onBackground = c.ink, surface = c.surface,
        onSurface = c.ink, surfaceVariant = c.surface2, onSurfaceVariant = c.ink2, error = c.danger, outline = c.line,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalWpgdColors provides c, content = content)
    }
}
