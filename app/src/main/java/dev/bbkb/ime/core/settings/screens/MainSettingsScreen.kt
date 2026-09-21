package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.SettingsRoute
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SearchDeviceCapabilities
import dev.bbkb.ime.core.settings.search.SettingsSearchIndex
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceScreen

/**
 * Main Settings Screen - Root navigation menu for all settings
 * Displays a search bar and all primary settings categories.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSettingsScreen(
    onNavigateToScreen: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val highlight = LocalSettingsHighlight.current

    var query by remember { mutableStateOf("") }

    // Results are drawn from the device-filtered view of the index, never the raw list: a setting
    // whose row sits behind a DeviceProfile gate the device does not satisfy is not rendered by
    // its screen, so a result for it would navigate somewhere that does not contain it (defect 16).
    val deviceCapabilities = remember { SearchDeviceCapabilities.current() }
    val searchable = remember(deviceCapabilities) { SettingsSearchIndex.entriesFor(deviceCapabilities) }

    val results = remember(query, searchable) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            emptyList()
        } else {
            searchable.filter { entry ->
                context.getString(entry.titleRes).lowercase().contains(q) ||
                    entry.keywords.contains(q)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_title)) },
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(context.getString(R.string.settings_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search)
            )

            if (query.isNotEmpty()) {
                // ── Search results ──────────────────────────────────────────────
                if (results.isEmpty()) {
                    Text(
                        text = context.getString(R.string.settings_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    )
                } else {
                    results.forEach { entry ->
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
            } else {
                // ── Category list ───────────────────────────────────────────────

                // 1. Languages
                PreferenceScreen(
                    title = context.getString(R.string.settings_languages_title),
                    summary = context.getString(R.string.settings_languages_summary),
                    icon = Icons.Default.Language,
                    onClick = { onNavigateToScreen(SettingsRoute.Languages.route) }
                )

                // 2. Typing and input
                PreferenceScreen(
                    title = context.getString(R.string.settings_preferences_title),
                    summary = context.getString(R.string.settings_preferences_summary),
                    icon = Icons.Default.Keyboard,
                    onClick = { onNavigateToScreen(SettingsRoute.Preferences.route) }
                )

                // 3. Suggestion and correction
                PreferenceScreen(
                    title = context.getString(R.string.settings_suggestion_correction_title),
                    summary = context.getString(R.string.settings_suggestion_correction_summary),
                    icon = Icons.Default.Spellcheck,
                    onClick = { onNavigateToScreen(SettingsRoute.SuggestionCorrection.route) }
                )

                // 4. Appearance and layout
                PreferenceScreen(
                    title = context.getString(R.string.settings_appearance_layout_title),
                    summary = context.getString(R.string.settings_appearance_layout_summary),
                    icon = Icons.Default.Palette,
                    onClick = { onNavigateToScreen(SettingsRoute.AppearanceLayout.route) }
                )

                // 5. Advanced
                PreferenceScreen(
                    title = context.getString(R.string.settings_advanced_title),
                    summary = context.getString(R.string.settings_advanced_summary),
                    icon = Icons.Default.Settings,
                    onClick = { onNavigateToScreen(SettingsRoute.Advanced.route) }
                )
            }
        }
    }
}
