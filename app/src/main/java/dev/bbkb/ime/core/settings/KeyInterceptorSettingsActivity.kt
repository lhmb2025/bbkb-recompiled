package dev.bbkb.ime.core.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** The helper accessibility service's settingsActivity: forwards to the KeyboardHelper page. */
class KeyInterceptorSettingsActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, ComposeSettingsActivity::class.java).apply {
            putExtra("screen", SettingsRoute.KeyboardHelper.route)
            // Back from settings goes to the launcher, not here.
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)

        finish()
    }
}
