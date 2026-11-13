package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceLanguages
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.RadioButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bbkb.ime.R
import dev.bbkb.ime.core.distribution.DistributionManifest
import dev.bbkb.ime.core.distribution.ManifestSource
import dev.bbkb.ime.core.languagepack.InstalledPacks
import dev.bbkb.ime.core.locale.SubtypeEnabler
import dev.bbkb.ime.core.languagepack.PackCatalog
import dev.bbkb.ime.core.languagepack.PackDownloadManager
import dev.bbkb.ime.core.languagepack.PackInstallService
import dev.bbkb.ime.core.languagepack.PackListing
import dev.bbkb.ime.core.languagepack.UninstallOutcome
import dev.bbkb.ime.core.languagepack.PackState
import dev.bbkb.ime.core.languagepack.PackText
import dev.bbkb.ime.core.shared.InAppEventBus
import com.blackberry.nuanceshim.languagepack.CustomPackRegistryStore
import com.blackberry.nuanceshim.languagepack.LanguageVariantStore
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import dev.bbkb.ime.core.settings.ui.LocalInGroup
import dev.bbkb.ime.core.settings.ui.GroupEdge
import dev.bbkb.ime.core.settings.ui.groupEdge
import dev.bbkb.ime.core.settings.ui.LocalShapes
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceDeleteButton
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceLeadingBadge
import dev.bbkb.ime.core.settings.ui.PreferenceTrailingLabel
import dev.bbkb.ime.core.settings.ui.SearchableSettingsScaffold
import dev.bbkb.ime.core.settings.ui.rememberListSearchState
import dev.bbkb.ime.core.settings.ui.TitleWithAccessory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import androidx.compose.runtime.Immutable

/**
 * Language packs: installed (shipped, side-loaded, regional variants) and available to download
 * ([PackCatalog] minus what is installed).
 *
 * Installed state is re-read from disk ([InstalledPacks.read]) after every download, never inferred.
 * [PackDownloadManager] is process-wide, so leaving the screen mid-download changes nothing.
 * Both lists are flat and ordered by [PackListing]; a regional dictionary is an ordinary row.
 */
