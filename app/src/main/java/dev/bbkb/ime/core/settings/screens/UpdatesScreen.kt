package dev.bbkb.ime.core.settings.screens

import android.Manifest
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import dev.bbkb.ime.core.distribution.DistributionException
import dev.bbkb.ime.core.distribution.HttpStatusException
import dev.bbkb.ime.core.distribution.IntegrityException
import dev.bbkb.ime.core.distribution.OfflineException
import dev.bbkb.ime.core.distribution.UnsupportedSchemaException
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.LocalSettingsColors
import dev.bbkb.ime.core.settings.ui.LocalShapes
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.core.settings.ui.SwitchPreference
import dev.bbkb.ime.core.update.ApkInstaller
import dev.bbkb.ime.core.update.UpdateChecker
import dev.bbkb.ime.core.update.UpdateJobService
import dev.bbkb.ime.core.update.UpdateNotifier
import dev.bbkb.ime.core.update.UpdateStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OTA app updates. Opens on the last recorded outcome ([UpdateChecker.lastOutcome], no network),
 * so there is no spinner on entry; only "Check now" fetches, "Download" transfers and "Install"
 * hands off to the installer. Debug builds usually have no channel ([UpdateStatus.NoChannel]),
 * which is stated as a fact, not an error.
 */
@Composable
fun UpdatesScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()

    val checker = remember { UpdateChecker(context) }
    val installer = remember { ApkInstaller(context) }

    var status by remember { mutableStateOf(checker.lastOutcome()) }
    var checking by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var explainUnknownSources by remember { mutableStateOf(false) }
    var backgroundCheck by remember {
        mutableStateOf(
            PrefsManager.getPrefs(context)
                .getBoolean(UpdateJobService.PREF_BACKGROUND_CHECK, true)
        )
    }

    // POST_NOTIFICATIONS matters only on API 33+. A denial costs only the notification: the toggle
    // stays on and the check still runs.
    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* granted or not, the daily check is unaffected */ }

    // Opening this screen counts as being told, so the named version must not notify again.
    LaunchedEffect(Unit) {
        UpdateNotifier.cancel(context)
        // A fresh install has not rebooted or been replaced, so schedule the default-on daily check here.
        // Idempotent; schedules nothing on a build with no update channel.
        UpdateJobService.sync(context)
        (status as? UpdateStatus.Available)?.let { checker.markSeen(it.build.versionCode) }
        // An already downloaded and verified APK for this update offers Install instead of Download.
        val available = status as? UpdateStatus.Available ?: return@LaunchedEffect
        val existing = withContext(Dispatchers.IO) { installer.downloadedApk(available.build) }
        if (existing != null) download = DownloadState.Done(existing)
    }

    // One check from either button; a found update may already be downloaded, so offer Install then.
    val runCheck: () -> Unit = {
        checking = true
        scope.launch {
            val outcome = checker.check(force = true)
            val shown = (outcome as? UpdateStatus.Available)
                ?: (outcome as? UpdateStatus.Failed)?.lastKnown
            download = shown
                ?.let { withContext(Dispatchers.IO) { installer.downloadedApk(it.build) } }
                ?.let { DownloadState.Done(it) }
                ?: DownloadState.Idle
            if (outcome is UpdateStatus.Available) checker.markSeen(outcome.build.versionCode)
            status = outcome
            checking = false
        }
    }
    val runDownload: (UpdateStatus.Available) -> Unit = { available ->
        download = DownloadState.Running(0)
        scope.launch {
            val result = installer.download(available.build) { bytes, total ->
                // Arrives on the IO dispatcher; snapshot writes are thread-safe. Only whole percents recompose.
                val pct = percentOf(bytes, total)
                val shown = (download as? DownloadState.Running)?.percent
                if (shown != pct) download = DownloadState.Running(pct)
            }
            download = result.fold(
                onSuccess = { DownloadState.Done(it) },
                onFailure = { DownloadState.Failed(it) }
            )
        }
    }
    val runInstall: () -> Unit = install@{
        val apk = (download as? DownloadState.Done)?.file ?: return@install
        if (!installer.canRequestInstalls()) {
            explainUnknownSources = true
        } else if (!installer.startInstall(apk)) {
            // The file went away (cache eviction): offer the download again.
            download = DownloadState.Idle
        }
    }

    SettingsScaffold(
        title = context.getString(R.string.settings_updates_title),
        onNavigateBack = onNavigateBack,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            StatusCard(
                context = context,
                status = status,
                checking = checking,
                download = download,
                lastCheckedAt = checker.lastCheckedAt(),
                onCheck = runCheck,
                onDownload = runDownload,
                onInstall = runInstall,
            )

            // No subhead: under a screen titled "Updates", one titled "Updates" says nothing.
            Spacer(modifier = Modifier.height(spacing.extraLarge))
            PreferenceGroup {
                SwitchPreference(
                    title = context.getString(R.string.settings_update_background_check_title),
                    summary = context.getString(R.string.settings_update_background_check_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_m_notifications),
                    checked = backgroundCheck,
                    // A literal, not UpdateJobService.PREF_BACKGROUND_CHECK: SettingsSearchIndexTest greps for it.
                    modifier = Modifier.settingsSearchAnchor("pref_update_background_check"),
                    onCheckedChange = { enabled ->
                        backgroundCheck = enabled
                        PrefsManager.getPrefs(context).edit()
                            .putBoolean(UpdateJobService.PREF_BACKGROUND_CHECK, enabled)
                            .apply()
                        UpdateJobService.sync(context)
                        if (enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !UpdateNotifier.canPostNotifications(context)
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }

            // Reference facts last. The version reads exactly as on About.
            PreferenceCategory(title = context.getString(R.string.settings_update_version_details))
            PreferenceGroup {
                PreferenceItem(
                    title = context.getString(R.string.settings_update_installed_version_title),
                    summary = context.getString(
                        R.string.settings_credits_version,
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(),
                    ),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_m_info),
                    onClick = null
                )
                PreferenceItem(
                    title = context.getString(R.string.settings_update_channel_title),
                    summary = channelSummary(context, checker),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_update_channel),
                    onClick = null
                )
            }

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }

    if (explainUnknownSources) {
        AlertDialog(
            onDismissRequest = { explainUnknownSources = false },
            icon = {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_settings_m_download),
                    contentDescription = null
                )
            },
            title = {
                Text(
                    context.getString(R.string.settings_update_unknown_sources_title),
                    textAlign = TextAlign.Center,
                )
            },
            text = { Text(context.getString(R.string.settings_update_unknown_sources_message)) },
            confirmButton = {
                TextButton(onClick = {
                    explainUnknownSources = false
                    context.startActivity(installer.unknownSourcesIntent())
                }) { Text(context.getString(R.string.settings_update_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { explainUnknownSources = false }) {
                    Text(context.getString(android.R.string.cancel))
                }
            }
        )
    }
}

