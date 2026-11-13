package dev.bbkb.ime.core.settings.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry

/**
 * Settings page transitions copying Android 14's `activity_open_*` / `activity_close_*`.
 * Forward: both pages slide a tenth towards start over 450 ms and the new (top) page fades in
 * over 83 ms after 50 ms. Back mirrors it, the leaving (top) page fading out 83 ms after 35 ms.
 * The short fades make it read as a slide, not a cross-fade. Directions follow layout direction.
 */
object SettingsTransitions {

    private const val DURATION = 450

    /** Material emphasized easing (fast_out_extra_slow_in). */
    private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    private val tenth: (Int) -> Int = { it / 10 }

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(SlideDirection.Start, tween(DURATION, easing = Emphasized), tenth) +
            fadeIn(tween(83, delayMillis = 50, easing = LinearEasing))
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(SlideDirection.Start, tween(DURATION, easing = Emphasized), tenth)
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(SlideDirection.End, tween(DURATION, easing = Emphasized), tenth)
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(SlideDirection.End, tween(DURATION, easing = Emphasized), tenth) +
            fadeOut(tween(83, delayMillis = 35, easing = LinearEasing))
    }
}