@Composable
fun LanguagePacksScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var languagePacks by remember { mutableStateOf<List<InstalledLanguagePack>>(emptyList()) }
    // Languages the speech recogniser has offline (the Voice chip). None while voice input is off.
    val voiceInputOn = remember { PrefsManager.getPrefs(context).getBoolean("voice_input_enabled", true) }
    var voiceLanguages by remember {
        mutableStateOf(if (voiceInputOn) VoiceLanguages.snapshot(context) else null)
    }
    LaunchedEffect(Unit) {
        if (voiceInputOn) VoiceLanguages.refresh(context) { voiceLanguages = it }
    }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf<InstalledLanguagePack?>(null) }
    // Variant deletes get their own dialog: the message must name the group member.
    var showVariantDeleteDialog by remember {
        mutableStateOf<Pair<String, LanguageVariantStore.Member>?>(null)
    }
    var variantGroups by remember {
        mutableStateOf<Map<String, LanguageVariantStore.Group>>(emptyMap())
    }

    // Manifest, disk state and download queue are separate inputs; PackCatalog merges them purely.
    val downloads = remember { PackDownloadManager.getInstance(context) }
    val downloadStates by downloads.states.collectAsStateWithLifecycle()
    var manifest by remember { mutableStateOf<DistributionManifest?>(null) }
    var installedPacks by remember { mutableStateOf(InstalledPacks()) }
    var catalogError by remember { mutableStateOf<Throwable?>(null) }
    var catalogLoading by remember { mutableStateOf(true) }
    // 0 is the first, cache-friendly load; above it the user asked to go to the server.
    var refreshCount by remember { mutableIntStateOf(0) }

    val catalog = remember(manifest, installedPacks, downloadStates) {
        manifest?.let { PackCatalog.from(it, installedPacks, downloadStates) }
    }
    val availablePacks = remember(catalog) {
        catalog?.let { PackListing.available(it.rows) }.orEmpty()
    }

    LaunchedEffect(refreshCount) {
        catalogLoading = true
        val loaded = loadCatalogue(context, force = refreshCount > 0)
        // Keep the catalogue we had on a failed refresh.
        manifest = loaded.getOrNull() ?: manifest
        catalogError = if (manifest == null) loaded.exceptionOrNull() else null
        installedPacks = readInstalledPacks(context, manifest)
        catalogLoading = false
    }

    // A finished download wrote to disk, so re-read both halves. Keyed on finished entries only:
    // keying on every state restarted the re-read on each progress tick.
    val finishedDownloads = remember(downloadStates) {
        downloadStates.filterValues { it is PackState.Installed || it is PackState.Failed }
    }
    LaunchedEffect(finishedDownloads) {
        installedPacks = readInstalledPacks(context, manifest)
        loadLanguagePacks(context) { packs -> languagePacks = packs }
        variantGroups = withContext(Dispatchers.IO) { LanguageVariantStore.read(context) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                installLanguagePack(context, it) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (success) {
                        scope.launch {
                            loadLanguagePacks(context) { packs ->
                                languagePacks = packs
                            }
                            variantGroups = LanguageVariantStore.read(context)
                            // Or the side-loaded pack would still show under "Available to download".
                            installedPacks = readInstalledPacks(context, manifest)
                        }
                    }
                }
            }
        }
    }

    // A language may have been turned on or off elsewhere (e.g. the system language list).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { loadLanguagePacks(context) { packs -> languagePacks = packs } }
    }

    LaunchedEffect(Unit) {
        scope.launch {
            variantGroups = LanguageVariantStore.read(context)
            loadLanguagePacks(context) { packs ->
                languagePacks = packs
                isLoading = false
            }
        }
    }

    // Search filters both halves; the headers stay so a result still shows which half it is in.
    val search = rememberListSearchState()
    val shownInstalled = languagePacks.filter { search.matches(it.displayName, it.languageCode) }
    val shownAvailable = availablePacks.filter { search.matches(it.item.displayName, it.locale) }

    SearchableSettingsScaffold(
        title = context.getString(R.string.settings_language_packs_title),
        onNavigateBack = onNavigateBack,
        search = search,
        searchHint = stringResource(R.string.languages_hub_search),
        actions = {
            // Disabled while a fetch is in flight so a double tap does not queue a second request.
            IconButton(
                onClick = { refreshCount++ },
                enabled = !catalogLoading,
            ) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_settings_m_refresh),
                    contentDescription = stringResource(R.string.language_packs_refresh),
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePickerLauncher.launch("*/*") }, // any file; validated as LDB after
                icon = { Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_add), contentDescription = null) },
                text = { Text(stringResource(R.string.language_packs_load_ldb)) }
            )
        }
    ) { paddingValues ->
        val spacing = LocalSpacing.current
        // Both section headers are always present, even while loading, so the screen never re-flows.
        // Each half is one group; its first and last rows take the group corners (groupEdge).
        CompositionLocalProvider(LocalInGroup provides true) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                // Bottom inset keeps the last row clear of the extended FAB (56dp + 16dp margin + 16dp).
                contentPadding = PaddingValues(
                    start = spacing.groupInset,
                    end = spacing.groupInset,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
            ) {
                item(key = "installed-header") {
                    PreferenceCategory(
                        stringResource(R.string.language_packs_installed_header),
                        modifier = Modifier.outsideGroupInset(spacing.groupInset),
                    )
                }
                if (isLoading) {
                    item(key = "installed-loading") { PackLoadingRow() }
                }
                if (search.filtering && !isLoading && shownInstalled.isEmpty()) {
                    item(key = "installed-none") {
                        PackMessageRow(message = stringResource(R.string.settings_common_no_matches))
                    }
                }
                itemsIndexed(
                    shownInstalled,
                    key = { _, row -> "installed-" + row.languageCode + row.countryCode }
                ) { index, pack ->
                    LanguagePackItem(
                        pack = pack,
                        voiceOffline = voiceLanguages?.isDownloaded(pack.localeId) == true,
                        edge = groupEdge(index, shownInstalled.size),
                        variantGroup = variantGroups[pack.languageCode],
                        onSelectVariant = { tag ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    LanguageVariantStore.activate(context, pack.languageCode, tag)
                                }
                                InAppEventBus.getInstance()
                                    .post(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED, null)
                                variantGroups = LanguageVariantStore.read(context)
                                installedPacks = readInstalledPacks(context, manifest)
                                // The swap moved files in and out of the base slot, so paths and "preinstalled" are stale.
                                loadLanguagePacks(context) { packs -> languagePacks = packs }
                            }
                        },
                        onDeleteVariant = { member ->
                            showVariantDeleteDialog = pack.languageCode to member
                        },
                        onTurnOn = {
                            scope.launch {
                                val on = withContext(Dispatchers.IO) {
                                    SubtypeEnabler.enable(context, pack.localeId)
                                }
                                if (on) {
                                    VoiceLanguages.downloadForKeyboard(context, pack.localeId)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.language_packs_turned_on, pack.displayName),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    loadLanguagePacks(context) { packs -> languagePacks = packs }
                                } else {
                                    // Before Android 14 only the user can turn it on; ON_RESUME re-reads the list.
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.language_packs_turn_on_in_settings, pack.displayName),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    SubtypeEnabler.openLanguageSettings(context)
                                }
                            }
                        },
                        onDelete = if (pack.isDeletable) {
                            { showDeleteDialog = pack }
                        } else null
                    )
                }

                item(key = "available-header") {
                    PreferenceCategory(
                        stringResource(R.string.language_packs_available_header),
                        modifier = Modifier.outsideGroupInset(spacing.groupInset),
                    )
                }
                if (catalogLoading && catalog == null) {
                    item(key = "available-loading") { PackLoadingRow() }
                }
                catalogError?.takeIf { catalog == null }?.let { error ->
                    item(key = "available-error") {
                        PackMessageRow(
                            message = PackText.errorMessage(context, error),
                            actionLabel = stringResource(R.string.language_packs_retry),
                            onAction = { refreshCount++ },
                        )
                    }
                }
                if (catalog != null) {
                    val packs = manifest?.packs
                    if (search.filtering && shownAvailable.isEmpty()) {
                        item(key = "available-none") {
                            PackMessageRow(message = stringResource(R.string.settings_common_no_matches))
                        }
                    }
                    itemsIndexed(shownAvailable, key = { _, row -> "available-" + row.locale }) { index, available ->
                        PackActionRow(
                            item = available.item,
                            edge = groupEdge(index, shownAvailable.size),
                            // Installing one of these replaces the dictionary the language loads today, so say so.
                            summary = available.replaces?.let {
                                context.getString(R.string.language_packs_variant_summary, it)
                            },
                            onDownload = { item ->
                                packs?.let { downloads.download(it, item.entry) }
                            },
                            onCancel = { locale -> downloads.cancel(locale) },
                            onRetry = { item ->
                                downloads.clearFailure(item.locale)
                                packs?.let { downloads.download(it, item.entry) }
                            },
                        )
                    }
                }
            }
        }
    }

    showVariantDeleteDialog?.let { (locale, member) ->
        AlertDialog(
            onDismissRequest = { showVariantDeleteDialog = null },
            title = { Text(stringResource(R.string.language_packs_delete_dictionary_title)) },
            text = {
                Text(stringResource(R.string.language_packs_delete_dictionary_message, member.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LanguageVariantStore.removeVariant(context, locale, member.tag)
                            }
                            InAppEventBus.getInstance()
                                .post(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED, null)
                            variantGroups = LanguageVariantStore.read(context)
                            loadLanguagePacks(context) { packs -> languagePacks = packs }
                            installedPacks = readInstalledPacks(context, manifest)
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ok) R.string.language_packs_dictionary_removed
                                    else R.string.language_packs_dictionary_remove_failed,
                                    member.name,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        showVariantDeleteDialog = null
                    }
                ) {
                    Text(stringResource(R.string.clipboard_menu_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showVariantDeleteDialog = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    showDeleteDialog?.let { pack ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.language_packs_delete_pack_title)) },
            text = { 
                Text(stringResource(R.string.language_packs_delete_pack_message, pack.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Remove the row at once so it cannot invite a second delete.
                        languagePacks = languagePacks.filterNot {
                            it.languageCode == pack.languageCode && it.countryCode == pack.countryCode
                        }
                        scope.launch {
                            deleteLanguagePack(context, pack) { _, message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                // Re-read either way: on success the pack is downloadable again, on failure the row returns.
                                scope.launch {
                                    loadLanguagePacks(context) { packs ->
                                        languagePacks = packs
                                    }
                                    installedPacks = readInstalledPacks(context, manifest)
                                }
                            }
                        }
                        showDeleteDialog = null
                    }
                ) {
                    Text(stringResource(R.string.clipboard_menu_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * One installed language on [PreferenceItem]: the language code in the icon column, and
 * "Preinstalled" or a delete button at the right edge.
 */
@Composable
private fun LanguagePackItem(
    pack: InstalledLanguagePack,
    /** The speech recogniser has this language on the phone: the Voice chip after the name. */
    voiceOffline: Boolean = false,
    /** The plain row's place in its group; a language with regional dictionaries keeps its card. */
    edge: GroupEdge = GroupEdge.Middle,
    variantGroup: LanguageVariantStore.Group? = null,
    onSelectVariant: (String) -> Unit = {},
    onDeleteVariant: (LanguageVariantStore.Member) -> Unit = {},
    onTurnOn: () -> Unit = {},
    onDelete: (() -> Unit)? = null
) {
    val spacing = LocalSpacing.current
    val grouped = variantGroup != null && variantGroup.members.size > 1

    if (!grouped) {
        PreferenceItem(
            title = pack.displayName,
            // An installed language the user cannot switch to reads as broken, so say so; tapping fixes it.
            summary = if (pack.needsTurningOn) {
                stringResource(R.string.language_packs_not_on_summary, pack.displayName)
            } else null,
            onClick = if (pack.needsTurningOn) onTurnOn else null,
            groupEdge = edge,
            leading = { LanguageCodeBadge(pack.languageCode) },
            titleAccessory = if (voiceOffline) {
                { OfflineVoiceChip() }
            } else null,
            trailing = when {
                onDelete != null -> {
                    { PreferenceDeleteButton(contentDescription = stringResource(R.string.settings_pkb_layouts_delete, pack.displayName), onClick = onDelete) }
                }
                pack.isPreinstalled -> {
                    { PreinstalledTag() }
                }
                else -> null
            }
        )
        return
    }

    // A language with regional dictionaries sits on a rounded surface. Inner paddings shrink by
    // [inset] so the code column, title and trailing controls line up with the plain rows.
    val inset = spacing.none
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = inset, vertical = spacing.extraSmall)
            .clip(LocalShapes.current.group)
            // The theme defines no surfaceContainer tokens; Material's defaults would bring in purple.
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(bottom = spacing.small)
    ) {
        // PreferenceItem's metrics without bottom padding. No "Preinstalled": the shipped member row says it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = spacing.listItemPadding - inset,
                    end = spacing.listItemPadding - inset,
                    top = spacing.listItemVerticalPadding - spacing.extraSmall
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(end = spacing.iconTextGap)
                    .size(spacing.iconSize),
                contentAlignment = Alignment.Center
            ) {
                LanguageCodeBadge(pack.languageCode)
            }
            TitleWithAccessory(
                accessory = if (voiceOffline) {
                    { OfflineVoiceChip() }
                } else null,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = pack.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            onDelete?.let { delete ->
                PreferenceDeleteButton(contentDescription = stringResource(R.string.settings_pkb_layouts_delete, pack.displayName), onClick = delete)
            }
        }

        // Variants that can only load in this language's slot (e.g. Swiss German under German).
        // The engine holds one dictionary per locale, so this is a single choice.
        variantGroup!!.members.forEach { member ->
            val selected = member.tag == variantGroup.activeTag
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Exactly the 48dp touch target, so the 48dp delete button does not make the row taller.
                    .heightIn(min = spacing.touchTarget)
                    .selectable(
                        selected = selected,
                        onClick = { onSelectVariant(member.tag) },
                        role = Role.RadioButton
                    )
                    .padding(
                        start = spacing.listItemPadding + spacing.iconSize + spacing.iconTextGap - inset,
                        end = spacing.listItemPadding - inset
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(modifier = Modifier.width(spacing.medium))
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                // Shipped dictionaries cannot be removed and get the label; user-added ones get delete.
                if (member.isShipped) {
                    PreinstalledTag()
                } else {
                    PreferenceDeleteButton(
                        contentDescription = stringResource(R.string.settings_pkb_layouts_delete, member.name),
                        onClick = { onDeleteVariant(member) }
                    )
                }
            }
        }
    }
}

/** Lays an item out [inset] wider on each side, undoing the list's group inset for a category label. */
private fun Modifier.outsideGroupInset(inset: Dp): Modifier = layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    val wider = if (constraints.hasBoundedWidth) {
        constraints.copy(maxWidth = constraints.maxWidth + extra)
    } else {
        constraints
    }
    val placeable = measurable.measure(wider)
    val width = (placeable.width - extra).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.placeRelative(-extra / 2, 0) }
}

/** A row-height spinner, for a section whose contents are still being read off disk or network. */
@Composable
private fun PackLoadingRow() {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .padding(spacing.medium),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(spacing.iconSize))
    }
}

