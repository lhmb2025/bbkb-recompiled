package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.SwitchPreference

/**
 * Slideboard layout settings (Appearance & layout subpage)
 * Enable the slideboard, swap sides, customize the number pad, and edit quick phrases.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideboardLayoutScreen(
    onNavigateToCustomizeSlideBoard: () -> Unit,
    onNavigateToQuickPhrases: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }

    var slideboardActive by remember {
        mutableStateOf(
            prefs.getBoolean("slideboard_active", context.resources.getBoolean(R.bool.config_default_slideboard))
        )
    }
    var swapSlideboardLocations by remember {
        mutableStateOf(prefs.getString("slideboard_numeric_location", "1") == "0")
    }

    // Defaults are the resources SettingsValues falls back to, so the summary names the phrases the
    // slideboard actually inserts (it used to carry a stale copy of QuickPhrasesScreen's literals).
    fun phrase(key: String, defaultRes: Int): String =
        context.getString(defaultRes).let { prefs.getString(key, it) ?: it }

    fun readQuickPhrases(): Triple<String, String, String> = Triple(
        phrase("quick_phrase_1", R.string.pref_quick_phrase_1_default),
        phrase("quick_phrase_2", R.string.pref_quick_phrase_2_default),
        phrase("quick_phrase_3", R.string.pref_quick_phrase_3_default)
    )

    // State, refreshed on resume: these used to be read straight out of the composition body, so
    // every recomposition hit SharedPreferences and returning from QuickPhrasesScreen (which
    // writes these keys) left the summary showing the old phrases.
    var quickPhrases by remember { mutableStateOf(readQuickPhrases()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                quickPhrases = readQuickPhrases()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val (quickPhrase1, quickPhrase2, quickPhrase3) = quickPhrases

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_slideboard_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        val spacing = LocalSpacing.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Enable slideboard
            SwitchPreference(
                title = context.getString(R.string.settings_vkb_slideboard_enable_title),
                summary = context.getString(R.string.settings_vkb_slideboard_enable_summary),
                checked = slideboardActive,
                modifier = Modifier.settingsSearchAnchor("slideboard_active"),
                onCheckedChange = { newValue ->
                    slideboardActive = newValue
                    prefs.edit().putBoolean("slideboard_active", newValue).apply()
                }
            )

            // Swap locations
            SwitchPreference(
                title = context.getString(R.string.settings_vkb_slideboard_swap_title),
                summary = if (swapSlideboardLocations)
                    context.getString(R.string.settings_vkb_slideboard_swap_summary_swapped)
                else
                    context.getString(R.string.settings_vkb_slideboard_swap_summary_default),
                checked = swapSlideboardLocations,
                enabled = slideboardActive,
                modifier = Modifier.settingsSearchAnchor("slideboard_numeric_location"),
                onCheckedChange = { newValue ->
                    swapSlideboardLocations = newValue
                    prefs.edit()
                        .putString("slideboard_numeric_location", if (newValue) "0" else "1")
                        .putString("slideboard_quick_phrases_location", if (newValue) "1" else "0")
                        .apply()
                }
            )

            // Customize number pad
            if (slideboardActive) {
                PreferenceScreen(
                    title = context.getString(R.string.settings_vkb_customize_numberpad_title),
                    summary = context.getString(R.string.settings_vkb_customize_numberpad_summary),
                    onClick = onNavigateToCustomizeSlideBoard
                )
            } else {
                PreferenceItem(
                    title = context.getString(R.string.settings_vkb_customize_numberpad_title),
                    summary = context.getString(R.string.settings_vkb_customize_numberpad_summary),
                    enabled = false,
                    onClick = null
                )
            }

            // Quick phrases (always editable)
            PreferenceScreen(
                title = context.getString(R.string.settings_vkb_quick_phrases_title),
                summary = "$quickPhrase1, $quickPhrase2, $quickPhrase3, ...",
                onClick = onNavigateToQuickPhrases
            )

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}
