package dev.bbkb.ime.core.settings.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens for the settings UI: type scale, 4dp spacing grid, shapes, motion, surfaces. */

val SettingsTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

@Immutable
data class Spacing(
    val none: Dp = 0.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val extraLarge: Dp = 24.dp,
    val extraExtraLarge: Dp = 32.dp,

    val contentPadding: Dp = 16.dp,
    val listItemPadding: Dp = 16.dp,
    val listItemVerticalPadding: Dp = 16.dp,
    val dialogListItemVerticalPadding: Dp = 8.dp, // radio items in selection dialogs
    val iconTextGap: Dp = 16.dp,
    val iconSize: Dp = 24.dp,
    val touchTarget: Dp = 48.dp,
    val oneLineListItemHeight: Dp = 56.dp,
    val titleSummaryGap: Dp = 8.dp,
    val categoryTopPadding: Dp = 24.dp,
    val categoryBottomPadding: Dp = 8.dp,

    // Grouped rows: a group is inset from the edge, rows sit 2dp apart, labels align with row text.
    val groupInset: Dp = 16.dp,
    val groupGap: Dp = 2.dp,
    val categoryStartPadding: Dp = 32.dp     // groupInset + listItemPadding
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

@Immutable
data class Shapes(
    val dialog: RoundedCornerShape = RoundedCornerShape(28.dp),
    /** A group of rows' outer corners; the first and last row inherit them through the clip. */
    val group: RoundedCornerShape = RoundedCornerShape(24.dp),
    /** One row's own corners inside a group. */
    val row: RoundedCornerShape = RoundedCornerShape(4.dp),
)

val LocalShapes = staticCompositionLocalOf { Shapes() }

@Immutable
data class Motion(
    /** Material 3 standard easing. */
    val standard: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f),

    val durationShort4: Int = 200
) {
    fun <T> fastSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = durationShort4,
        easing = standard
    )
}

val LocalMotion = staticCompositionLocalOf { Motion() }

/**
 * Page and row-card surfaces of the grouped layout, derived by [BlackBerryTheme]: light is
 * surfaceContainerLowest on surfaceContainer, dark is surfaceContainerHigh on surface.
 */
@Immutable
data class SettingsColors(
    val page: Color,
    val card: Color,
)

val LocalSettingsColors = staticCompositionLocalOf { SettingsColors(Color.Unspecified, Color.Unspecified) }
