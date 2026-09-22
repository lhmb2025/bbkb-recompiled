package dev.bbkb.ime.core.settings.screens

import android.Manifest
import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SwitchPreference
import dev.bbkb.ime.core.update.ApkInstaller
import dev.bbkb.ime.core.update.UpdateChecker
import dev.bbkb.ime.core.update.UpdateJobService
import dev.bbkb.ime.core.update.UpdateNotifier
import dev.bbkb.ime.core.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Updates screen — the whole user-facing side of OTA app updates.
 *
 * Everything here is a decision the user makes. The screen renders the *last recorded* outcome
 * when it opens ([UpdateChecker.lastOutcome] reads preferences, not the network), so there is no
 * spinner on entry; "Check now" is the only thing that fetches, "Download" is the only thing that
 * transfers bytes, and "Install" is the only thing that hands the APK to the platform installer.
 *
 * On a debug build the honest answer is usually "no update channel": the published manifest
 * carries `release` only. That is stated as a fact, not as an error — see [UpdateStatus.NoChannel].
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // Asking for POST_NOTIFICATIONS is only meaningful on API 33+; the launcher is harmless
    // below that and the result is ignored either way — a denied grant costs the notification and
    // nothing else, and the toggle stays on so the check still runs and this screen still shows
    // what it found.
    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* granted or not, the daily check is unaffected */ }

    // Opening this screen is being told: the notification has done its job, and the version it
    // named must not produce a second one.
    LaunchedEffect(Unit) {
        UpdateNotifier.cancel(context)
        (status as? UpdateStatus.Available)?.let { checker.markSeen(it.build.versionCode) }
        // If an APK for the update on record is already downloaded and verified, offer Install
        // rather than Download — the file is kept until a newer one replaces it.
        val available = status as? UpdateStatus.Available ?: return@LaunchedEffect
        val existing = withContext(Dispatchers.IO) { installer.downloadedApk(available.build) }
        if (existing != null) download = DownloadState.Done(existing)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_updates_title)) },
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
            // ── This build ──────────────────────────────────────────────────────
            PreferenceItem(
                title = context.getString(R.string.settings_update_installed_version_title),
                // Same shape as the About screen's version row, and for the same reason: it is
                // the one string on the page that legitimately changes every release.
                summary = "Version ${BuildConfig.VERSION_NAME} " +
                    "(Build ${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
                icon = Icons.Default.Info,
                onClick = null
            )
            PreferenceItem(
                title = context.getString(R.string.settings_update_channel_title),
                summary = channelSummary(context, checker),
                iconSpaceReserved = true,
                onClick = null
            )
            PreferenceItem(
                title = context.getString(R.string.settings_update_last_checked_title),
                summary = lastCheckedSummary(context, checker.lastCheckedAt()),
                iconSpaceReserved = true,
                onClick = null
            )

            // ── Check now ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.contentPadding, vertical = spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.medium)
            ) {
                Button(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val outcome = checker.check(force = true)
                            checking = false
                            status = outcome
                            download = DownloadState.Idle
                            if (outcome is UpdateStatus.Available) {
                                checker.markSeen(outcome.build.versionCode)
                            }
                        }
                    }
                ) {
                    Text(
                        context.getString(
                            if (checking) R.string.settings_update_checking
                            else R.string.settings_update_check_now
                        )
                    )
                }
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(spacing.iconSize))
                }
            }

            // ── The answer ──────────────────────────────────────────────────────
            when (val current = status) {
                null, is UpdateStatus.NoChannel -> Unit // the channel row above already says it

                is UpdateStatus.UpToDate -> {
                    StatusLine(
                        text = context.getString(R.string.settings_update_up_to_date),
                        note = if (current.fromCache) {
                            context.getString(R.string.settings_update_from_cache)
                        } else {
                            null
                        }
                    )
                }

                is UpdateStatus.Available -> UpdateCard(
                    context = context,
                    status = current,
                    download = download,
                    onDownload = {
                        download = DownloadState.Running(0)
                        scope.launch {
                            val result = installer.download(current.build) { bytes, total ->
                                val pct = if (total > 0L) {
                                    ((bytes * 100L) / total).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                                // onProgress arrives on the IO dispatcher; this assignment is the
                                // marshal back, via the Compose snapshot on the launching scope.
                                scope.launch { download = DownloadState.Running(pct) }
                            }
                            download = result.fold(
                                onSuccess = { DownloadState.Done(it) },
                                onFailure = { DownloadState.Failed(it) }
                            )
                        }
                    },
                    onInstall = {
                        val apk = (download as? DownloadState.Done)?.file ?: return@UpdateCard
                        if (!installer.canRequestInstalls()) {
                            explainUnknownSources = true
                        } else if (!installer.startInstall(apk)) {
                            // The file went away (cache eviction) — offer the download again.
                            download = DownloadState.Idle
                        }
                    }
                )

                is UpdateStatus.Failed -> {
                    StatusLine(
                        text = errorMessage(context, current.error),
                        note = null,
                        isError = true
                    )
                    Row(
                        modifier = Modifier.padding(
                            horizontal = spacing.contentPadding,
                            vertical = spacing.small
                        )
                    ) {
                        OutlinedButton(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                scope.launch {
                                    val outcome = checker.check(force = true)
                                    checking = false
                                    status = outcome
                                }
                            }
                        ) { Text(context.getString(R.string.settings_update_retry)) }
                    }
                    // A failed refresh does not hide what the last good check found.
                    current.lastKnown?.let { known ->
                        UpdateCard(
                            context = context,
                            status = known,
                            download = download,
                            onDownload = {
                                download = DownloadState.Running(0)
                                scope.launch {
                                    val result = installer.download(known.build)
                                    download = result.fold(
                                        onSuccess = { DownloadState.Done(it) },
                                        onFailure = { DownloadState.Failed(it) }
                                    )
                                }
                            },
                            onInstall = {
                                val apk = (download as? DownloadState.Done)?.file
                                    ?: return@UpdateCard
                                if (!installer.canRequestInstalls()) {
                                    explainUnknownSources = true
                                } else if (!installer.startInstall(apk)) {
                                    download = DownloadState.Idle
                                }
                            }
                        )
                    }
                }
            }

            // ── Background check ────────────────────────────────────────────────
            PreferenceCategory(title = context.getString(R.string.settings_updates_title))
            SwitchPreference(
                title = context.getString(R.string.settings_update_background_check_title),
                summary = context.getString(R.string.settings_update_background_check_summary),
                icon = Icons.Default.Notifications,
                checked = backgroundCheck,
                modifier = Modifier.settingsSearchAnchor(UpdateJobService.PREF_BACKGROUND_CHECK),
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

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }

    if (explainUnknownSources) {
        AlertDialog(
            onDismissRequest = { explainUnknownSources = false },
            title = { Text(context.getString(R.string.settings_update_install)) },
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

/** Where a download has got to. Deliberately not persisted: a download is one sitting's work. */
private sealed class DownloadState {
    object Idle : DownloadState()
    data class Running(val percent: Int) : DownloadState()
    data class Done(val file: File) : DownloadState()
    data class Failed(val error: Throwable) : DownloadState()
}

/**
 * The "update available" card: what it is, how big it is, what changed, and the one button that
 * is live at this point in the download → install sequence.
 */
@Composable
private fun UpdateCard(
    context: Context,
    status: UpdateStatus.Available,
    download: DownloadState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.contentPadding, vertical = spacing.small),
        shape = RoundedCornerShape(spacing.medium),
        // surfaceVariant at half strength, as elsewhere in settings: the theme defines no
        // surfaceContainer tokens.
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = spacing.iconTextGap)
                        .size(spacing.iconSize)
                )
                Text(
                    text = context.getString(R.string.settings_update_available_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(spacing.small))
            Text(
                text = context.getString(
                    R.string.settings_update_version_label, status.build.versionName
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            if (status.build.size > 0L) {
                Text(
                    text = context.getString(
                        R.string.settings_update_size_label,
                        Formatter.formatShortFileSize(context, status.build.size)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val notes = status.build.notes
            if (!notes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(spacing.medium))
                Text(
                    text = context.getString(R.string.settings_update_notes_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(spacing.medium))

            when (download) {
                is DownloadState.Idle -> Button(onClick = onDownload) {
                    Text(context.getString(R.string.settings_update_download))
                }

                is DownloadState.Running -> {
                    Text(
                        text = context.getString(
                            R.string.settings_update_downloading, download.percent
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(spacing.small))
                    // Determinate: the manifest states the size, so the bar means something.
                    LinearProgressIndicator(
                        progress = { download.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is DownloadState.Done -> {
                    Text(
                        text = context.getString(R.string.settings_update_downloaded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(spacing.small))
                    Button(onClick = onInstall) {
                        Text(context.getString(R.string.settings_update_install))
                    }
                }

                is DownloadState.Failed -> {
                    Text(
                        text = errorMessage(context, download.error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(spacing.small))
                    OutlinedButton(onClick = onDownload) {
                        Text(context.getString(R.string.settings_update_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, note: String?, isError: Boolean = false) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.padding(
            horizontal = spacing.contentPadding,
            vertical = spacing.small
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "Release", the override's channel name, or "no update channel for debug builds". */
private fun channelSummary(context: Context, checker: UpdateChecker): String = when {
    !checker.hasChannel -> context.getString(R.string.settings_update_channel_none)
    checker.channel == "release" -> context.getString(R.string.settings_update_channel_release)
    // A debug build with pref_distribution_channel_override set to something else: say which.
    else -> checker.channel
}

private fun lastCheckedSummary(context: Context, at: Long): String =
    if (at <= 0L) {
        context.getString(R.string.settings_update_never)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
    }

/**
 * One sentence per failure the foundation can report. Each [DistributionException] carries the
 * values its message needs, so nothing here parses a message string.
 */
private fun errorMessage(context: Context, error: Throwable): String = when (error) {
    is OfflineException -> context.getString(R.string.settings_update_error_offline)
    is IntegrityException -> context.getString(R.string.settings_update_error_integrity)
    is HttpStatusException ->
        context.getString(R.string.settings_update_error_http, error.status)
    is UnsupportedSchemaException -> context.getString(R.string.settings_update_error_too_old)
    is DistributionException -> context.getString(R.string.settings_update_error_generic)
    else -> context.getString(R.string.settings_update_error_generic)
}
