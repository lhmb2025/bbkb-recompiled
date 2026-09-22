package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bbkb.ime.R
import dev.bbkb.ime.core.distribution.DistributionManifest
import dev.bbkb.ime.core.distribution.ManifestSource
import dev.bbkb.ime.core.languagepack.InstalledPacks
import dev.bbkb.ime.core.languagepack.PackCatalog
import dev.bbkb.ime.core.languagepack.PackDownloadManager
import dev.bbkb.ime.core.languagepack.PackInstallService
import dev.bbkb.ime.core.languagepack.PackState
import dev.bbkb.ime.core.languagepack.PackText
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.subtypeswitcher.SideloadedSubtypes
import dev.bbkb.ime.core.shared.InAppEventBus
import com.blackberry.nuanceshim.languagepack.CustomPackRegistryStore
import com.blackberry.nuanceshim.languagepack.LanguageVariantStore
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceDeleteButton
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceLeadingBadge
import dev.bbkb.ime.core.settings.ui.PreferenceTrailingLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import androidx.compose.runtime.Immutable

/**
 * Language packs: what is installed, and everything published that is not.
 *
 * Two sections. **Installed** is the older half of the screen — the packs in the APK, the ones
 * the user side-loaded with the "+" button, and the regional variant radio lists. **Available to
 * download** is the published catalogue ([PackCatalog]) minus what is installed, one row per
 * language with a size and a Download button.
 *
 * Three things about it are deliberate:
 *
 *  - **The catalogue is a hint, the disk is the truth.** Installed state is re-read off disk
 *    ([InstalledPacks.read]) whenever a download finishes, so a row never claims a pack is
 *    installed on the strength of a download having *appeared* to succeed.
 *  - **Downloads are not owned by this screen.** [PackDownloadManager] is process-wide; rotating
 *    the phone or leaving the screen mid-download changes nothing, and coming back re-attaches to
 *    the progress that has been running all along.
 *  - **An offline catalogue is still a catalogue.** [ManifestSource] serves its on-disk copy when
 *    there is no network, and the screen says so ("Catalogue from … (offline)") rather than
 *    showing an empty list or an error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePacksScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State for language packs
    var languagePacks by remember { mutableStateOf<List<InstalledLanguagePack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf<InstalledLanguagePack?>(null) }
    // Deleting a variant is confirmed separately: the thing being removed is a member of a group,
    // not the language row, and the message has to say which.
    var showVariantDeleteDialog by remember {
        mutableStateOf<Pair<String, LanguageVariantStore.Member>?>(null)
    }
    // Regional variants sharing a base language's engine slot, keyed by that base locale.
    var variantGroups by remember {
        mutableStateOf<Map<String, LanguageVariantStore.Group>>(emptyMap())
    }

    // ── The downloadable catalogue ────────────────────────────────────────────────────────────
    // The manifest, what is on disk, and what the (process-wide) download queue is doing are
    // three separate facts; PackCatalog is the only thing that merges them, and it does it
    // purely, so this screen holds inputs rather than a derived list it has to keep in step.
    val downloads = remember { PackDownloadManager.getInstance(context) }
    val downloadStates by downloads.states.collectAsStateWithLifecycle()
    var manifest by remember { mutableStateOf<DistributionManifest?>(null) }
    var installedPacks by remember { mutableStateOf(InstalledPacks()) }
    var catalogError by remember { mutableStateOf<Throwable?>(null) }
    var catalogLoading by remember { mutableStateOf(true) }
    // Incremented by the refresh action. 0 is the first, cache-friendly load; anything above it
    // is the user asking to go to the server.
    var refreshCount by remember { mutableIntStateOf(0) }

    val catalog = remember(manifest, installedPacks, downloadStates) {
        manifest?.let { PackCatalog.from(it, installedPacks, downloadStates) }
    }

    LaunchedEffect(refreshCount) {
        catalogLoading = true
        val loaded = loadCatalogue(context, force = refreshCount > 0)
        // Keep the catalogue we already had on a failed refresh: a server error is no reason to
        // throw away a perfectly good list the user was reading.
        manifest = loaded.getOrNull() ?: manifest
        catalogError = if (manifest == null) loaded.exceptionOrNull() else null
        installedPacks = readInstalledPacks(context, manifest)
        catalogLoading = false
    }

    // A download that finished has written to disk, so re-read both halves of the screen. This
    // also covers the picker, which posts through the same manager-independent path below.
    LaunchedEffect(downloadStates) {
        installedPacks = readInstalledPacks(context, manifest)
        loadLanguagePacks(context) { packs -> languagePacks = packs }
        variantGroups = withContext(Dispatchers.IO) { LanguageVariantStore.read(context) }
    }

    // File picker for LDB files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                installLanguagePack(context, it) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (success) {
                        // Refresh the list
                        scope.launch {
                            loadLanguagePacks(context) { packs ->
                                languagePacks = packs
                            }
                            variantGroups = LanguageVariantStore.read(context)
                            // And the catalogue's view of the disk, or the pack the user just
                            // side-loaded would still be sitting in "Available to download".
                            installedPacks = readInstalledPacks(context, manifest)
                        }
                    }
                }
            }
        }
    }
    
    // Load language packs on first composition
    LaunchedEffect(Unit) {
        scope.launch {
            variantGroups = LanguageVariantStore.read(context)
            loadLanguagePacks(context) { packs ->
                languagePacks = packs
                isLoading = false
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_language_packs_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Force a manifest fetch. Disabled while one is in flight so a double tap
                    // does not queue a second request behind the first.
                    IconButton(
                        onClick = { refreshCount++ },
                        enabled = !catalogLoading,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.language_packs_refresh),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        // The screen's one creative action, named. It used to be a bare + in the app bar, which
        // is where Material 3 puts secondary actions and which never said what it added.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePickerLauncher.launch("*/*") }, // any file; validated as LDB after
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add dictionary") }
            )
        }
    ) { paddingValues ->
        // No info banner: what it said ("preinstalled packs cannot be deleted") is now shown by
        // the rows themselves - only removable dictionaries carry a delete button.
        //
        // The two section headers are always in the list, even while either half is still
        // loading, so the screen never re-flows from "spinner" to "list" and the user can see
        // that a downloadable half exists before it has finished arriving.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            // Rows bring their own horizontal padding (PreferenceItem); the bottom inset keeps
            // the last row clear of the extended FAB (56dp + 16dp margin + 16dp breathing room).
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            item(key = "installed-header") {
                PreferenceCategory(stringResource(R.string.language_packs_installed_header))
            }
            if (isLoading) {
                item(key = "installed-loading") { PackLoadingRow() }
            }
            items(languagePacks, key = { "installed-" + it.languageCode + it.countryCode }) { pack ->
                LanguagePackItem(
                    pack = pack,
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
                        }
                    },
                    onDeleteVariant = { member ->
                        showVariantDeleteDialog = pack.languageCode to member
                    },
                    onDelete = if (pack.isDeletable) {
                        { showDeleteDialog = pack }
                    } else null
                )
            }

            item(key = "available-header") {
                PreferenceCategory(stringResource(R.string.language_packs_available_header))
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
            catalog?.let { loaded ->
                if (loaded.fromCache) {
                    item(key = "available-provenance") {
                        PackMessageRow(message = PackText.catalogueAsOf(context, loaded.fetchedAt))
                    }
                }
                val packs = manifest?.packs
                items(loaded.availableRows, key = { "available-" + it.locale }) { row ->
                    AvailablePackRow(
                        row = row,
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

    // Variant delete confirmation
    showVariantDeleteDialog?.let { (locale, member) ->
        AlertDialog(
            onDismissRequest = { showVariantDeleteDialog = null },
            title = { Text("Delete dictionary?") },
            text = {
                Text("Remove the ${member.name} dictionary? " +
                    "The language stays; it goes back to another dictionary for it.")
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
                                if (ok) "Removed ${member.name}" else "Could not remove ${member.name}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        showVariantDeleteDialog = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showVariantDeleteDialog = null }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { pack ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Language Pack?") },
            text = { 
                Text("Are you sure you want to delete the ${pack.displayName} language pack? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            deleteLanguagePack(context, pack) { success, message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                if (success) {
                                    // Refresh the list - and the catalogue's view of the disk, so
                                    // a deleted pack goes back to being downloadable.
                                    scope.launch {
                                        loadLanguagePacks(context) { packs ->
                                            languagePacks = packs
                                        }
                                        installedPacks = readInstalledPacks(context, manifest)
                                    }
                                }
                            }
                        }
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * One language, built on the app's own [PreferenceItem] rather than a hand-rolled card, so this
 * screen reads like every other settings list: bodyLarge titles, 16dp padding, 48dp minimum rows.
 *
 * The icon column carries the language code, so titles still line up with the icon-bearing category
 * screens the user navigates through to get here. The right edge carries "Preinstalled" or a delete
 * button: the version was noise (these dictionaries are never updated) and "Custom" was redundant
 * with the delete button, which is how a removable pack identifies itself.
 */
@Composable
private fun LanguagePackItem(
    pack: InstalledLanguagePack,
    variantGroup: LanguageVariantStore.Group? = null,
    onSelectVariant: (String) -> Unit = {},
    onDeleteVariant: (LanguageVariantStore.Member) -> Unit = {},
    onDelete: (() -> Unit)? = null
) {
    val spacing = LocalSpacing.current
    val grouped = variantGroup != null && variantGroup.members.size > 1

    if (!grouped) {
        PreferenceItem(
            title = pack.displayName,
            leading = { LanguageCodeBadge(pack.languageCode) },
            // The trailing slot holds either the Preinstalled tag or the delete button - the same
            // one-slot, two-meanings rule the dictionary rows use, at the same right edge.
            trailing = when {
                onDelete != null -> {
                    { PreferenceDeleteButton(contentDescription = "Delete ${pack.displayName}", onClick = onDelete) }
                }
                pack.isPreinstalled -> {
                    { PreinstalledTag() }
                }
                else -> null
            }
        )
        return
    }

    // A language with regional dictionaries sits on a light rounded surface, so the language and
    // its choices read as one object. The surface is inset by [inset] on each side and every inner
    // horizontal padding is reduced by the same amount, which keeps the code column, the title and
    // the trailing controls at exactly the x positions of the plain rows around it.
    val inset = spacing.small
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = inset, vertical = spacing.extraSmall)
            .clip(RoundedCornerShape(16.dp))
            // surfaceVariant at half strength: the theme defines no surfaceContainer tokens, and
            // Material's defaults for those would bring in the baseline purple.
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(bottom = spacing.small)
    ) {
        // Header: PreferenceItem's metrics without its bottom padding, so the choices sit right
        // under the name they belong to. No "Preinstalled" - the shipped member row says it.
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
            Text(
                text = pack.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            onDelete?.let { delete ->
                PreferenceDeleteButton(contentDescription = "Delete ${pack.displayName}", onClick = delete)
            }
        }

        // Regional variants that can only load in THIS language's slot, e.g. Swiss German under
        // German. The engine holds one dictionary per locale, so this is a single choice.
        variantGroup!!.members.forEach { member ->
            val selected = member.tag == variantGroup.activeTag
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Exactly the 48dp touch target and no more: no vertical padding, so the
                    // delete button (itself 48dp) no longer makes its row taller than its sibling.
                    .heightIn(min = spacing.touchTarget)
                    .selectable(
                        selected = selected,
                        onClick = { onSelectVariant(member.tag) },
                        role = Role.RadioButton
                    )
                    .padding(
                        // Indented to the title column.
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
                // One trailing slot, two meanings, never both: the shipped dictionary is in the
                // APK and cannot be removed, so it gets the label; every other member is a file
                // the user added, so it gets the way to remove it.
                if (member.isShipped) {
                    PreinstalledTag()
                } else {
                    PreferenceDeleteButton(
                        contentDescription = "Delete ${member.name}",
                        onClick = { onDeleteVariant(member) }
                    )
                }
            }
        }
    }
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

/**
 * A sentence about the section rather than about a pack: "Catalogue from … (offline)", or why the
 * catalogue is not there, with the one action that might change that.
 */
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
 * One language in the **Available to download** section: the language's own dictionary, and under
 * it the regional dictionaries that would load in its place.
 *
 * The base row is a header rather than a button when the base pack is already installed — that
 * happens for German, French, Italian and Dutch, which ship with the app and whose Swiss and
 * Belgian variants are the only downloadable thing about them. Without the header those variants
 * would appear as four unexplained rows named after countries.
 */
@Composable
private fun AvailablePackRow(
    row: PackCatalog.Row,
    onDownload: (PackCatalog.Item) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (PackCatalog.Item) -> Unit,
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    if (row.base.state !is PackState.Installed) {
        PackActionRow(
            item = row.base,
            summary = null,
            onDownload = onDownload,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    } else if (row.availableVariants.isNotEmpty()) {
        PreferenceItem(
            title = row.displayName,
            summary = null,
            leading = { LanguageCodeBadge(row.locale) },
            trailing = { PreinstalledTag() },
        )
    }

    row.availableVariants.forEach { variant ->
        PackActionRow(
            item = variant,
            // Named, not implied: installing one of these REPLACES the dictionary the language
            // loads today, which is not what "German (Switzerland)" on its own suggests.
            summary = context.getString(R.string.language_packs_variant_summary, row.displayName),
            indent = spacing.iconSize + spacing.iconTextGap,
            onDownload = onDownload,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

/**
 * One downloadable pack: its name, its size or its progress, and the single action its state
 * allows. Available offers Download, downloading offers Cancel, a failure offers Try again —
 * never two at once, which is what keeps a 48dp row readable on a 1440x1440 KEY2 screen.
 */
@Composable
private fun PackActionRow(
    item: PackCatalog.Item,
    summary: String?,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
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
        leading = { LanguageCodeBadge(item.locale) },
        modifier = Modifier.padding(start = indent),
        trailing = {
            when (state) {
                is PackState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    // Determinate whenever the manifest gave a size, which it always does - the
                    // denominator is the catalogue's, not the server's Content-Length.
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.width(64.dp),
                    )
                    IconButton(onClick = { onCancel(item.locale) }) {
                        Icon(
                            Icons.Default.Close,
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
 * The language's code, heavy, in the row's icon column: a glanceable identifier that also tells two
 * similarly named languages apart.
 *
 * The badge itself is [PreferenceLeadingBadge] now — other screens want the same thing in the same
 * slot (a macro tag, a config initial), and three private copies of one 24dp-unbounded text is how
 * the leading columns drift apart. What stays here is the only part that is about languages: the
 * code is upper-cased in the app's locale-independent form, so a Turkish device does not render
 * "tr" as "TR" with a dotted I.
 */
@Composable
private fun LanguageCodeBadge(code: String) {
    PreferenceLeadingBadge(code.uppercase(Locale.ROOT))
}

/** "Preinstalled", right-aligned with the delete buttons. */
@Composable
private fun PreinstalledTag() = PreferenceTrailingLabel("Preinstalled")

/**
 * One installed language pack, as this screen displays it.
 *
 * Deliberately NOT com.blackberry.nuanceshim.languagepack.LanguagePackInfo, which is the shim's
 * manifest-parsed record: this is the screen's own row model, with the display name and the
 * deletable flag the list needs. It used to share that simple name (§5.11).
 *
 * @Immutable reduces unnecessary recompositions when used in lists
 */
@Immutable
data class InstalledLanguagePack(
    val languageCode: String,
    val countryCode: String = "",
    val displayName: String,
    val version: String,
    val filePath: String,
    val isPreinstalled: Boolean,
    val isDeletable: Boolean = !isPreinstalled
)

/**
 * Load all installed language packs
 */
private suspend fun loadLanguagePacks(
    context: Context,
    onResult: (List<InstalledLanguagePack>) -> Unit
) {
    withContext(Dispatchers.IO) {
        val packs = mutableListOf<InstalledLanguagePack>()
        
        try {
            // Get language pack manager
            val languagePackManager = LanguagePackManager.getInstance(context)
            
            // Get installed language pack directories from filesystem
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
                            // Determine if it's preinstalled by checking if LDB exists in assets
                            val isPreinstalled = isPreinstalledPack(context, localeString)
                            
                            packs.add(
                                InstalledLanguagePack(
                                    languageCode = locale.language,
                                    countryCode = locale.country,
                                    displayName = locale.displayName,
                                    version = if (isInstalled) getLanguagePackVersion(context, localeString) else "Not installed",
                                    filePath = getLanguagePackPath(context, localeString),
                                    isPreinstalled = isPreinstalled
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Skip invalid locale entries
                    continue
                }
            }
            
            // Sort by display name
            packs.sortBy { it.displayName }
            
        } catch (e: Exception) {
            // Handle errors gracefully
        }
        
        withContext(Dispatchers.Main) {
            onResult(packs)
        }
    }
}

/**
 * Check if a language pack is preinstalled
 */
private fun isPreinstalledPack(context: Context, localeString: String): Boolean {
    return try {
        val assetManager = context.assets
        val ldbFiles = assetManager.list("ldb") ?: emptyArray()
        ldbFiles.any { it.contains(localeString, ignoreCase = true) && it.endsWith(".ldb") }
    } catch (e: Exception) {
        false
    }
}

/**
 * Get the file path for a language pack
 */
private fun getLanguagePackPath(context: Context, localeString: String): String {
    val customPath = File(context.noBackupFilesDir, "nuance/$localeString")
    return if (customPath.exists()) {
        customPath.absolutePath
    } else {
        "assets/ldb/"
    }
}

/**
 * Install a dictionary the user picked with the "+" button.
 *
 * The only thing this does that the download path does not is **work out the locale**: a picked
 * file comes with nothing but a name, so the BlackBerry catalogue's naming convention is parsed
 * out of it ([extractLanguageFromFileName] / [extractCountryFromFileName]) and the result is
 * checked against the engine's own registry to see whether that locale exists or whether the pack
 * can only load as a variant of its base language.
 *
 * Everything after that — writing the files, the registry entry, the runtime subtype, the change
 * event — is [PackInstallService], shared with [PackDownloadManager]. A downloaded pack skips
 * this function entirely and installs under the locale the manifest states; see
 * [PackInstallService] for why the file name must never decide that.
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
                ?: throw Exception("Cannot open file")

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Basic validation - check file size and extension
            if (tempFile.length() < PackInstallService.MIN_LDB_BYTES) {
                throw Exception("File too small to be a valid LDB file")
            }

            val fileName = getFileName(context, uri) ?: "unknown.ldb"
            if (!fileName.endsWith(".ldb", ignoreCase = true)) {
                throw Exception("File must have .ldb extension")
            }

            // Extract language AND country from the filename. The country half used to be
            // thrown away, so a pack from *_ENubUNUS_* landed in nuance/en - which is the
            // directory the language-only "English" pack owns - while the installer for en_US
            // looked in nuance/en_US and never found it. LanguagePackInstaller.getLocaleIdentifier
            // is the naming authority: language, or language_COUNTRY when a country is known.
            val languageCode = extractLanguageFromFileName(fileName)
            if (languageCode.isEmpty()) {
                throw Exception("Cannot determine language from filename")
            }
            val countryCode = extractCountryFromFileName(fileName, languageCode)
            val localeId = if (countryCode != null) "${languageCode}_$countryCode" else languageCode
            val locale = locale(languageCode, countryCode)

            // Does this locale exist in the ENGINE's table, or does it resolve to something else?
            // LanguagePackRegistry falls back to the base language's default entry for a country it
            // does not know, so asking for the resolved identifier answers "which slot can this
            // dictionary actually occupy". de_CH resolves to "de"; en_US resolves to "en_US".
            //
            // A regional variant with no engine entry of its own can only load AS `slot`, in the
            // place the base language's pack occupies - so it is grouped there rather than
            // inventing a locale nothing can select. This is the same `group` the catalogue
            // states for these four packs; the picker just has to work it out for itself.
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
                    "Installed as a variant of ${locale(group!!, null).displayLanguage}, and made " +
                        "active. Switch between them on that language's row."
                installed.offeredSubtype ->
                    "Installed ${locale.displayName}. Enable it in Settings › Languages to use it."
                installed.selectable ->
                    "Language pack installed successfully: ${locale.displayName}"
                else ->
                    "Installed ${locale.displayName}, but this keyboard has no layout for its " +
                        "script yet, so it cannot be selected."
            }
            withContext(Dispatchers.Main) {
                onResult(true, message)
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult(false, "Failed to install language pack: ${e.message}")
            }
        } finally {
            // The service copies what it installs, so the staging file is ours to remove however
            // this went.
            tempFile.delete()
        }
    }
}

/**
 * Delete a custom language pack
 */
private suspend fun deleteLanguagePack(
    context: Context,
    pack: InstalledLanguagePack,
    onResult: (Boolean, String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            if (pack.isPreinstalled) {
                throw Exception("Cannot delete preinstalled language packs")
            }
            
            val packDir = File(pack.filePath)
            if (packDir.exists() && packDir.isDirectory) {
                packDir.deleteRecursively()

                // Drop the registry entry too. Leaving it behind would leave the locale looking
                // "supported" with no files under it, so getStatus would report a pack that is
                // not there.
                CustomPackRegistryStore.remove(context, pack.languageCode, pack.countryCode)

                // Withdraw the runtime subtype too, if this pack is what created one. Leaving it
                // would offer a language with no dictionary behind it.
                SideloadedSubtypes.remove(context, pack.languageCode)
                val rimm = RichInputMethodManager.getInstance()
                rimm.setAdditionalInputMethodSubtypes(rimm.getAdditionalSubtypes(context))

                val locale = Locale(pack.languageCode, pack.countryCode)
                val languagePackManager = LanguagePackManager.getInstance(context)
                languagePackManager.reloadRegistry()
                languagePackManager.getStatus(locale)
                // Note: Deregistration will be handled by the language pack manager automatically
                
                withContext(Dispatchers.Main) {
                    onResult(true, "Language pack deleted: ${pack.displayName}")
                }
            } else {
                throw Exception("Language pack directory not found")
            }
            
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onResult(false, "Failed to delete language pack: ${e.message}")
            }
        }
    }
}

/**
 * Get filename from URI
 */
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
 * Extract language code from LDB filename
 */
/**
 * The catalogue's language block: the UPPER-CASE run that opens the locale group, e.g. `EN` in
 * `_ENubUNUS_` and `SAT` in `_SATlsUNdevanagari_`.
 *
 * Verified to match all 116 shipped catalogue filenames, 111 with a two-letter block and 5 with a
 * three-letter one (`BRX`, `DOI`, `MAI`, `MNI`, `SAT`). Its length is therefore an exact test of
 * which code space the file uses, where the old lower-cased heuristic could only ever see two
 * letters and silently truncated the rest.
 */
private val LANGUAGE_BLOCK = Regex("_([A-Z]{2,3})[a-z]{2}UN")

/**
 * ISO 639-2/3 codes the ET9 engine has no entry for, mapped to nothing.
 *
 * The engine resolves a locale through a fixed 105-entry table in the shipped blob
 * (`libnative-lib.so:0x217c50`), of which `assets/ldb/manifest.json` is a byte-for-byte mirror.
 * None of `brx`/`doi`/`mai`/`mni`/`sat` is in it, so these packs cannot load on this build however
 * they are named: they belong to a different ET9 release.
 *
 * They are rejected EXPLICITLY rather than left to the allowlist, because truncation made the
 * outcome a coin flip — `MNI` became `mn` (Mongolian) and `SAT` became `sa` (Sanskrit), both real
 * languages with their own packs in the same catalogue, so a user installing Manipuri silently
 * overwrote Mongolian. A refusal the user can see beats a corruption they cannot.
 */
private fun hasUnsupportedThreeLetterCode(fileName: String): Boolean =
    LANGUAGE_BLOCK.find(fileName)?.groupValues?.get(1)?.length == 3

/**
 * Filename spelling → the spelling the rest of the stack uses.
 *
 * The LDB filenames carry codes ISO has since retired (or, for Filipino and Norwegian, a
 * less-specific one). The engine's locale table, `assets/ldb/manifest.json` and `res/xml/method.xml`
 * all agree on the other spelling — so this is purely a filename concern, and these five packs
 * install, resolve and get a working subtype the moment the name is translated.
 */
private val LANGUAGE_CODE_ALIASES = mapOf(
    "jw" to "jv",    // Javanese: `jw` retired 1989
    "he" to "iw",    // Hebrew: Java's Locale normalises he -> iw, and the engine table follows it
    "id" to "in",    // Indonesian: likewise id -> in
    "tl" to "fil",   // Tagalog -> Filipino, which is what the engine and method.xml carry
    "no" to "nb",    // Norwegian -> Bokmal
)

internal fun extractLanguageFromFileName(fileName: String): String {
    // Heuristics to extract language from BlackBerry LDB filename patterns.
    //
    // Patterns 1-3 are ordered most to least specific and every one of them is now validated
    // against isValidLanguageCode, which previously guarded only the fallback - so patterns 1-3
    // could return a junk two-letter code that ended up as a directory name under
    // noBackupFilesDir/nuance/.
    //
    // The "Chinese/Japanese special cases" that used to follow (blackberry_([a-z]{2})[a-z]*_ and
    // three _zh…_ variants) were unreachable: pattern 1 matches every input they targeted and
    // returns first. For "..._zhsbunps_gb2312_..." pattern 1 matches "_zhsbunps_" and yields
    // "zh"; for "blackberry_jalsunkana_..." it matches "_jalsunkana_" and yields "ja".
    // Reject a three-letter code before the two-letter patterns get a chance to truncate it.
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

    // If no pattern matches, return empty string
    return ""
}


/**
 * The catalogue, from the network or from the on-disk copy.
 *
 * [ManifestSource] already owns all of the policy: a fetch with `force = false` serves a cache
 * younger than six hours without a request at all, and serves a cache of any age when there is no
 * network. The one thing added here is the last fallback — when a *forced* fetch fails,
 * `ManifestSource` deliberately does not substitute the cache (the caller asked to go to the
 * server and deserves to hear that it did not work), but this screen would rather show the
 * catalogue it has and label it than show an error where a list used to be.
 */
private suspend fun loadCatalogue(context: Context, force: Boolean): Result<DistributionManifest> {
    val source = ManifestSource(context)
    val fetched = source.fetch(force)
    if (fetched.isSuccess) return fetched
    val cached = withContext(Dispatchers.IO) { source.cached() }
    return if (cached != null) Result.success(cached) else fetched
}

/**
 * What is on disk, asked only about the locales the catalogue actually offers.
 *
 * Blocking disk I/O, hence the dispatcher. With no catalogue there is nothing to ask about, so
 * this is empty rather than a walk of every locale the engine knows.
 */
private suspend fun readInstalledPacks(
    context: Context,
    manifest: DistributionManifest?,
): InstalledPacks = withContext(Dispatchers.IO) {
    val locales = manifest?.packs?.items?.map { it.locale } ?: return@withContext InstalledPacks()
    InstalledPacks.read(context, locales)
}

/**
 * The locale slot a pack for [language]/[country] would actually occupy.
 *
 * `LanguagePackRegistry` resolves an unknown country to the base language's default entry, so this
 * returns `"en_US"` for a pack that has its own engine entry and `"de"` for one that does not
 * (`de_CH`). `null` means the registry does not know the language at all.
 */
private fun resolvedLocaleSlot(context: Context, language: String, country: String?): String? {
    val status = LanguagePackManager.getInstance(context)
        .getStatus(locale(language, country)) ?: return null
    return if (status.isSupported) status.localeIdentifier else null
}

private fun locale(language: String, country: String?): Locale =
    if (country.isNullOrEmpty()) Locale(language) else Locale(language, country)

/**
 * The version written into a side-loaded pack's `version.txt`, and recorded for it in
 * [CustomPackRegistryStore].
 *
 * We cannot know a user-supplied .ldb's real version - it is not in the filename and we do not
 * parse the container - and nothing in the app compares it against the shipped manifest's
 * versions anyway (`LanguagePackInstaller.installedVersion` is only ever tested against the -1
 * "not yet read" sentinel). It must simply be present and parse as a positive number:
 * `getInstalledLocales()` requires a `version.txt` beside the .ldb, and `LanguagePackInfo.isValid`
 * rejects `version <= 0`.
 */
private const val SIDELOADED_PACK_VERSION = 1.0

/**
 * The country half of a BlackBerry LDB filename, or null when it carries none.
 *
 * Derived from the ten names actually shipped in `app/src/main/assets/ldb/`, which all follow
 * `<LANG><codec><UN>[<REGION>]`:
 *
 * ```
 *   _ENubUN_      en          (language-only; UN is the shared vendor marker, not a region)
 *   _ENubUNUS_    en-US
 *   _ENubUNUK_    en-UK
 *   _ESusUNES_    es-ES
 *   _ZHsbUNps_    zh          (trailing "ps" is lower-case: codec noise, not a region)
 *   _UKlsUN_      uk          (Ukrainian - the language code itself looks like a region)
 * ```
 *
 * So the region is an UPPER-CASE run immediately after the `UN` marker; anything lower-case there
 * is vendor noise. Matching on the original case is what separates the two, which is why this does
 * not lower-case the name first as [extractLanguageFromFileName] does.
 *
 * Two deliberate non-goals. `_ESusUNlatam_` (es-419) is not matched: its region is lower-case and
 * lives in the asset's `es_419/` path prefix, not the filename. And zh-TW/zh-HK likewise carry
 * their region only in the path. A side-loaded file with no region in its name installs as
 * language-only, which is honest - guessing would put the pack in a directory nothing looks in.
 */
internal fun extractCountryFromFileName(fileName: String, languageCode: String): String? {
    if (languageCode.isEmpty()) return null
    // Matched against the filename's OWN language block, not the (possibly aliased) code the
    // caller resolved: `_JWlsUN_` must still parse after jw -> jv, and searching for `_JV...`
    // would find nothing.
    val suffix = Regex("_[A-Z]{2,3}[a-z]{2}UN([A-Z0-9]{2,3})_")
        .find(fileName)?.groupValues?.get(1) ?: return null
    // Allowlist, not "any two capitals": an unrecognised suffix would become a directory name
    // under noBackupFilesDir/nuance/ that no installer ever looks for.
    return if (suffix in KNOWN_REGION_CODES) suffix else null
}

/**
 * Regions the app is prepared to name a pack directory after. Taken from the `country` values in
 * `assets/ldb/manifest.json` plus the ones its LOCALE_FALLBACKS table refers to, so a side-loaded
 * pack can land on exactly the identifiers the rest of the registry already understands.
 */
internal val KNOWN_REGION_CODES = setOf(
    "US", "UK", "GB", "CA", "AU", "NZ", "IE", "ZA", "SG", "IN",
    "ES", "MX", "419", "BR", "PT", "FR", "DE", "IT", "NL", "BE",
    "CH", "AT", "TW", "HK", "CN", "JP", "KR", "RU",
)

/**
 * Check if a 2-letter code could be a valid language code
 */
private fun isValidLanguageCode(code: String): Boolean {
    // List of common ISO 639-1 language codes that might appear in BlackBerry LDB files
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

/**
 * Get installed language pack locales from filesystem
 */
private fun getInstalledLanguagePackLocales(context: Context): Set<String> {
    val locales = mutableSetOf<String>()
    
    try {
        // Check custom language packs directory
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
        
        // Check preinstalled language packs in assets
        val assetManager = context.assets
        val ldbFiles = assetManager.list("ldb") ?: emptyArray()
        ldbFiles.forEach { fileName ->
            if (fileName.endsWith(".ldb")) {
                // Extract language from filename
                val langCode = extractLanguageFromFileName(fileName)
                if (langCode.isNotEmpty()) {
                    locales.add(langCode)
                }
            }
        }
        
    } catch (e: Exception) {
        // Handle errors gracefully
    }
    
    return locales
}

/**
 * Get language pack version from version file
 */
private fun getLanguagePackVersion(context: Context, localeString: String): String {
    return try {
        val versionFile = File(context.noBackupFilesDir, "nuance/$localeString/version.txt")
        if (versionFile.exists()) {
            versionFile.readText().trim()
        } else {
            "1902.01" // Default version for preinstalled packs
        }
    } catch (e: Exception) {
        "Unknown"
    }
}
