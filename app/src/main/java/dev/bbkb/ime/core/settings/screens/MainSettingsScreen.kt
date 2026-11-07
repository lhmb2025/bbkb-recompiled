package dev.bbkb.ime.core.settings.screens

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.SettingsRoute
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SearchDeviceCapabilities
import dev.bbkb.ime.core.settings.search.SettingsSearchIndex
import dev.bbkb.ime.core.settings.search.SettingsSearchResult
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.core.update.UpdateChecker

/**
 * Main Settings Screen - Root navigation menu for all settings
 * Displays a search bar and all primary settings categories.
 */
@Composable
fun MainSettingsScreen(
    onNavigateToScreen: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val highlight = LocalSettingsHighlight.current

    var query by remember { mutableStateOf("") }

    // The last recorded "update available", if there is one. Preferences only.
    val updateAvailable = remember { UpdateChecker(context).lastKnownAvailable() }

    // Results are drawn from the device-filtered view of the index, never the raw list: a setting
    // whose row sits behind a DeviceProfile gate the device does not satisfy is not rendered by
    // its screen, so a result for it would navigate somewhere that does not contain it (defect 16).
    val deviceCapabilities = remember { SearchDeviceCapabilities.current() }
    val searchable = remember(deviceCapabilities) { SettingsSearchIndex.entriesFor(deviceCapabilities) }

    // Screens too, so a search for "physical keyboard" or "learned words" lists the page itself.
    val searchableScreens = remember(deviceCapabilities) { SettingsSearchIndex.screensFor(deviceCapabilities) }

    // The titles in lower case, resolved once per index and language instead of once per entry
    // on every typed character (about 100 string loads and copies per keystroke).
    val configuration = LocalConfiguration.current
    val searchableTitles = remember(searchable, configuration) {
        searchable.map { context.getString(it.titleRes).lowercase() }
    }
    val screenTitles = remember(searchableScreens, configuration) {
        searchableScreens.map { context.getString(it.titleRes).lowercase() }
    }

    val results = remember(query, searchable, searchableTitles, searchableScreens, screenTitles) {
        SettingsSearchIndex.search(query, searchableScreens, screenTitles, searchable, searchableTitles)
    }

    SettingsScaffold(
        title = context.getString(R.string.settings_title),
        onNavigateBack = onNavigateBack,
    ) { paddingValues ->
        val spacing = LocalSpacing.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // The search field as the Android 16 Settings app draws it: a pill on the page with no
            // outline, its fill one step above the page colour.
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.groupInset, vertical = spacing.small),
                placeholder = { Text(context.getString(R.string.settings_search_hint)) },
                leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_clear), contentDescription = stringResource(R.string.settings_common_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search)
            )

            if (query.isNotEmpty()) {
                // ── Search results ──────────────────────────────────────────────
                if (results.isEmpty()) {
                    Text(
                        text = context.getString(R.string.settings_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = spacing.categoryStartPadding, vertical = 24.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.height(spacing.small))
                    PreferenceGroup {
                        results.forEach { result ->
                            when (result) {
                                is SettingsSearchResult.Screen -> {
                                    val screen = result.screen
                                    PreferenceItem(
                                        title = context.getString(screen.titleRes),
                                        // Where the page sits: "Input › Physical keyboard". A
                                        // category sits on the settings root itself.
                                        summary = if (screen.parentPath.isEmpty()) {
                                            context.getString(R.string.settings_title)
                                        } else {
                                            screen.parentPath.joinToString(" › ") { context.getString(it) }
                                        },
                                        onClick = {
                                            val activity = screen.activity
                                            if (activity != null) {
                                                context.startActivity(Intent(context, activity))
                                            } else {
                                                onNavigateToScreen(screen.route)
                                            }
                                        }
                                    )
                                }
                                is SettingsSearchResult.Setting -> {
                                    val entry = result.setting
                                    PreferenceItem(
                                        title = context.getString(entry.titleRes),
                                        summary = context.getString(entry.categoryTitleRes),
                                        onClick = {
                                            entry.anchor?.let { highlight.request(it) }
                                            onNavigateToScreen(entry.route)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // ── Category list ───────────────────────────────────────────────

                // An update the last check found, read straight out of preferences — no network
                // call and no spinner on the settings root. Absent on a build with no update
                // channel and on a fresh install, which is why nothing is shown in those states.
                updateAvailable?.let { available ->
                    UpdateAvailableBanner(
                        version = available.build.versionName,
                        onClick = { onNavigateToScreen(SettingsRoute.Updates.route) }
                    )
                }

                Spacer(modifier = Modifier.height(spacing.small))
                PreferenceGroup {
                    // 1. Languages: straight to the consolidated screen. The old Languages
                    // menu stays registered for deep links and search results that route to it.
                    PreferenceScreen(
                        title = context.getString(R.string.settings_languages_title),
                        summary = context.getString(R.string.settings_languages_summary),
                        icon = ImageVector.vectorResource(R.drawable.ic_settings_m_language),
                        onClick = { onNavigateToScreen(SettingsRoute.LanguagesHub.route) }
                    )

                    // 2. Typing and input
                    PreferenceScreen(
                        title = context.getString(R.string.settings_preferences_title),
                        summary = context.getString(R.string.settings_preferences_summary),
                        icon = ImageVector.vectorResource(R.drawable.ic_settings_m_keyboard),
                        onClick = { onNavigateToScreen(SettingsRoute.Preferences.route) }
                    )

                    // 3. Suggestion and correction
                    PreferenceScreen(
                        title = context.getString(R.string.settings_suggestion_correction_title),
                        summary = context.getString(R.string.settings_suggestion_correction_summary),
                        icon = ImageVector.vectorResource(R.drawable.ic_settings_m_spellcheck),
                        onClick = { onNavigateToScreen(SettingsRoute.SuggestionCorrection.route) }
                    )

                    // 4. Appearance and layout
                    PreferenceScreen(
                        title = context.getString(R.string.settings_appearance_layout_title),
                        summary = context.getString(R.string.settings_appearance_layout_summary),
                        icon = ImageVector.vectorResource(R.drawable.ic_settings_m_palette),
                        onClick = { onNavigateToScreen(SettingsRoute.AppearanceLayout.route) }
                    )

                    // 5. Advanced
                    PreferenceScreen(
                        title = context.getString(R.string.settings_advanced_title),
                        summary = context.getString(R.string.settings_advanced_summary),
                        icon = ImageVector.vectorResource(R.drawable.ic_settings_m_settings),
                        onClick = { onNavigateToScreen(SettingsRoute.Advanced.route) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

/**
 * Compact "an update is waiting" banner at the top of the settings root.
 *
 * One row, one tap, no actions of its own: everything about the update — what it is, how big
 * it is, and the two deliberate presses that download and install it — lives on the Updates
 * screen.
 */
@Composable
private fun UpdateAvailableBanner(
    version: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_settings_m_cloud_download),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = context.getString(R.string.settings_update_available_banner, version),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
