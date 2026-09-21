package dev.bbkb.ime.core.settings.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 3 Design System for BBKB Settings
 * 
 * Provides comprehensive design tokens for:
 * - Typography (Material 3 type scale)
 * - Spacing (4dp grid system)
 * - Shapes (corner radii)
 * - Motion (animation specs)
 */

// ============================================================================
// TYPOGRAPHY
// ============================================================================

/**
 * Material 3 Typography Scale
 * Based on Roboto font family with recommended sizes and weights
 */
val SettingsTypography = Typography(
    // Display styles - Large, prominent text
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
    
    // Headline styles - High-emphasis text
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
    
    // Title styles - Medium-emphasis text
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
    
    // Body styles - Main readable content
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
    
    // Label styles - Buttons, tabs, and labels
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

// ============================================================================
// SPACING
// ============================================================================

/**
 * Material 3 Spacing System
 * Based on 4dp grid with semantic naming
 */
@Immutable
data class Spacing(
    // Base units
    val none: Dp = 0.dp,
    val extraSmall: Dp = 4.dp,      // 1x grid unit
    val small: Dp = 8.dp,            // 2x grid unit
    val medium: Dp = 12.dp,          // 3x grid unit
    val large: Dp = 16.dp,           // 4x grid unit
    val extraLarge: Dp = 24.dp,      // 6x grid unit
    val extraExtraLarge: Dp = 32.dp, // 8x grid unit
    
    // Semantic spacing for specific use cases (Modern Android standards)
    val contentPadding: Dp = 16.dp,          // Standard content padding
    val listItemPadding: Dp = 16.dp,         // Horizontal padding for list items
    val listItemVerticalPadding: Dp = 16.dp, // Vertical padding for list items (Modern Android uses 16dp for 72dp height items)
    val dialogListItemVerticalPadding: Dp = 8.dp, // Vertical padding for radio items in selection dialogs
    val iconTextGap: Dp = 16.dp,             // Gap between icon and text
    val iconSize: Dp = 24.dp,                // Standard icon size
    val touchTarget: Dp = 48.dp,             // Minimum touch target size
    val oneLineListItemHeight: Dp = 56.dp,   // Material 3 one-line list item height
    val titleSummaryGap: Dp = 8.dp,          // Gap between title and summary (Modern Android standard)
    val categoryTopPadding: Dp = 24.dp,      // Extra top padding for categories (increased for modern look)
    val categoryBottomPadding: Dp = 8.dp     // Bottom padding for categories
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

// ============================================================================
// SHAPES
// ============================================================================

/**
 * Material 3 Shape System
 * Defines corner radii for different component sizes
 */
@Immutable
data class Shapes(
    val dialog: RoundedCornerShape = RoundedCornerShape(28.dp)
)

val LocalShapes = staticCompositionLocalOf { Shapes() }

// ============================================================================
// MOTION
// ============================================================================

/**
 * Material 3 Motion System
 * Defines animation specs with standard easing and durations
 */
@Immutable
data class Motion(
    /** The Material 3 standard easing curve. Read by [fastSpec]. */
    val standard: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f),

    /** 200 ms. Read by [fastSpec]. */
    val durationShort4: Int = 200
) {
    /** Fast animations for simple state changes */
    fun <T> fastSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = durationShort4,
        easing = standard
    )
}

val LocalMotion = staticCompositionLocalOf { Motion() }
