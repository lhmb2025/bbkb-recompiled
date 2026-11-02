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
 * Page transitions for the settings navigation, matching the system Settings app: Android 14's
 * own activity transitions (frameworks `activity_open_enter/exit`, `activity_close_enter/exit`),
 * measured against the emulator's Settings app frame by frame.
 *
 * - Going deeper, both pages slide a tenth of the width towards the start over 450 ms. The new
 *   page sits on top and fades in almost at once (83 ms, after 50 ms); the old one does not fade,
 *   it is simply covered.
 * - Going back is the mirror: both slide a tenth towards the end, and the page being left sits on
 *   top and fades out almost at once (83 ms, after 35 ms) over the page underneath.
 *
 * The short fades are what make it read as a slide; a fade as long as the slide reads as a
 * cross-fade. Directions follow the layout direction, so a right-to-left language slides the other
 * way. The navigation host stacks the pages: the new page above on the way in, the page being left
 * above on the way back.
 */
object SettingsTransitions {

    private const val DURATION = 450

    /** Material's emphasized easing, the curve behind fast_out_extra_slow_in. */
    private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** A tenth of the width, keeping the direction the container hands in. */
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
