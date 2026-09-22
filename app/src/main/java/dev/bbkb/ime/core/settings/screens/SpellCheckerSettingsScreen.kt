package dev.bbkb.ime.core.settings.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.ListPreference
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceActionItem
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.SwitchPreference

/**
 * Spell Checker Settings Screen
 * Configure spell checking behavior and permissions
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpellCheckerSettingsScreen(
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
    
    // Get preference values
    var useContacts by remember {
        mutableStateOf(prefs.getBoolean("pref_spellcheck_use_contacts", true))
    }
    
    var sensitivity by remember {
        mutableStateOf(prefs.getString("pref_spellcheck_sensitivity", "balanced") ?: "balanced")
    }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasContactsPermission = isGranted
        if (!isGranted) {
            // If permission denied, disable the preference
            useContacts = false
            prefs.edit().putBoolean("pref_spellcheck_use_contacts", false).apply()
        }
    }
    
    // Request permission on first load if not granted and preference is enabled
    LaunchedEffect(Unit) {
        if (!hasContactsPermission && useContacts) {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_spell_checker_screen_title)) },
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
            // What the screen is for, as a sentence rather than as a card with a heading. The
            // heading said "About Spell Checker" one line under an app bar reading "Spell
            // checker", so it named the screen twice and told the reader nothing.
            PreferenceInfo(
                text = "The BBKB spell checker provides intelligent spell checking across all apps that support spell checking. It uses advanced language models to detect typos and suggest corrections."
            )

            // The contacts permission, as the row it gates rather than as a tinted card above it.
            if (!hasContactsPermission) {
                PreferenceActionItem(
                    title = "Contacts Permission Required",
                    summary = "Grant contacts permission to recognize contact names as valid words during spell checking.",
                    actionLabel = "Grant Permission",
                    onAction = {
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    },
                )
            }

            // Use Contacts for Spellchecking
            SwitchPreference(
                title = context.getString(R.string.settings_spellcheck_use_contacts_title),
                summary = if (hasContactsPermission) {
                    context.getString(R.string.settings_spellcheck_use_contacts_summary_enabled)
                } else {
                    context.getString(R.string.settings_spellcheck_use_contacts_summary_disabled)
                },
                icon = Icons.Default.Contacts,
                checked = useContacts && hasContactsPermission,
                enabled = hasContactsPermission,
                onCheckedChange = { newValue ->
                    if (!hasContactsPermission && newValue) {
                        // Request permission if trying to enable
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    } else {
                        useContacts = newValue
                        prefs.edit().putBoolean("pref_spellcheck_use_contacts", newValue).apply()
                    }
                }
            )

            // Spell Check Sensitivity
            ListPreference(
                title = context.getString(R.string.settings_spellcheck_sensitivity_title),
                summary = when (sensitivity) {
                    "lenient" -> context.getString(R.string.settings_spellcheck_sensitivity_lenient)
                    "balanced" -> context.getString(R.string.settings_spellcheck_sensitivity_balanced)
                    "strict" -> context.getString(R.string.settings_spellcheck_sensitivity_strict)
                    else -> context.getString(R.string.settings_spellcheck_sensitivity_balanced)
                },
                icon = Icons.Default.Tune,
                value = sensitivity,
                entries = listOf("Lenient", "Balanced", "Strict"),
                entryValues = listOf("lenient", "balanced", "strict"),
                onValueChange = { newValue ->
                    sensitivity = newValue
                    prefs.edit().putString("pref_spellcheck_sensitivity", newValue).apply()
                }
            )
            
            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}