/** Whole percent of [bytes] out of [total]; 0 when the total is unknown. */
private fun percentOf(bytes: Long, total: Long): Int =
    if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 100) else 0

/** Where a download has got to. Not persisted: a download is one sitting's work. */
private sealed class DownloadState {
    object Idle : DownloadState()
    data class Running(val percent: Int) : DownloadState()
    data class Done(val file: File) : DownloadState()
    data class Failed(val error: Throwable) : DownloadState()
}

private enum class BadgeTone { Calm, Attention, Error }

/**
 * One answer to "where do I stand?": badge, headline, one line, release notes while an update is
 * offered, and the single sensible action. Every [UpdateStatus] x [DownloadState] maps to one
 * layout; a failed refresh that still knows an update shows it, with the failure as a line.
 */
@Composable
private fun StatusCard(
    context: Context,
    status: UpdateStatus?,
    checking: Boolean,
    download: DownloadState,
    lastCheckedAt: Long,
    onCheck: () -> Unit,
    onDownload: (UpdateStatus.Available) -> Unit,
    onInstall: () -> Unit,
) {
    val lastChecked = if (lastCheckedAt > 0L) {
        context.getString(R.string.settings_update_last_checked_at, lastCheckedSummary(context, lastCheckedAt))
    } else {
        null
    }
    val offered = (status as? UpdateStatus.Available) ?: (status as? UpdateStatus.Failed)?.lastKnown
    val checkButton: @Composable () -> Unit = {
        Button(enabled = !checking, onClick = onCheck) {
            Text(context.getString(R.string.settings_update_check_now))
        }
    }

    when {
        checking -> StatusCardFrame(
            badge = R.drawable.ic_settings_m_refresh, tone = BadgeTone.Calm,
            headline = context.getString(R.string.settings_update_checking),
            supporting = lastChecked,
            progress = null, // indeterminate
            showProgress = true,
            action = checkButton,
        )

        status is UpdateStatus.NoChannel -> StatusCardFrame(
            badge = R.drawable.ic_settings_m_info, tone = BadgeTone.Calm,
            // A fact about this build, not an error; there is nothing to check against.
            headline = context.getString(R.string.settings_update_channel_none),
        )

        offered != null -> {
            val version = context.getString(R.string.settings_update_version_label, offered.build.versionName)
            val size = offered.build.size.takeIf { it > 0L }
                ?.let { Formatter.formatShortFileSize(context, it) }
            val checkError = (status as? UpdateStatus.Failed)?.error?.let { errorMessage(context, it) }
            when (download) {
                is DownloadState.Done -> StatusCardFrame(
                    badge = R.drawable.ic_settings_m_check_circle, tone = BadgeTone.Attention,
                    headline = context.getString(R.string.settings_update_ready_title),
                    supporting = "$version · ${context.getString(R.string.settings_update_downloaded)}",
                    notes = offered.build.notes,
                    action = {
                        Button(onClick = onInstall) {
                            Text(context.getString(R.string.settings_update_install))
                        }
                    },
                )

                is DownloadState.Running -> StatusCardFrame(
                    badge = R.drawable.ic_settings_m_download, tone = BadgeTone.Attention,
                    headline = context.getString(R.string.settings_update_available_title),
                    supporting = context.getString(R.string.settings_update_downloading, download.percent),
                    // Determinate: the manifest states the size.
                    progress = download.percent / 100f,
                    showProgress = true,
                    notes = offered.build.notes,
                )

                else -> StatusCardFrame(
                    badge = R.drawable.ic_settings_m_cloud_download, tone = BadgeTone.Attention,
                    headline = context.getString(R.string.settings_update_available_title),
                    supporting = listOfNotNull(version, size).joinToString(" · "),
                    error = (download as? DownloadState.Failed)?.let { errorMessage(context, it.error) }
                        ?: checkError,
                    notes = offered.build.notes,
                    action = {
                        Button(onClick = { onDownload(offered) }) {
                            Text(
                                context.getString(
                                    if (download is DownloadState.Failed) R.string.settings_update_retry
                                    else R.string.settings_update_download
                                )
                            )
                        }
                    },
                )
            }
        }

        status is UpdateStatus.Failed -> StatusCardFrame(
            badge = R.drawable.ic_settings_m_cloud_off, tone = BadgeTone.Error,
            headline = errorMessage(context, status.error),
            supporting = lastChecked,
            action = {
                FilledTonalButton(onClick = onCheck) {
                    Text(context.getString(R.string.settings_update_retry))
                }
            },
        )

        status is UpdateStatus.UpToDate -> StatusCardFrame(
            badge = R.drawable.ic_settings_m_check_circle, tone = BadgeTone.Calm,
            headline = context.getString(R.string.settings_update_up_to_date),
            supporting = lastChecked,
            action = checkButton,
        )

        // Never checked on this install.
        else -> StatusCardFrame(
            badge = R.drawable.ic_settings_updates, tone = BadgeTone.Calm,
            headline = context.getString(R.string.settings_updates_summary),
            action = checkButton,
        )
    }
}