/** Why the catalogue is missing, with the one action that might change that. */
@Composable
private fun PackMessageRow(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.listItemPadding, vertical = spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * One downloadable pack: name, size or progress, and the single action its state allows
 * (Download, Cancel or Try again), never two at once.
 */
@Composable
private fun PackActionRow(
    item: PackCatalog.Item,
    edge: GroupEdge,
    summary: String?,
    onDownload: (PackCatalog.Item) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (PackCatalog.Item) -> Unit,
) {
    val context = LocalContext.current
    val state = item.state

    val sizeLine = PackText.size(context, item.sizeBytes)
    val summaryLine = when (state) {
        is PackState.Failed -> PackText.errorMessage(context, state.error)
        else -> listOfNotNull(summary, sizeLine).joinToString(" • ")
    }

    PreferenceItem(
        title = item.displayName,
        summary = summaryLine,
        groupEdge = edge,
        leading = { LanguageCodeBadge(item.locale) },
        trailing = {
            when (state) {
                is PackState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    // The denominator is the catalogue's size, not the server's Content-Length.
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.width(64.dp),
                    )
                    IconButton(onClick = { onCancel(item.locale) }) {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_settings_m_close),
                            contentDescription = context.getString(R.string.cancel),
                        )
                    }
                }

                is PackState.Failed -> TextButton(onClick = { onRetry(item) }) {
                    Text(stringResource(R.string.language_packs_retry))
                }

                else -> TextButton(onClick = { onDownload(item) }) {
                    Text(stringResource(R.string.language_packs_download))
                }
            }
        },
    )
}

