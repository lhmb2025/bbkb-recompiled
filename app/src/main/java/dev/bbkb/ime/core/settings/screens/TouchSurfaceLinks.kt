package dev.bbkb.ime.core.settings.screens

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuFacade
import dev.bbkb.ime.core.shared.Logger

/**
 * Where the Touch surface helper sends the user: the OEM's touchpad pages, Shizuku's releases and
 * the Shizuku app. Every link is a chain of intents tried in order, because the OEM pages exist
 * only on the firmware that has them.
 */
object TouchSurfaceLinks {

    private const val TAG = "TouchSurfaceLinks"

    /** The Unihertz settings package that owns the keyboard's touchpad pages. */
    const val OEM_SETTINGS_PACKAGE = "com.agui.settings"

    /** Settings > Gestures > Scroll assistant on the Titan 2 (Android 16). */
    val SCROLL_ASSISTANT = ComponentName(OEM_SETTINGS_PACKAGE, "$OEM_SETTINGS_PACKAGE.touchpad.ScrollAssistantActivity")

    /** Cursor assistant, which claims double-tap on the keys. "Cusor" is the OEM's own spelling. */
    val CURSOR_ASSISTANT = ComponentName(OEM_SETTINGS_PACKAGE, "$OEM_SETTINGS_PACKAGE.touchpad.CusorMoveAssistantActivity")

    /** AOSP's Gestures page; Android has no public action for it. */
    val SYSTEM_GESTURES = ComponentName("com.android.settings", "com.android.settings.Settings\$GestureSettingsActivity")

    const val SHIZUKU_RELEASES = "https://github.com/RikkaApps/Shizuku/releases"

    /** Scroll assistant, else the system Gestures page, else the Settings app. */
    fun scrollAssistant(): List<Intent> = oemPage(SCROLL_ASSISTANT)

    /** Cursor assistant, with the same fallbacks. */
    fun cursorAssistant(): List<Intent> = oemPage(CURSOR_ASSISTANT)

    private fun oemPage(page: ComponentName): List<Intent> = listOf(
        Intent().setComponent(page),
        Intent().setComponent(SYSTEM_GESTURES),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun installShizuku(): List<Intent> = listOf(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASES)))

    /** The Shizuku app's launcher, or null when it is not installed. */
    fun shizukuApp(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(ShizukuFacade.MANAGER_PACKAGE)

    /** Opens the first of [intents] that starts. */
    fun open(context: Context, intents: List<Intent>): Intent? = openFirst(intents) { intent ->
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Tries [intents] in order with [start] and returns the one that started, or null when none
     * did. A page that does not exist (ActivityNotFoundException) or is not exported
     * (SecurityException) moves on to the next.
     */
    fun openFirst(intents: List<Intent>, start: (Intent) -> Unit): Intent? {
        for (intent in intents) {
            try {
                start(intent)
                return intent
            } catch (missing: ActivityNotFoundException) {
                Logger.info(TAG, "no activity for $intent")
            } catch (refused: SecurityException) {
                Logger.info(TAG, "not allowed to open $intent")
            }
        }
        return null
    }
}
