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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.subtypeswitcher.SideloadedSubtypes
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory
import dev.bbkb.ime.core.shared.InAppEventBus
import com.blackberry.nuanceshim.languagepack.CustomPackRegistryStore
import com.blackberry.nuanceshim.languagepack.LanguageVariantStore
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import dev.bbkb.ime.core.settings.ui.LocalSpacing
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
 * Language Packs Management Screen (Compose)
 * Allows users to view installed language packs and install custom LDB files
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
        val spacing = LocalSpacing.current

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (languagePacks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(spacing.extraExtraLarge),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No language packs found",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // No info banner: what it said ("preinstalled packs cannot be deleted") is now shown by
            // the rows themselves - only removable dictionaries carry a delete button.
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                // Rows bring their own horizontal padding (PreferenceItem); the bottom inset keeps
                // the last row clear of the extended FAB (56dp + 16dp margin + 16dp breathing room).
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(languagePacks) { pack ->
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
                                    // Refresh the list
                                    scope.launch {
                                        loadLanguagePacks(context) { packs ->
                                            languagePacks = packs
                                        }
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
 * Install a custom language pack from URI
 */
private suspend fun installLanguagePack(
    context: Context,
    uri: Uri,
    onResult: (Boolean, String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw Exception("Cannot open file")
            
            // Read file to validate it's an LDB file
            val tempFile = File(context.cacheDir, "temp_language_pack.ldb")
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Basic validation - check file size and extension
            if (tempFile.length() < 1000) {
                tempFile.delete()
                throw Exception("File too small to be a valid LDB file")
            }
            
            // Try to extract language info from filename or content
            val fileName = getFileName(context, uri) ?: "unknown.ldb"
            if (!fileName.endsWith(".ldb", ignoreCase = true)) {
                tempFile.delete()
                throw Exception("File must have .ldb extension")
            }
            
            // Extract language AND country from the filename. The country half used to be
            // thrown away, so a pack from *_ENubUNUS_* landed in nuance/en - which is the
            // directory the language-only "English" pack owns - while the installer for en_US
            // looked in nuance/en_US and never found it. LanguagePackInstaller.getLocaleIdentifier
            // is the naming authority: language, or language_COUNTRY when a country is known.
            val languageCode = extractLanguageFromFileName(fileName)
            if (languageCode.isEmpty()) {
                tempFile.delete()
                throw Exception("Cannot determine language from filename")
            }
            val countryCode = extractCountryFromFileName(fileName, languageCode)
            val localeId = if (countryCode != null) "${languageCode}_$countryCode" else languageCode

            // Does this locale exist in the ENGINE's table, or does it resolve to something else?
            // LanguagePackRegistry falls back to the base language's default entry for a country it
            // does not know, so asking for the resolved identifier answers "which slot can this
            // dictionary actually occupy". de_CH resolves to "de"; en_US resolves to "en_US".
            val slot = resolvedLocaleSlot(context, languageCode, countryCode)
            if (countryCode != null && slot != null && slot != localeId) {
                // A regional variant with no engine entry of its own. It can only load AS `slot`,
                // in the place the base language's pack occupies - so group it there instead of
                // inventing a locale nothing can select. See LanguageVariantStore.
                val ok = LanguageVariantStore.installVariant(
                    context, slot, localeId, locale(languageCode, countryCode).displayName, tempFile)
                tempFile.delete()
                LanguagePackManager.getInstance(context).reloadRegistry()
                InAppEventBus.getInstance()
                    .post(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED, null)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        onResult(true, "Installed as a variant of " +
                            "${locale(slot, null).displayLanguage}, and made active. " +
                            "Switch between them on that language's row.")
                    } else {
                        onResult(false, "Could not install this regional variant")
                    }
                }
                return@withContext
            }

            // Create destination directory
            val destDir = File(context.noBackupFilesDir, "nuance/$localeId")
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            // Copy file to destination
            val destFile = File(destDir, "$localeId.ldb")
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()

            // getInstalledLocales() counts a directory as a pack only when it holds BOTH a .ldb
            // and a version.txt, and LanguagePackInstaller.isInstalled() parses the trailing
            // digits of this file. Nothing compares it against the shipped manifest's version
            // (checked: installedVersion is only ever tested against the -1 "unread" sentinel),
            // so this value is a marker, not a precedence number. 0.0 would fail isValid().
            val versionFile = File(destDir, "version.txt")
            versionFile.writeText(SIDELOADED_PACK_VERSION.toString())

            // Record it in the writable half of the registry. Without this the locale is not
            // "supported", and LanguagePackManager's hourly cleanup deletes the directory we
            // just wrote - which is why side-loading a language the APK does not ship never
            // actually worked. See CustomPackRegistryStore.
            val locale = if (countryCode != null) Locale(languageCode, countryCode)
                         else Locale(languageCode)
            val registered = CustomPackRegistryStore.add(
                context,
                languageCode,
                countryCode,
                locale.displayName.ifEmpty { localeId },
                "nuance/$localeId/$localeId.ldb",
                SIDELOADED_PACK_VERSION,
            )
            if (!registered) {
                destDir.deleteRecursively()
                throw Exception("Could not register the language pack; nothing was installed")
            }

            val languagePackManager = LanguagePackManager.getInstance(context)
            languagePackManager.reloadRegistry()
            languagePackManager.getStatus(locale)

            // If method.xml declares no subtype for this language, offer one at runtime - otherwise
            // the pack installs, the engine knows it, and there is no way to select it. Returns
            // false for the five languages with no usable layout in the tree.
            var offeredSubtype = false
            if (!hasBuiltInSubtypeFor(context, languageCode)) {
                offeredSubtype = SideloadedSubtypes.add(context, languageCode)
                if (offeredSubtype) {
                    val rimm = RichInputMethodManager.getInstance()
                    rimm.setAdditionalInputMethodSubtypes(rimm.getAdditionalSubtypes(context))
                }
            }

            InAppEventBus.getInstance().post(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED, null)

            val message = when {
                offeredSubtype ->
                    "Installed ${locale.displayName}. Enable it in Settings \u203a Languages to use it."
                hasBuiltInSubtypeFor(context, languageCode) ->
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
 * Does `res/xml/method.xml` already declare a subtype for this language?
 *
 * Asked of the framework rather than of a hardcoded list, so it stays true if method.xml changes.
 * Additional subtypes we registered ourselves are excluded - otherwise the answer would flip to
 * true after the first install and we would never withdraw the entry on delete.
 */
private fun hasBuiltInSubtypeFor(context: Context, language: String): Boolean {
    val info = RichInputMethodManager.getInstance().getInputMethodInfoOfThisIme() ?: return false
    for (i in 0 until info.subtypeCount) {
        val subtype = info.getSubtypeAt(i)
        if (SubtypeFactory.isAdditionalSubtype(subtype)) continue
        if (subtype.locale.substringBefore('_') == language) return true
    }
    return false
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