/**
 * The language code in the row's icon column. Upper-cased locale-independently so a Turkish
 * device does not render "tr" with a dotted I.
 */
@Composable
private fun LanguageCodeBadge(code: String) {
    PreferenceLeadingBadge(code.uppercase(Locale.ROOT))
}

/**
 * "Voice" with the offline mark: the speech recogniser has this language downloaded. Shorter than
 * the title's line, so a row with the chip is the same height as one without.
 */
@Composable
internal fun OfflineVoiceChip() {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.language_packs_voice_offline)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.secondaryContainer)
            .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            ImageVector.vectorResource(R.drawable.ic_settings_m_offline_pin),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = colors.onSecondaryContainer,
        )
        Text(
            text = stringResource(R.string.language_packs_voice_chip),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSecondaryContainer,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun PreinstalledTag() = PreferenceTrailingLabel(stringResource(R.string.language_packs_preinstalled))

/** One installed language pack as this screen displays it (not the shim's LanguagePackInfo). */
@Immutable
data class InstalledLanguagePack(
    val languageCode: String,
    val countryCode: String = "",
    val displayName: String,
    val version: String,
    val filePath: String,
    val isPreinstalled: Boolean,
    val isDeletable: Boolean = !isPreinstalled,
    /**
     * Directory / registry identifier (`ko`, `pt_BR`, `iw`). Not rebuilt from [languageCode]: from
     * Android 15 [Locale] spells `iw` and `in` as `he` and `id`, a directory that does not exist.
     */
    val localeId: String,
    /**
     * Installed with a layout but not on in the keyboard's language list. Only true for user-added
     * packs: the shipped ones are languages the user may simply not want.
     */
    val needsTurningOn: Boolean = false,
)

private suspend fun loadLanguagePacks(
    context: Context,
    onResult: (List<InstalledLanguagePack>) -> Unit
) {
    withContext(Dispatchers.IO) {
        val packs = mutableListOf<InstalledLanguagePack>()
        
        try {
            val languagePackManager = LanguagePackManager.getInstance(context)

            val installedLocales = getInstalledLanguagePackLocales(context)
            
            for (localeString in installedLocales) {
                try {
                    val locale = if (localeString.contains("_")) {
                        val parts = localeString.split("_")
                        Locale(parts[0], parts[1])
                    } else {
                        Locale(localeString)
                    }
                    
                    val languagePack = languagePackManager.getStatus(locale)
                    if (languagePack != null) {
                        val isInstalled = languagePack.isInstalled()
                        val isSupported = languagePack.isSupported()
                        
                        if (isInstalled || isSupported) {
                            val isPreinstalled = isPreinstalledPack(context, localeString)
                            val needsTurningOn = !isPreinstalled &&
                                SubtypeEnabler.hasKeyboardFor(context, localeString) &&
                                !SubtypeEnabler.isEnabled(context, localeString)

                            packs.add(
                                InstalledLanguagePack(
                                    languageCode = locale.language,
                                    countryCode = locale.country,
                                    displayName = locale.displayName,
                                    version = if (isInstalled) getLanguagePackVersion(context, localeString) else context.getString(R.string.language_packs_not_installed),
                                    filePath = getLanguagePackPath(context, localeString),
                                    isPreinstalled = isPreinstalled,
                                    localeId = localeString,
                                    needsTurningOn = needsTurningOn,
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            
            
        } catch (e: Exception) {
            // A failed scan shows whatever packs were collected so far.
        }

        val sorted = PackListing.byDisplayName(packs) { it.displayName }

        withContext(Dispatchers.Main) {
            onResult(sorted)
        }
    }
}

private fun isPreinstalledPack(context: Context, localeString: String): Boolean {
    return try {
        val assetManager = context.assets
        val ldbFiles = assetManager.list("ldb") ?: emptyArray()
        ldbFiles.any { it.contains(localeString, ignoreCase = true) && it.endsWith(".ldb") }
    } catch (e: Exception) {
        false
    }
}

private fun getLanguagePackPath(context: Context, localeString: String): String {
    val customPath = File(context.noBackupFilesDir, "nuance/$localeString")
    return if (customPath.exists()) {
        customPath.absolutePath
    } else {
        "assets/ldb/"
    }
}

/**
 * Installs a dictionary picked with the "+" button. The locale is parsed from the file name and
 * checked against the engine registry (own locale, or only a variant of its base language).
 * The install itself is [PackInstallService]; downloads use the manifest's locale instead.
 */
private suspend fun installLanguagePack(
    context: Context,
    uri: Uri,
    onResult: (Boolean, String) -> Unit
) {
    withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_language_pack.ldb")
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw Exception(context.getString(R.string.language_packs_cannot_open_file))

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (tempFile.length() < PackInstallService.MIN_LDB_BYTES) {
                throw Exception(context.getString(R.string.language_packs_file_too_small))
            }

            val fileName = getFileName(context, uri) ?: "unknown.ldb"
            if (!fileName.endsWith(".ldb", ignoreCase = true)) {
                throw Exception(context.getString(R.string.language_packs_file_needs_ldb))
            }

            // Keep the country too: an en_US pack must land in nuance/en_US, not the language-only nuance/en.
            // LanguagePackInstaller.getLocaleIdentifier is the naming authority.
            val languageCode = extractLanguageFromFileName(fileName)
            if (languageCode.isEmpty()) {
                throw Exception(context.getString(R.string.language_packs_language_unknown))
            }
            val countryCode = extractCountryFromFileName(fileName, languageCode)
            val localeId = if (countryCode != null) "${languageCode}_$countryCode" else languageCode
            val locale = locale(languageCode, countryCode)

            // LanguagePackRegistry falls back to the base language's entry for an unknown country, so the
            // resolved identifier is the slot this dictionary can occupy (de_CH -> "de", en_US -> "en_US").
            // A variant with no entry of its own loads as `slot`, grouped under the base language.
            val slot = resolvedLocaleSlot(context, languageCode, countryCode)
            val group = slot?.takeIf { countryCode != null && it != localeId }

            val installed = PackInstallService(context).installFromFile(
                file = tempFile,
                locale = localeId,
                displayName = locale.displayName.ifEmpty { localeId },
                version = SIDELOADED_PACK_VERSION.toString(),
                group = group,
            ).getOrElse { failure -> throw failure }

            val message = when {
                installed.isVariant ->
                    context.getString(R.string.language_packs_installed_variant, locale(group!!, null).displayLanguage)
                installed.selectable && installed.enabled ->
                    context.getString(R.string.language_packs_installed_and_on, locale.displayName)
                installed.selectable ->
                    context.getString(R.string.language_packs_installed_turn_on, locale.displayName)
                else ->
                    context.getString(R.string.language_packs_installed_no_layout, locale.displayName)
            }
            withContext(Dispatchers.Main) {
                onResult(true, message)
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult(false, context.getString(R.string.language_packs_install_failed, e.message))
            }
        } finally {
            // The service copies what it installs, so the staging file is ours to remove.
            tempFile.delete()
        }
    }
}

/**
 * Deletes a downloaded or side-loaded pack via the idempotent [PackInstallService.uninstall].
 * Variants delete through [LanguageVariantStore] instead.
 */
private suspend fun deleteLanguagePack(
    context: Context,
    pack: InstalledLanguagePack,
    onResult: (Boolean, String) -> Unit
) {
    if (pack.isPreinstalled) {
        onResult(false, context.getString(R.string.language_packs_cannot_delete_preinstalled))
        return
    }
    val outcome = PackInstallService(context).uninstall(pack.localeId)
    withContext(Dispatchers.Main) {
        outcome.fold(
            onSuccess = { result ->
                onResult(
                    true,
                    when (result) {
                        UninstallOutcome.REMOVED -> context.getString(R.string.language_packs_deleted, pack.displayName)
                        UninstallOutcome.ALREADY_GONE -> context.getString(R.string.language_packs_already_removed, pack.displayName)
                    },
                )
            },
            onFailure = { failure ->
                onResult(false, context.getString(R.string.language_packs_delete_failed, failure.message))
            },
        )
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * The upper-case language block that opens the locale group (`EN` in `_ENubUNUS_`, `SAT` in
 * `_SATlsUNdevanagari_`). Matches all 116 shipped catalogue names: 111 two-letter, 5 three-letter.
 */
private val LANGUAGE_BLOCK = Regex("_([A-Z]{2,3})[a-z]{2}UN")

/**
 * Three-letter codes (`brx`/`doi`/`mai`/`mni`/`sat`) missing from the engine's 105-entry locale
 * table (`libnative-lib.so:0x217c50`, mirrored by `assets/ldb/manifest.json`). Rejected explicitly:
 * truncated they become real languages (`mni` -> `mn`) and would overwrite those packs.
 */
private fun hasUnsupportedThreeLetterCode(fileName: String): Boolean =
    LANGUAGE_BLOCK.find(fileName)?.groupValues?.get(1)?.length == 3

/**
 * File-name spellings (retired or less specific ISO codes) mapped to the spelling the engine,
 * `assets/ldb/manifest.json` and `res/xml/method.xml` use.
 */
private val LANGUAGE_CODE_ALIASES = mapOf(
    "jw" to "jv",    // Javanese: `jw` retired 1989
    "he" to "iw",    // Hebrew: the engine table, the catalogs and method.xml all say iw
    "id" to "in",    // Indonesian: likewise in (LocaleUtils.languageCode is the Locale-side bridge)
    "tl" to "fil",   // Tagalog -> Filipino, which is what the engine and method.xml carry
    "no" to "nb",    // Norwegian -> Bokmal
)

internal fun extractLanguageFromFileName(fileName: String): String {
    // Patterns run most to least specific, each validated by isValidLanguageCode.
    // Reject a three-letter code before the two-letter patterns can truncate it.
    if (hasUnsupportedThreeLetterCode(fileName)) return ""

    val name = fileName.lowercase()

    // Pattern 1: *_ENusUN_* -> en (most common BlackBerry format)
    Regex("_([a-z]{2})[a-z]*_").find(name)?.let {
        val code = it.groupValues[1]
        if (isValidLanguageCode(code)) return LANGUAGE_CODE_ALIASES[code] ?: code
    }

    // Pattern 2: *_EN_* -> en (simpler format)
    Regex("_([a-z]{2})_").find(name)?.let {
        val code = it.groupValues[1]
        if (isValidLanguageCode(code)) return LANGUAGE_CODE_ALIASES[code] ?: code
    }

    // Pattern 3: en.ldb -> en (simple format)
    Regex("^([a-z]{2})\\.ldb$").find(name)?.let {
        val code = it.groupValues[1]
        if (isValidLanguageCode(code)) return LANGUAGE_CODE_ALIASES[code] ?: code
    }

    // Pattern 4: fallback - any 2-letter language code after an underscore
    Regex("_([a-z]{2})[^a-z]").find(name)?.let {
        val code = it.groupValues[1]
        if (isValidLanguageCode(code)) return LANGUAGE_CODE_ALIASES[code] ?: code
    }

    return ""
}


/**
 * The catalogue from network or cache ([ManifestSource] owns the policy). When a forced fetch
 * fails, falls back to the cache: this screen would rather show a labelled stale list than an error.
 */
private suspend fun loadCatalogue(context: Context, force: Boolean): Result<DistributionManifest> {
    val source = ManifestSource(context)
    val fetched = source.fetch(force)
    if (fetched.isSuccess) return fetched
    val cached = withContext(Dispatchers.IO) { source.cached() }
    return if (cached != null) Result.success(cached) else fetched
}

/** On-disk state for the locales the catalogue offers; empty with no catalogue. */
private suspend fun readInstalledPacks(
    context: Context,
    manifest: DistributionManifest?,
): InstalledPacks = withContext(Dispatchers.IO) {
    val locales = manifest?.packs?.items?.map { it.locale } ?: return@withContext InstalledPacks()
    InstalledPacks.read(context, locales)
}

/**
 * The locale slot a pack for [language]/[country] would occupy: `"en_US"` when it has its own
 * engine entry, `"de"` for `de_CH`, null when the registry does not know the language.
 */
private fun resolvedLocaleSlot(context: Context, language: String, country: String?): String? {
    val status = LanguagePackManager.getInstance(context)
        .getStatus(locale(language, country)) ?: return null
    return if (status.isSupported) status.localeIdentifier else null
}

private fun locale(language: String, country: String?): Locale =
    if (country.isNullOrEmpty()) Locale(language) else Locale(language, country)

/**
 * Version written to a side-loaded pack's `version.txt`. The real version is unknowable and never
 * compared; it must exist (`getInstalledLocales()`) and be positive (`LanguagePackInfo.isValid`).
 */
private const val SIDELOADED_PACK_VERSION = 1.0

/**
 * The country half of a BlackBerry LDB file name, or null. Names follow `<LANG><codec><UN>[<REGION>]`:
 *
 * ```
 *   _ENubUN_      en          (UN is the shared vendor marker, not a region)
 *   _ENubUNUS_    en-US
 *   _ENubUNUK_    en-UK
 *   _ESusUNES_    es-ES
 *   _ZHsbUNps_    zh          (lower-case "ps" is codec noise, not a region)
 *   _UKlsUN_      uk          (Ukrainian: the language code itself looks like a region)
 * ```
 *
 * The region is the upper-case run after `UN`, so this matches on original case. es-419 and
 * zh-TW/zh-HK carry their region only in the asset path; such files install as language-only.
 */
internal fun extractCountryFromFileName(fileName: String, languageCode: String): String? {
    if (languageCode.isEmpty()) return null
    // Match the file's own language block, not the aliased code (`_JWlsUN_` after jw -> jv).
    val suffix = Regex("_[A-Z]{2,3}[a-z]{2}UN([A-Z0-9]{2,3})_")
        .find(fileName)?.groupValues?.get(1) ?: return null
    // Allowlist: an unknown suffix would become a directory no installer looks for.
    return if (suffix in KNOWN_REGION_CODES) suffix else null
}

/**
 * Regions a pack directory may be named after: the `country` values in `assets/ldb/manifest.json`
 * plus those its LOCALE_FALLBACKS table refers to.
 */
internal val KNOWN_REGION_CODES = setOf(
    "US", "UK", "GB", "CA", "AU", "NZ", "IE", "ZA", "SG", "IN",
    "ES", "MX", "419", "BR", "PT", "FR", "DE", "IT", "NL", "BE",
    "CH", "AT", "TW", "HK", "CN", "JP", "KR", "RU",
)

private fun isValidLanguageCode(code: String): Boolean {
    val validCodes = setOf(
        "af", "am", "ar", "as", "az", "be", "bg", "bn", "bo", "bs", "ca", "cs", "cy", "da", 
        "de", "el", "en", "es", "et", "eu", "fa", "fi", "fr", "ga", "gl", "gu", "ha", "he", 
        "hi", "hr", "hu", "hy", "id", "ig", "is", "it", "ja", "jw", "ka", "kk", "km", "kn", 
        "ko", "ks", "ku", "ky", "ln", "lo", "lt", "lv", "mg", "mk", "ml", "mn", "mr", "ms", "my", 
        "ne", "nl", "no", "or", "pa", "pl", "ps", "pt", "ro", "ru", "sa", "si", "sk", "sl", 
        "sq", "sr", "st", "su", "sv", "sw", "ta", "te", "tg", "th", "tk", "tl", "tr", "tt", "ug", 
        "uk", "ur", "uz", "vi", "xh", "yo", "zh", "zu"
    )
    return code in validCodes
}

private fun getInstalledLanguagePackLocales(context: Context): Set<String> {
    val locales = mutableSetOf<String>()
    
    try {
        val nuanceDir = File(context.noBackupFilesDir, "nuance")
        if (nuanceDir.exists() && nuanceDir.isDirectory) {
            nuanceDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val ldbFiles = dir.listFiles { _, name -> name.endsWith(".ldb") }
                    val versionFile = File(dir, "version.txt")
                    if (ldbFiles != null && ldbFiles.isNotEmpty() && versionFile.exists()) {
                        locales.add(dir.name)
                    }
                }
            }
        }

        val assetManager = context.assets
        val ldbFiles = assetManager.list("ldb") ?: emptyArray()
        ldbFiles.forEach { fileName ->
            if (fileName.endsWith(".ldb")) {
                val langCode = extractLanguageFromFileName(fileName)
                if (langCode.isNotEmpty()) {
                    locales.add(langCode)
                }
            }
        }
        
    } catch (e: Exception) {
        // An unreadable directory contributes no locales.
    }
    
    return locales
}

private fun getLanguagePackVersion(context: Context, localeString: String): String {
    return try {
        val versionFile = File(context.noBackupFilesDir, "nuance/$localeString/version.txt")
        if (versionFile.exists()) {
            versionFile.readText().trim()
        } else {
            "1902.01" // Default version for preinstalled packs
        }
    } catch (e: Exception) {
        context.getString(R.string.settings_common_unknown)
    }
}
