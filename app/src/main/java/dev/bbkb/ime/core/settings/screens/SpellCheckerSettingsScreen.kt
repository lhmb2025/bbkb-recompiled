package dev.bbkb.ime.core.settings.screens

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.textservice.TextServicesManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.core.settings.ui.SwitchPreference
import dev.bbkb.ime.core.settings.ui.rememberContactsPermission

/**
 * Spell Checker Settings Screen
 * Configure spell checking behavior and permissions
 */
@Composable
fun SpellCheckerSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    
    
    // Get preference values
    var useContacts by remember {
        mutableStateOf(prefs.getBoolean("pref_spellcheck_use_contacts", true))
    }
    // Read by AndroidSpellCheckerService.PREF_ACCEPT_LEARNED_WORDS
    var acceptLearnedWords by remember {
        mutableStateOf(prefs.getBoolean("pref_spellcheck_accept_learned_words", true))
    }

    // Turning "Accept contact names" on asks for the contacts permission; a grant turns it on.
    // Nothing is asked for just by opening the screen.
    val contacts = rememberContactsPermission { granted ->
        if (granted) {
            useContacts = true
            prefs.edit().putBoolean("pref_spellcheck_use_contacts", true).apply()
        }
    }

    // Whether Android uses BBKB to spell check. Only Android's own spell checker setting can
    // change that, so it is re-read whenever the user comes back from there.
    var bbkbIsSpellChecker by remember { mutableStateOf(isBbkbSpellChecker(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { bbkbIsSpellChecker = isBbkbSpellChecker(context) }

    
    
    SettingsScaffold(
        title = context.getString(R.string.settings_spell_checker_title),
        onNavigateBack = onNavigateBack,
    ) { paddingValues ->
        val spacing = LocalSpacing.current
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // What the screen is for, as a sentence rather than as a card with a heading. The
            // heading said "About Spell Checker" one line under an app bar reading "Spell
            // checker", so it named the screen twice and told the reader nothing.
            PreferenceInfo(
                text = stringResource(R.string.settings_spellcheck_intro)
            )

            // The master row, in a card of its own as "Enable suggestions" is. The switch shows
            // whether Android uses BBKB to spell check. Apps cannot choose the system spell
            // checker, so tapping the row or the switch opens Android's picker; the switch catches
            // up when the user comes back (ON_RESUME above).
            PreferenceGroup {
                SwitchPreference(
                    title = stringResource(R.string.settings_spellcheck_enable_title),
                    summary = stringResource(R.string.settings_spellcheck_enable_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_m_spellcheck),
                    checked = bbkbIsSpellChecker,
                    onCheckedChange = { openSpellCheckerSettings(context) },
                )
            }
            Spacer(modifier = Modifier.height(spacing.large))

            PreferenceGroup {

                // Use Contacts for Spellchecking
                SwitchPreference(
                    title = context.getString(R.string.settings_spellcheck_use_contacts_title),
                    summary = context.getString(
                        if (contacts.granted) R.string.settings_spellcheck_use_contacts_summary_enabled
                        else R.string.settings_pred_contacts_summary_disabled
                    ),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_m_contacts),
                    checked = useContacts && contacts.granted,
                    onCheckedChange = { newValue ->
                        if (newValue && !contacts.granted) {
                            contacts.request()
                        } else {
                            useContacts = newValue
                            prefs.edit().putBoolean("pref_spellcheck_use_contacts", newValue).apply()
                        }
                    }
                )

                SwitchPreference(
                    title = context.getString(R.string.settings_spellcheck_accept_learned_title),
                    summary = context.getString(R.string.settings_spellcheck_accept_learned_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_learned_words),
                    checked = acceptLearnedWords,
                    onCheckedChange = { newValue ->
                        acceptLearnedWords = newValue
                        prefs.edit().putBoolean("pref_spellcheck_accept_learned_words", newValue).apply()
                    }
                )
            }

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

/** True when Android's spell checker is on and is BBKB's. */
private fun isBbkbSpellChecker(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val tsm = context.getSystemService(TextServicesManager::class.java) ?: return false
        return tsm.isSpellCheckerEnabled && tsm.currentSpellCheckerInfo?.packageName == context.packageName
    }
    // Before Android 12 there is no public reader; the platform keeps both answers here.
    val resolver = context.contentResolver
    val enabled = Settings.Secure.getInt(resolver, "spell_checker_enabled", 1) != 0
    val selected = Settings.Secure.getString(resolver, "selected_spell_checker") ?: return false
    return enabled && ComponentName.unflattenFromString(selected)?.packageName == context.packageName
}

/**
 * Android's spell checker picker (Settings › System › Languages › Spell checker). It has no public
 * action, but the Settings app exports the screen; anywhere it is missing, the language and input
 * settings that lead to it.
 */
private fun openSpellCheckerSettings(context: Context) {
    val picker = Intent().setClassName(
        "com.android.settings", "com.android.settings.Settings\$SpellCheckersSettingsActivity"
    )
    try {
        context.startActivity(picker)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }
}