@Composable
private fun StatusCardFrame(
    badge: Int,
    tone: BadgeTone,
    headline: String,
    supporting: String? = null,
    error: String? = null,
    showProgress: Boolean = false,
    progress: Float? = null,
    notes: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    val colors = MaterialTheme.colorScheme
    val (badgeColor, iconColor) = when (tone) {
        BadgeTone.Calm -> colors.primaryContainer to colors.onPrimaryContainer
        BadgeTone.Attention -> colors.primary to colors.onPrimary
        BadgeTone.Error -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.groupInset),
        shape = LocalShapes.current.group,
        color = LocalSettingsColors.current.card,
    ) {
        Column(
            modifier = Modifier.padding(spacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ImageVector.vectorResource(badge),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                )
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                }
            }
            if (showProgress) {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (!notes.isNullOrBlank()) {
                HorizontalDivider(color = colors.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
                    Text(
                        text = LocalContext.current.getString(R.string.settings_update_notes_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                    )
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (action != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    action()
                }
            }
        }
    }
}

/** "Release", the override's channel name, or "no update channel for debug builds". */
private fun channelSummary(context: Context, checker: UpdateChecker): String = when {
    !checker.hasChannel -> context.getString(R.string.settings_update_channel_none)
    checker.channel == "release" -> context.getString(R.string.settings_update_channel_release)
    // A debug build with pref_distribution_channel_override set: say which.
    else -> checker.channel
}

private fun lastCheckedSummary(context: Context, at: Long): String =
    if (at <= 0L) {
        context.getString(R.string.settings_update_never)
    } else {
        DateUtils.formatDateTime(
            context, at,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or
                DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

/** One sentence per failure; each [DistributionException] carries its values, so no message parsing. */
private fun errorMessage(context: Context, error: Throwable): String = when (error) {
    is OfflineException -> context.getString(R.string.settings_update_error_offline)
    is IntegrityException -> context.getString(R.string.settings_update_error_integrity)
    is HttpStatusException ->
        context.getString(R.string.settings_update_error_http, error.status)
    is UnsupportedSchemaException -> context.getString(R.string.settings_update_error_too_old)
    is DistributionException -> context.getString(R.string.settings_update_error_generic)
    else -> context.getString(R.string.settings_update_error_generic)
}
