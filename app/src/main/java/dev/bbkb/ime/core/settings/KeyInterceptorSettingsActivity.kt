package dev.bbkb.ime.core.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Stub activity that redirects to the Advanced Settings screen in ComposeSettingsActivity.
 * This is used as the settingsActivity for the KeyInterceptorService accessibility service,
 * allowing users to tap "Settings" in the system accessibility settings to go directly
 * to the relevant settings page.
 */
class KeyInterceptorSettingsActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Launch ComposeSettingsActivity with the KeyboardHelper screen
        val intent = Intent(this, ComposeSettingsActivity::class.java).apply {
            putExtra("screen", SettingsRoute.KeyboardHelper.route)
            // Clear the task so pressing back from settings goes to launcher, not back here
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        
        // Finish this activity immediately so it doesn't appear in the back stack
        finish()
    }
}
