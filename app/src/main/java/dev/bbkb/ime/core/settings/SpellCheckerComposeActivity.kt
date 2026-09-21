package dev.bbkb.ime.core.settings

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import dev.bbkb.ime.core.settings.screens.SpellCheckerSettingsScreen
import dev.bbkb.ime.BuildConfig

/**
 * Modern Compose-based Spell Checker Settings Activity
 * Launched from Android's system spell checker settings
 */
class SpellCheckerComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure window for better compatibility
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("SpellChecker", "Edge-to-edge configuration failed", e)
        }
        
        setContent {
            BlackBerryTheme {
                SpellCheckerSettingsScreen(
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}
