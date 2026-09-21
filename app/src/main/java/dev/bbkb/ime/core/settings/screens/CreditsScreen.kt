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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceItem

/**
 * About Screen - Shows version info and attributions for open source libraries
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    
    // Version info from PackageManager
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: "Unknown"
    val versionCode = packageInfo?.let {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            it.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            it.versionCode.toLong()
        }
    } ?: 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_about_title)) },
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
            // Version Info
            PreferenceItem(
                title = context.getString(R.string.settings_credits_app_name),
                summary = "Version $versionName (Build $versionCode)",
                enabled = true,
                onClick = null
            )
            
            // No rule under the version row: the CREDITS subhead below already separates the two,
            // and its 24dp top padding is the gap. A divider plus a subhead says the same thing
            // twice, at two different insets.
            PreferenceCategory(title = context.getString(R.string.settings_category_credits))

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
                name = "Timber",
                author = "Jake Wharton",
                license = "Apache License 2.0",
                url = "https://github.com/JakeWharton/timber"
            )

            CreditItem(
                name = "Apache Commons IO",
                author = "Apache Software Foundation",
                license = "Apache License 2.0",
                url = "https://commons.apache.org/proper/commons-io/"
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
                name = "Gson",
                author = "Google",
                license = "Apache License 2.0",
                url = "https://github.com/google/gson"
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
