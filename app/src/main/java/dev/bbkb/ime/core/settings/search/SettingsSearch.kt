package dev.bbkb.ime.core.settings.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.delay

/**
 * Anchors of the settings rows to scroll to and highlight after a search result or a "go set this
 * up" link is tapped. Several rows can be pending at once; each clears only its own anchor once it
 * has pulsed.
 */
class SettingsHighlightController {
    var pendingAnchors by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The first pending anchor, or null when nothing is pending. */
    val pendingAnchor: String? get() = pendingAnchors.firstOrNull()

    fun request(vararg anchors: String) {
        pendingAnchors = anchors.toSet()
    }

    fun consume(anchor: String) {
        pendingAnchors = pendingAnchors - anchor
    }

    fun consume() {
        pendingAnchors = emptySet()
    }
}

val LocalSettingsHighlight = compositionLocalOf { SettingsHighlightController() }

/**
 * Marks a row as a search target: when [anchor] is pending, the row is brought into view (works
 * inside `verticalScroll` columns without offset math) and a brief tint pulse is drawn over it.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.settingsSearchAnchor(anchor: String): Modifier = composed {
    val controller = LocalSettingsHighlight.current
    val isActive = anchor in controller.pendingAnchors
    val requester = remember { BringIntoViewRequester() }
    val highlightColor = MaterialTheme.colorScheme.primary
    val pulse = remember { Animatable(0f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            // Let the destination screen finish its first layout/navigation transition.
            delay(150)
            requester.bringIntoView()
            pulse.snapTo(1f)
            pulse.animateTo(0f, animationSpec = tween(durationMillis = 1400))
            controller.consume(anchor)
        }
    }

    this
        .bringIntoViewRequester(requester)
        .drawWithContent {
            drawContent()
            if (pulse.value > 0f) {
                drawRect(color = highlightColor.copy(alpha = 0.22f * pulse.value))
            }
        }
}
