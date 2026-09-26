package dev.bbkb.ime.core.settings.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.PrefsManager

/**
 * BBKB's colours for framework [AlertDialog]s.
 *
 * The settings screens are Compose under [BlackBerryTheme]; everything the keyboard itself pops
 * up (the Change-language menu, the add-word dialog, permission rationales, the input options
 * menu) is a platform dialog, which reads its colours from the theme of the context it is built
 * with. This picks `BbkbDialogTheme.Light` or `.Dark` the way [BlackBerryTheme] does, from
 * `pref_keyboard_theme_mode` ("auto" follows the system), so both kinds of UI match.
 */
object BbkbDialogs {

    @JvmStatic
    fun isDark(context: Context): Boolean =
        when (PrefsManager.getPrefs(context).getString("pref_keyboard_theme_mode", "auto")) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }

    /** [context] wrapped in the dialog theme; inflate dialog views and adapters from this too. */
    @JvmStatic
    fun themedContext(context: Context): Context =
        ContextThemeWrapper(context, if (isDark(context)) R.style.BbkbDialogTheme_Dark else R.style.BbkbDialogTheme_Light)

    @JvmStatic
    fun builder(context: Context): AlertDialog.Builder = AlertDialog.Builder(themedContext(context))
}
