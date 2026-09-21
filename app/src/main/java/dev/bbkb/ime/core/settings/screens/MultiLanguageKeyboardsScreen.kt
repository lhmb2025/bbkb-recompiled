package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageConfig
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageUtils
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceScreen

/**
 * Multi-Language Keyboards Screen (Compose)
 * Manages configured multi-language keyboard layouts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiLanguageKeyboardsScreen(
    onNavigateToAddKeyboard: () -> Unit,
    onNavigateToEditKeyboard: (MultiLanguageConfig) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    val spacing = LocalSpacing.current
    
    // Load keyboard configurations
    // Use toList() to create immutable snapshot for proper recomposition
    var keyboards by remember {
        mutableStateOf(MultiLanguageUtils.loadConfigs(prefs).toList())
    }
    
    // Reload on resume, so returning from the wizard actually refreshes the list. The empty
    // DisposableEffect that used to sit here did nothing, and LaunchedEffect(Unit) only runs on
    // first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                keyboards = MultiLanguageUtils.loadConfigs(prefs)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.multi_language_input_settings_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        // The screen's one creative action, named. It was a bare + in the app bar borrowing the
        // user-dictionary "Add" string for its content description.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToAddKeyboard,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.multi_language_input_add_keyboard)) }
            )
        }
    ) { paddingValues ->
        if (keyboards.isEmpty()) {
            // Empty state with icon
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = spacing.extraExtraLarge),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.medium)
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.multi_language_input_empty_screen),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // List of configured keyboards
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                // Keeps the last row clear of the extended FAB.
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(
                    items = keyboards,
                    key = { it.toString() }
                ) { keyboard ->
                    MultiLanguageKeyboardItem(
                        keyboard = keyboard,
                        onClick = { onNavigateToEditKeyboard(keyboard) }
                    )
                }
            }
        }
    }
}

/**
 * One configured keyboard, as the navigation row it is.
 *
 * It was a Material 3 `ListItem` — its own container, its own 16dp/8dp padding, its own titleMedium
 * — with a `HorizontalDivider` under it, so this list agreed with nothing else in settings. It is
 * [PreferenceScreen] now: bodyLarge title, bodyMedium summary, 16dp gutter, chevron, no rule.
 *
 * The supporting locales were a `FlowRow` of `AssistChip`s, which are a *choice* affordance: each
 * chip was clickable and each did the same thing as the row. They read as a summary line now,
 * which is what they always were — the languages this keyboard also types.
 */
@Composable
private fun MultiLanguageKeyboardItem(
    keyboard: MultiLanguageConfig,
    onClick: () -> Unit
) {
    val supporting = keyboard.getSupportingLocales()

    PreferenceScreen(
        title = keyboard.getPrimaryLocale().toString(),
        summary = if (supporting.isNotEmpty()) {
            supporting.joinToString(", ") { it.toString() }
        } else null,
        icon = Icons.Default.Keyboard,
        onClick = onClick,
    )
}
