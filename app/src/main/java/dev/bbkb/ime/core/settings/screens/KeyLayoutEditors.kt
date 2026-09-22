package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.settings.screens.keyeditor.KeyLayoutEditorScreen
import dev.bbkb.ime.core.settings.screens.keyeditor.PkbSymbolPageSpec
import dev.bbkb.ime.core.settings.screens.keyeditor.SlideboardNumpadSpec
import dev.bbkb.ime.core.settings.screens.keyeditor.VkbSymbolPageSpec

/**
 * The three routes onto the shared key-layout editor. Each is one `KeyEditorSpec`; the editor
 * itself lives in [dev.bbkb.ime.core.settings.screens.keyeditor].
 */

/** Custom symbol page, for the physical (`isPkb`) or the on-screen keyboard. */
@Composable
fun CustomSymbolPageScreen(isPkb: Boolean, onBack: () -> Unit) =
    KeyLayoutEditorScreen(if (isPkb) PkbSymbolPageSpec else VkbSymbolPageSpec, onBack)

/** The slide-out numeric keypad's 4x5 symbol grid. */
@Composable
fun CustomizeSlideBoardScreen(onBack: () -> Unit) =
    KeyLayoutEditorScreen(SlideboardNumpadSpec, onBack)
