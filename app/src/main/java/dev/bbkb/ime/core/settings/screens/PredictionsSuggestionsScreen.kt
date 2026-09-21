package dev.bbkb.ime.core.settings.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EmojiEmotions
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
import androidx.core.content.ContextCompat
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceActionItem
import dev.bbkb.ime.core.settings.ui.SwitchPreference

/**
 * Predictions & Suggestions Settings (Subpage 2 of Correction & Learning)
 * What gets predicted and suggested
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionsSuggestionsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    
    // Check if READ_CONTACTS permission is granted
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // GET_ACCOUNTS only works on API < 26 (Android 8.0)
    val showEmailToggle = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
    
    // Check if GET_ACCOUNTS permission is granted (only relevant for API < 26)
    var hasAccountsPermission by remember {
        mutableStateOf(
            if (showEmailToggle) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.GET_ACCOUNTS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        )
    }
    
    
    var showPredictions by remember {
        mutableStateOf(prefs.getBoolean("show_predictions", true))
    }
    var onKeyPredictions by remember {
        mutableStateOf(prefs.getBoolean("on_key_predictions", true))
    }
    var emojiPredictions by remember {
        // Same resource default SettingsValues.loadEmojiPredictionsSetting reads (false); a literal
        // `true` here showed the toggle on while the keyboard had the feature off.
        mutableStateOf(
            prefs.getBoolean("emoji_predictions", context.resources.getBoolean(R.bool.config_default_emoji_predictions))
        )
    }
    var nextWordPrediction by remember {
        mutableStateOf(prefs.getBoolean("next_word_prediction", true))
    }
    var flickCommitAnimation by remember {
        mutableStateOf(prefs.getBoolean("pref_flick_commit_animation", true))
    }
    var personalizedSuggestions by remember {
        mutableStateOf(prefs.getBoolean("pref_key_use_personalized_dicts", true))
    }
    var inlineAutofill by remember {
        mutableStateOf(prefs.getBoolean("pref_inline_autofill_enabled", true))
    }
    var suggestContacts by remember {
        mutableStateOf(prefs.getBoolean("pref_key_use_contacts_dict", true))
    }
    var suggestEmails by remember {
        mutableStateOf(prefs.getBoolean("pref_key_use_accounts_dict", true))
    }
    var forceSuggestions by remember {
        mutableStateOf(prefs.getBoolean("pref_force_suggestions", false))
    }
    var emojiDynamicSearch by remember {
        mutableStateOf(
            if (prefs.contains("pref_emoji_dynamic_search")) {
                prefs.getBoolean("pref_emoji_dynamic_search", false)
            } else {
                // Migrate from the legacy "pref_emoji_search_mode" multi-option setting.
                prefs.getString("pref_emoji_search_mode", "none") == "dynamic"
            }
        )
    }
    var emojiSearchReplaceText by remember {
        mutableStateOf(prefs.getBoolean("pref_emoji_search_replace_text", true))
    }
    
    // Permission launcher for READ_CONTACTS
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasContactsPermission = isGranted
        if (isGranted) {
            // Permission granted - turn on the toggle
            suggestContacts = true
            prefs.edit().putBoolean("pref_key_use_contacts_dict", true).apply()
        }
        // If not granted, toggle stays off (no change needed)
    }
    
    // Permission launcher for GET_ACCOUNTS (only used on API < 26)
    val accountsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAccountsPermission = isGranted
        if (isGranted) {
            // Permission granted - turn on the toggle
            suggestEmails = true
            prefs.edit().putBoolean("pref_key_use_accounts_dict", true).apply()
        }
        // If not granted, toggle stays off (no change needed)
    }
    
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_suggestion_title)) },
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
            // The contacts permission, as the row it gates rather than as a tinted card above it.
            if (!hasContactsPermission) {
                PreferenceActionItem(
                    title = context.getString(R.string.settings_pred_contacts_permission_title),
                    summary = context.getString(R.string.settings_pred_contacts_permission_summary),
                    actionLabel = context.getString(R.string.settings_pred_grant_permission),
                    onAction = {
                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    },
                )
            }

            // Show predictions (master toggle)
            SwitchPreference(
                title = context.getString(R.string.settings_pred_show_predictions_title),
                summary = context.getString(R.string.settings_pred_show_predictions_summary),
                checked = showPredictions,
                modifier = Modifier.settingsSearchAnchor("show_predictions"),
                onCheckedChange = { newValue ->
                    showPredictions = newValue
                    prefs.edit().putBoolean("show_predictions", newValue).apply()
                }
            )
            
            
            // On-key predictions (dependent)
            SwitchPreference(
                title = context.getString(R.string.settings_pred_on_key_title),
                summary = context.getString(R.string.settings_pred_on_key_summary),
                enabled = showPredictions,
                checked = onKeyPredictions,
                modifier = Modifier.settingsSearchAnchor("on_key_predictions"),
                onCheckedChange = { newValue ->
                    onKeyPredictions = newValue
                    prefs.edit().putBoolean("on_key_predictions", newValue).apply()
                }
            )
            
            
            // Emoji predictions (dependent)
            SwitchPreference(
                title = context.getString(R.string.settings_pred_emoji_title),
                summary = context.getString(R.string.settings_pred_emoji_summary),
                enabled = showPredictions,
                checked = emojiPredictions,
                modifier = Modifier.settingsSearchAnchor("emoji_predictions"),
                onCheckedChange = { newValue ->
                    emojiPredictions = newValue
                    prefs.edit().putBoolean("emoji_predictions", newValue).apply()
                }
            )
            
            
            // Next word prediction
            SwitchPreference(
                title = context.getString(R.string.settings_pred_next_word_title),
                summary = context.getString(R.string.settings_pred_next_word_summary),
                enabled = showPredictions,
                checked = nextWordPrediction,
                modifier = Modifier.settingsSearchAnchor("next_word_prediction"),
                onCheckedChange = { newValue ->
                    nextWordPrediction = newValue
                    prefs.edit().putBoolean("next_word_prediction", newValue).apply()
                }
            )


            // Personalized suggestions
            SwitchPreference(
                title = context.getString(R.string.settings_pred_personalized_title),
                summary = context.getString(R.string.settings_pred_personalized_summary),
                enabled = showPredictions,
                checked = personalizedSuggestions,
                modifier = Modifier.settingsSearchAnchor("pref_key_use_personalized_dicts"),
                onCheckedChange = { newValue ->
                    personalizedSuggestions = newValue
                    prefs.edit().putBoolean("pref_key_use_personalized_dicts", newValue).apply()
                }
            )


            // Inline autofill chips (Android 11+). The manager re-reads this at the start of
            // every input session, so the change applies without an IME restart.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                SwitchPreference(
                    title = context.getString(R.string.settings_pred_inline_autofill_title),
                    summary = context.getString(R.string.settings_pred_inline_autofill_summary),
                    checked = inlineAutofill,
                    modifier = Modifier.settingsSearchAnchor("pref_inline_autofill_enabled"),
                    onCheckedChange = { newValue ->
                        inlineAutofill = newValue
                        prefs.edit().putBoolean("pref_inline_autofill_enabled", newValue).apply()
                    }
                )
            }


            // Suggest from contacts (requires READ_CONTACTS permission)
            SwitchPreference(
                title = context.getString(R.string.settings_pred_contacts_title),
                summary = if (hasContactsPermission) {
                    context.getString(R.string.settings_pred_contacts_summary_enabled)
                } else {
                    context.getString(R.string.settings_pred_contacts_summary_disabled)
                },
                checked = suggestContacts && hasContactsPermission,
                enabled = showPredictions && hasContactsPermission,
                modifier = Modifier.settingsSearchAnchor("pref_key_use_contacts_dict"),
                onCheckedChange = { newValue ->
                    suggestContacts = newValue
                    prefs.edit().putBoolean("pref_key_use_contacts_dict", newValue).apply()
                }
            )
            
            // Suggest email addresses (only available on API < 26 where GET_ACCOUNTS works)
            if (showEmailToggle) {
                // Same treatment as the contacts prompt above.
                if (!hasAccountsPermission) {
                    PreferenceActionItem(
                        title = context.getString(R.string.settings_pred_accounts_permission_title),
                        summary = context.getString(R.string.settings_pred_accounts_permission_summary),
                        actionLabel = context.getString(R.string.settings_pred_grant_permission),
                        onAction = {
                            accountsPermissionLauncher.launch(Manifest.permission.GET_ACCOUNTS)
                        },
                    )
                }

                SwitchPreference(
                    title = context.getString(R.string.settings_pred_email_title),
                    summary = if (hasAccountsPermission) {
                        context.getString(R.string.settings_pred_email_summary_enabled)
                    } else {
                        context.getString(R.string.settings_pred_email_summary_disabled)
                    },
                    icon = Icons.Default.Email,
                    checked = suggestEmails && hasAccountsPermission,
                    enabled = hasAccountsPermission,
                    onCheckedChange = { newValue ->
                        suggestEmails = newValue
                        prefs.edit().putBoolean("pref_key_use_accounts_dict", newValue).apply()
                    }
                )
            }
            
            // Force suggestions: override an app's "no suggestions" declaration and show the
            // suggestion strip anyway (e.g. for apps like Google Keep that suppress it).
            SwitchPreference(
                title = context.getString(R.string.settings_pred_force_suggestions_title),
                summary = context.getString(R.string.settings_pred_force_suggestions_summary),
                enabled = showPredictions,
                checked = forceSuggestions,
                modifier = Modifier.settingsSearchAnchor("pref_force_suggestions"),
                onCheckedChange = { newValue ->
                    forceSuggestions = newValue
                    prefs.edit().putBoolean("pref_force_suggestions", newValue).apply()
                }
            )

            // Flick commit animation (committed word floats up on swipe-to-commit)
            SwitchPreference(
                title = context.getString(R.string.settings_pred_flick_animation_title),
                summary = context.getString(R.string.settings_pred_flick_animation_summary),
                enabled = showPredictions,
                checked = flickCommitAnimation,
                modifier = Modifier.settingsSearchAnchor("pref_flick_commit_animation"),
                onCheckedChange = { newValue ->
                    flickCommitAnimation = newValue
                    prefs.edit().putBoolean("pref_flick_commit_animation", newValue).apply()
                }
            )

            // Emoji dynamic search — surface emoji as you type
            SwitchPreference(
                title = context.getString(R.string.pref_emoji_dynamic_search_title),
                summary = if (emojiDynamicSearch)
                    context.getString(R.string.pref_emoji_dynamic_search_summary_on)
                else
                    context.getString(R.string.pref_emoji_dynamic_search_summary_off),
                icon = Icons.Default.EmojiEmotions,
                checked = emojiDynamicSearch,
                modifier = Modifier.settingsSearchAnchor("pref_emoji_dynamic_search"),
                onCheckedChange = { newValue ->
                    emojiDynamicSearch = newValue
                    prefs.edit().putBoolean("pref_emoji_dynamic_search", newValue).apply()
                }
            )

            // Replace text with emoji — only visible when Dynamic Search is enabled
            if (emojiDynamicSearch) {
                SwitchPreference(
                    title = context.getString(R.string.pref_emoji_search_replace_text_title),
                    summary = if (emojiSearchReplaceText)
                        context.getString(R.string.pref_emoji_search_replace_text_summary_on)
                    else
                        context.getString(R.string.pref_emoji_search_replace_text_summary_off),
                    icon = Icons.Default.AutoAwesome,
                    checked = emojiSearchReplaceText,
                    onCheckedChange = { newValue ->
                        emojiSearchReplaceText = newValue
                        prefs.edit().putBoolean("pref_emoji_search_replace_text", newValue).apply()
                    }
                )
            }

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}
