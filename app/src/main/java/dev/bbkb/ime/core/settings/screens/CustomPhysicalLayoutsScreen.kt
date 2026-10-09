package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.builder.DeviceProfileExporter
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.keyevent.UserLetterMap
import dev.bbkb.ime.core.keyevent.UserLetterMapRepository
import dev.bbkb.ime.core.settings.backup.LayoutsBundle
import dev.bbkb.ime.core.settings.ui.PreferenceDeleteButton
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Physical keyboard > Custom physical layouts: the letter maps imported through Advanced > Import
 * layouts ([UserLetterMap]), one of which — or none — the physical keys follow.
 *
 * Picking a row writes [UserLetterMapRepository.PREF_ACTIVE_LETTER_MAP]; the typing path reads it
 * on the next key, so there is nothing to restart. Each row says which keyboards the map is bound
 * to and how many keys it changes, and, when it was written for another keypad, that it does
 * nothing on this phone. Share hands the stored file to the share sheet, the way the device
 * profile builder shares a config; delete removes it (and switches it off if it was on).
 *
 * The list is read off the main thread, so on first composition it is briefly empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPhysicalLayoutsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { UserLetterMapRepository(context) }
    val scope = rememberCoroutineScope()
    val deviceKeypad = remember { DeviceProfile.current()?.keypadLayout }

    // null until the first read lands.
    var maps by remember { mutableStateOf<List<UserLetterMap>?>(null) }
    var activeId by remember { mutableStateOf(repository.activeId) }
    var deleteTarget by remember { mutableStateOf<UserLetterMap?>(null) }

    LaunchedEffect(Unit) {
        maps = withContext(Dispatchers.IO) { repository.list() }
    }

    fun select(id: String) {
        repository.activeId = id
        activeId = id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_pkb_letter_maps_title)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // The one thing a map costs: the touch keypad's swipe typing reads the engine's own key
            // geometry, so it is off while a map changes what a letter key types.
            item { PreferenceInfo(text = stringResource(R.string.settings_pkb_letter_maps_swipe_note)) }

            item {
                LetterMapRow(
                    title = stringResource(R.string.settings_pkb_letter_maps_none),
                    summary = stringResource(R.string.settings_pkb_letter_maps_none_summary),
                    selected = activeId.isEmpty() || maps?.none { it.id == activeId } == true,
                    onSelect = { select("") },
                )
            }

            items(maps.orEmpty(), key = { it.id }) { map ->
                LetterMapRow(
                    title = map.name,
                    summary = summaryOf(context, map, deviceKeypad),
                    selected = map.id == activeId,
                    onSelect = { select(map.id) },
                    onShare = { share(context, repository, map) },
                    onDelete = { deleteTarget = map },
                )
            }

            if (maps?.isEmpty() == true) {
                item { PreferenceInfo(text = stringResource(R.string.settings_pkb_letter_maps_empty)) }
            }
        }
    }

    deleteTarget?.let { map ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.settings_pkb_letter_maps_delete_title)) },
            text = { Text(stringResource(R.string.settings_pkb_letter_maps_delete_message, map.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        maps = withContext(Dispatchers.IO) {
                            repository.delete(map.id)
                            repository.list()
                        }
                        activeId = repository.activeId
                    }
                }) {
                    Text(
                        stringResource(R.string.settings_pkb_letter_maps_delete_title),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * One choice, radio in the leading slot as on Device configuration; the whole row is the
 * selectable target. Share and delete sit in the trailing slot for imported maps.
 */
@Composable
private fun LetterMapRow(
    title: String,
    summary: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    PreferenceItem(
        title = title,
        summary = summary,
        leading = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        trailing = if (onShare == null && onDelete == null) null else {
            {
                Row {
                    onShare?.let { share ->
                        IconButton(onClick = share) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.settings_pkb_letter_maps_share, title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    onDelete?.let { delete ->
                        PreferenceDeleteButton(
                            contentDescription = stringResource(R.string.settings_pkb_letter_maps_delete, title),
                            onClick = delete,
                        )
                    }
                }
            }
        },
    )
}

/** "en, de · 4 keys", plus a second line when the map was written for another keypad. */
internal fun summaryOf(context: Context, map: UserLetterMap, deviceKeypad: String?): String {
    val languages = map.locales.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ?: context.getString(R.string.settings_pkb_letter_maps_any_language)
    val details = context.getString(R.string.settings_pkb_letter_maps_details, languages, map.keyCount)
    if (deviceKeypad == null || deviceKeypad.equals(map.keypadLayout, ignoreCase = true)) return details
    return details + "\n" + context.getString(
        R.string.settings_pkb_letter_maps_other_keypad,
        map.keypadLayout.uppercase(),
    )
}

private fun share(context: Context, repository: UserLetterMapRepository, map: UserLetterMap) {
    val file = repository.fileFor(map.id)
    val intent = if (file.isFile) {
        DeviceProfileExporter.shareIntent(
            context,
            file,
            context.getString(R.string.settings_pkb_letter_maps_share_chooser),
            LayoutsBundle.MIME_TYPE,
        )
    } else null
    if (intent == null) {
        Toast.makeText(context, context.getString(R.string.settings_pkb_letter_maps_share_failed), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.settings_pkb_letter_maps_share_failed), Toast.LENGTH_SHORT).show()
    }
}
