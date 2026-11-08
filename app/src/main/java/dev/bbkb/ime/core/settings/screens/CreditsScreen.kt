package dev.bbkb.ime.core.settings.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SettingsScaffold

/**
 * About Screen - Shows version info and attributions for open source libraries
 */
@Composable
fun CreditsScreen(
    onNavigateToUpdates: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    
    // Version info, baked in at build time (the same values the manifest carries)
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE

    SettingsScaffold(
        title = context.getString(R.string.settings_about_title),
        onNavigateBack = onNavigateBack,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Version Info
            PreferenceGroup {
                PreferenceItem(
                    title = context.getString(R.string.settings_credits_app_name),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_bbkb_helper),
                    summary = context.getString(R.string.settings_credits_version, versionName, versionCode.toString()),
                    enabled = true,
                    onClick = null
                )
            
                // Updates. About is where a user looks for "what version am I on", so it is
                // also where they look for "is there a newer one".
                PreferenceItem(
                    title = context.getString(R.string.settings_updates_title),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_updates),
                    summary = context.getString(R.string.settings_updates_summary),
                    enabled = true,
                    onClick = onNavigateToUpdates
                )
            }

            // No rule under the version row: the CREDITS subhead below already separates the two,
            // and its 24dp top padding is the gap. A divider plus a subhead says the same thing
            // twice, at two different insets.
            PreferenceCategory(title = context.getString(R.string.settings_category_credits))

            PreferenceGroup {
                CreditItem(
                    name = "Emojibase",
                    author = "Miles Johnson",
                    license = "MIT License",
                    url = "https://github.com/milesj/emojibase"
                )

                CreditItem(
                    name = "Kotlin Coroutines",
                    author = "JetBrains",
                    license = "Apache License 2.0",
                    url = "https://github.com/Kotlin/kotlinx.coroutines"
                )

                CreditItem(
                    name = "Jetpack Compose",
                    author = "Google",
                    license = "Apache License 2.0",
                    url = "https://developer.android.com/jetpack/compose"
                )

                CreditItem(
                    name = "Material Components",
                    author = "Google",
                    license = "Apache License 2.0",
                    url = "https://github.com/material-components/material-components-android"
                )

                CreditItem(
                    name = "AndroidX",
                    author = "Google / AOSP",
                    license = "Apache License 2.0",
                    url = "https://developer.android.com/jetpack/androidx"
                )

                CreditItem(
                    name = "jsoup",
                    author = "Jonathan Hedley",
                    license = "MIT License",
                    url = "https://jsoup.org/"
                )

                CreditItem(
                    name = "AndroidX Emoji2",
                    author = "Google / AOSP",
                    license = "Apache License 2.0",
                    url = "https://developer.android.com/jetpack/androidx/releases/emoji2"
                )

                CreditItem(
                    name = "Shizuku API",
                    author = "Rikka",
                    license = "MIT License",
                    url = "https://github.com/RikkaApps/Shizuku-API"
                )
            }

            // Bottom spacing
            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

@Composable
private fun CreditItem(
    name: String,
    author: String,
    license: String,
    url: String
) {
    val context = LocalContext.current

    PreferenceItem(
        title = name,
        summary = "$author • $license",
        enabled = true,
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        }
    )
}
