package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.EditTextPreference
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost

/**
 * One slideboard quick phrase. [prefKey] is formed only here; the keys are persisted user state
 * that `SettingsValues` reads by name, so never rename them. [defaultRes] is the same fallback
 * `SettingsValues` uses.
 */
private class QuickPhrase(
    index: Int,
    @StringRes val titleRes: Int,
    @StringRes val defaultRes: Int,
    @DrawableRes val iconRes: Int,
) {
    val prefKey: String = "quick_phrase_$index"
}

private val QUICK_PHRASES = listOf(
    QuickPhrase(1, R.string.settings_quick_phrase_1, R.string.pref_quick_phrase_1_default, R.drawable.ic_settings_quick_phrase_1),
    QuickPhrase(2, R.string.settings_quick_phrase_2, R.string.pref_quick_phrase_2_default, R.drawable.ic_settings_quick_phrase_2),
    QuickPhrase(3, R.string.settings_quick_phrase_3, R.string.pref_quick_phrase_3_default, R.drawable.ic_settings_quick_phrase_3),
    QuickPhrase(4, R.string.settings_quick_phrase_4, R.string.pref_quick_phrase_4_default, R.drawable.ic_settings_quick_phrase_4),
    QuickPhrase(5, R.string.settings_quick_phrase_5, R.string.pref_quick_phrase_5_default, R.drawable.ic_settings_quick_phrase_5),
)

/**
 * The phrase keys in screen order, used by `LayoutsBundle` export and import.
 */
internal val QUICK_PHRASE_KEYS: List<String> get() = QUICK_PHRASES.map { it.prefKey }

/**
 * The phrases as the slideboard inserts them: stored value, or the `SettingsValues` default.
 */
internal fun readQuickPhrases(context: Context, prefs: SharedPreferences): List<String> =
    QUICK_PHRASES.map { phrase ->
        context.getString(phrase.defaultRes).let { prefs.getString(phrase.prefKey, it) ?: it }
    }

@Composable
fun QuickPhrasesScreen(
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(
        R.string.settings_quick_phrases_screen_title,
        onNavigateBack,
        QUICK_PHRASES.map { phrase ->
            Custom(inGroup = true) { env -> QuickPhraseRow(env.prefs, phrase) }
        },
    )
}

@Composable
private fun QuickPhraseRow(prefs: SharedPreferences, phrase: QuickPhrase) {
    val default = stringResource(phrase.defaultRes)
    var value by remember {
        mutableStateOf(prefs.getString(phrase.prefKey, default) ?: default)
    }

    // The phrase is the row's title; the slot name is the summary and dialog heading.
    EditTextPreference(
        title = stringResource(phrase.titleRes),
        rowTitle = value,
        summary = stringResource(phrase.titleRes),
        icon = ImageVector.vectorResource(phrase.iconRes),
        value = value,
        // A blank phrase would be an empty tile on the slideboard.
        allowBlank = false,
        capitalization = KeyboardCapitalization.Sentences,
        onValueChange = { newValue ->
            value = newValue
            prefs.edit().putString(phrase.prefKey, newValue).apply()
        }
    )
}
