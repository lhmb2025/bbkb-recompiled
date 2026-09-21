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
 * Holds the anchor of the setting that should be scrolled-to and highlighted after a
 * search result is tapped. Provided to the whole settings NavHost via [LocalSettingsHighlight].
 */
class SettingsHighlightController {
    var pendingAnchor by mutableStateOf<String?>(null)
        private set

    fun request(anchor: String) {
        pendingAnchor = anchor
    }

    fun consume() {
        pendingAnchor = null
    }
}

val LocalSettingsHighlight = compositionLocalOf { SettingsHighlightController() }

/**
 * Marks a preference row as a search target. When the active search highlight matches [anchor],
 * the row is scrolled into view and a brief tint pulse is drawn over it.
 *
 * Relies on [BringIntoViewRequester] so it works inside the existing `verticalScroll` columns
 * without any manual offset math.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.settingsSearchAnchor(anchor: String): Modifier = composed {
    val controller = LocalSettingsHighlight.current
    val isActive = controller.pendingAnchor == anchor
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
            controller.consume()
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
