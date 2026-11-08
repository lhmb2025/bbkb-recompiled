package dev.bbkb.ime.core.settings.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.core.update.UpdateChecker
import dev.bbkb.ime.core.update.UpdateStatus

/** The public repository the app is published from (also where its update list lives). */
private const val SOURCE_URL = "https://github.com/lhmb2025/bbkb-recompiled"
/** The BBKB community server: problem reports, feature requests and help. */
private const val DISCORD_URL = "https://discord.gg/HavRXQHKj7"

/**
 * About: which app this is and which version, where it stands on updates, where its source and
 * its community are, and what it is built with.
 *
 * The screen leads with the app's own icon and name, so the top bar draws no "About" title over
 * them (the title is still the pane title TalkBack announces); "BBKB" under the icon is the
 * screen's heading.
 */
@Composable
fun CreditsScreen(
    onNavigateToUpdates: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val version = context.getString(
        R.string.settings_credits_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()
    )
    // The last recorded check, read from preferences: nothing here touches the network.
    val updateSummary = remember { updateSummary(context, UpdateChecker(context).lastOutcome()) }

    SettingsScaffold(
        title = context.getString(R.string.settings_about_title),
        onNavigateBack = onNavigateBack,
        showTitle = false,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // ── The app ─────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.extraLarge)
                    .padding(top = 40.dp, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
            ) {
                AppIcon(size = 96)
                Spacer(modifier = Modifier.height(spacing.medium))
                Text(
                    text = context.getString(R.string.settings_credits_app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                // Tapping the version copies it, for a bug report.
                AssistChip(
                    onClick = { copyVersion(context, version) },
                    label = { Text(version) },
                    trailingIcon = {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_settings_m_content_copy),
                            contentDescription = context.getString(R.string.settings_about_copy_version),
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }

            // ── Updates ─────────────────────────────────────────────────────────
            // About is where a user looks for "what version am I on", so it is also where they
            // look for "is there a newer one"; the row says what the last check found.
            PreferenceGroup {
                PreferenceScreen(
                    title = context.getString(R.string.settings_updates_title),
                    summary = updateSummary,
                    onClick = onNavigateToUpdates,
                )
            }

            // ── Links ───────────────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(spacing.large))
            PreferenceGroup {
                LinkRow(
                    title = context.getString(R.string.settings_about_source_code),
                    summary = context.getString(R.string.settings_about_source_code_summary),
                    url = SOURCE_URL,
                )
                LinkRow(
                    title = context.getString(R.string.settings_about_report_problem),
                    summary = context.getString(R.string.settings_about_discord_summary),
                    url = DISCORD_URL,
                )
                PreferenceScreen(
                    title = context.getString(R.string.settings_about_licenses_title),
                    onClick = onNavigateToLicenses,
                )
            }

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

/**
 * Open-source licenses: the libraries BBKB is built with, alphabetical (ignoring case), each
 * opening its project page.
 */
@Composable
fun OpenSourceLicensesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    SettingsScaffold(
        title = context.getString(R.string.settings_about_licenses_title),
        onNavigateBack = onNavigateBack,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            PreferenceInfo(text = context.getString(R.string.settings_about_licenses_intro))
            PreferenceGroup {
                LIBRARIES.forEach { lib ->
                    LinkRow(title = lib.name, summary = "${lib.author} • ${lib.license}", url = lib.url)
                }
            }
            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

private class Library(val name: String, val author: String, val license: String, val url: String)

private val LIBRARIES = listOf(
    Library("AndroidX", "Google / AOSP", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    Library(
        "AndroidX Emoji2", "Google / AOSP", "Apache License 2.0",
        "https://developer.android.com/jetpack/androidx/releases/emoji2",
    ),
    Library("Emojibase", "Miles Johnson", "MIT License", "https://github.com/milesj/emojibase"),
    Library("Jetpack Compose", "Google", "Apache License 2.0", "https://developer.android.com/jetpack/compose"),
    Library("jsoup", "Jonathan Hedley", "MIT License", "https://jsoup.org/"),
    Library("Kotlin Coroutines", "JetBrains", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    Library(
        "Material Components", "Google", "Apache License 2.0",
        "https://github.com/material-components/material-components-android",
    ),
    Library("Shizuku API", "Rikka", "MIT License", "https://github.com/RikkaApps/Shizuku-API"),
).sortedBy { it.name.lowercase() }

/** A row that opens a web page, marked as leaving the app. */
@Composable
private fun LinkRow(title: String, summary: String, url: String) {
    val context = LocalContext.current
    PreferenceItem(
        title = title,
        summary = summary,
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        trailing = {
            Icon(
                ImageVector.vectorResource(R.drawable.ic_settings_m_open_in_new),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * The launcher icon as the system draws it (the adaptive icon, in the launcher's mask), which
 * `painterResource` cannot load from an `<adaptive-icon>` mipmap.
 */
@Composable
private fun AppIcon(size: Int) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.dp.roundToPx() }
    val bitmap: ImageBitmap = remember(px) {
        val drawable = context.applicationInfo.loadIcon(context.packageManager)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(bmp))
        bmp.asImageBitmap()
    }
    // Decorative: the app's name follows directly below it.
    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size.dp))
}

private fun copyVersion(context: Context, version: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.settings_credits_app_name), version))
    // Android 13 and later confirm a copy themselves; earlier versions say nothing.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.settings_about_version_copied, Toast.LENGTH_SHORT).show()
    }
}

/** What the Updates row says: the last check's answer, or the plain invitation to check. */
private fun updateSummary(context: Context, status: UpdateStatus?): String {
    val available = (status as? UpdateStatus.Available) ?: (status as? UpdateStatus.Failed)?.lastKnown
    return when {
        available != null ->
            context.getString(R.string.settings_update_available_banner, available.build.versionName)
        status is UpdateStatus.UpToDate -> context.getString(R.string.settings_update_up_to_date)
        status is UpdateStatus.NoChannel -> context.getString(R.string.settings_update_channel_none)
        else -> context.getString(R.string.settings_updates_summary)
    }
}
