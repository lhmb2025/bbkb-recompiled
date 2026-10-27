package dev.bbkb.ime.core.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.bbkb.ime.core.settings.screens.SpellCheckerSettingsScreen

/**
 * Modern Compose-based Spell Checker Settings Activity
 * Launched from Android's system spell checker settings
 */
class SpellCheckerComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Transparent system bars; BlackBerryTheme colours their icons to match the theme.
        enableEdgeToEdge()
        
        setContent {
            BlackBerryTheme {
                SpellCheckerSettingsScreen(
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}
