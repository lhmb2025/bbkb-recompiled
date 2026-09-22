package dev.bbkb.ime.core.languagepack

import android.content.Context
import com.blackberry.nuanceshim.languagepack.CustomPackRegistryStore
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import com.blackberry.nuanceshim.languagepack.LanguageVariantStore
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.shared.InAppEventBus
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.subtypeswitcher.SideloadedSubtypes
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The one code path that turns a `.ldb` file into an installed dictionary.
 *
 * Both ways a pack can arrive end here: the "+" picker on the Language packs screen (which has
 * only a file name to go on, so it parses the locale out of it first) and
 * [PackDownloadManager] (which has the catalogue entry, so it passes the manifest's locale).
 * Having one installer is the point — before this, the picker's install lived inside the
 * composable, so anything else that wanted to install a pack would have had to reimplement the
 * registry bookkeeping that makes a pack survive.
 *
 * ### What "installed" means on disk
 *
 * For an ordinary pack, exactly what `LanguagePackInstaller` looks for:
 *
 * ```
 * no_backup/nuance/<locale>/<locale>.ldb
 * no_backup/nuance/<locale>/version.txt
 * files/nuance_custom_packs.json   <- the registry entry that makes the locale "supported"
 * ```
 *
 * Both files are required: `LanguagePackInstaller.getInstalledLocales` counts a directory only
 * when it holds a `.ldb` **and** a `version.txt`. The registry entry is required as well —
 * without it the locale is not supported, `getStatus` will not resolve it, and the pack is a
 * directory nothing ever reads.
 *
 * For a pack with a [group] — the four regional variants ({@code de_CH}, {@code fr_CH},
 * {@code it_CH}, {@code nl_BE}) the engine has no locale table entry for — the file is parked and
 * activated by [LanguageVariantStore] instead, in the slot the base language occupies. That is
 * the same call the picker has always made for these; see that class for why they cannot be
 * installed as locales of their own.
 *
 * ### The locale is the caller's, and a downloaded pack's comes from the manifest
 *
 * This service never looks at [File.getName]. A downloaded pack installs under the locale the
 * catalogue states, which is the only thing that can place `es_419`, `zh_TW`, `zh_HK` and
 * `en_ZH`: all four carry their region in the catalogue and not in the file name, so a file-name
 * parse reads them as `es`, `zh`, `zh` and `en` and three of them would land on top of a
 * different language's dictionary.
 *
 * ### Threading and ownership
 *
 * [installFromFile] is a suspend function that does all of its work on [Dispatchers.IO]. The
 * source file is **copied**: the caller still owns it and is responsible for deleting it.
 * A failed install leaves nothing behind.
 */
class PackInstallService @JvmOverloads constructor(
    private val context: Context,
    private val subtypes: SubtypeRegistrar = FrameworkSubtypeRegistrar,
    private val events: (String) -> Unit = { action ->
        InAppEventBus.getInstance().post(action, null)
    },
) {

    /**
     * Copy [file] into place as the dictionary for [locale] and register it.
     *
     * @param file the `.ldb` to install. Copied, not moved.
     * @param locale the identifier the pack installs under: `language` or `language_COUNTRY`
     *   with an underscore. For a downloaded pack this is `PackEntry.locale` verbatim — never a
     *   value derived from the file name.
     * @param displayName the name recorded in the registry and shown in the list.
     * @param version what goes in `version.txt` and in the registry entry. It is a marker, not a
     *   precedence number (nothing in the app compares it), but it must parse as a positive
     *   number, so anything unusable falls back to [FALLBACK_VERSION].
     * @param group non-null only for a regional variant that loads *in place of* a base
     *   language; the value is that base language's locale (`PackEntry.group`).
     * @return the installed pack, or a [PackInstallException] describing what stopped it.
     */
    suspend fun installFromFile(
        file: File,
        locale: String,
        displayName: String,
        version: String,
        group: String?,
    ): Result<InstalledPack> = withContext(Dispatchers.IO) {
        try {
            Result.success(install(file, locale, displayName, version, group))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PackInstallException) {
            Result.failure(failure)
        } catch (failure: IOException) {
            Result.failure(PackInstallException("Could not install $locale", failure))
        }
    }

    /**
     * Remove the pack installed under [locale]: its `nuance/` directory, its registry entry and
     * the runtime subtype it may have brought with it.
     *
     * Idempotent on purpose. The Language packs screen builds its rows from a snapshot of the
     * disk, so by the time the user confirms a delete the files can already be gone (a second
     * tap on a row that had not refreshed yet, a variant swap that emptied the slot). That is not
     * a failure: whatever is still there is cleaned up and [UninstallOutcome.ALREADY_GONE] says
     * so, instead of the old "directory not found" error over a pack that was in fact removed.
     *
     * Variants are not handled here; they are members of a [LanguageVariantStore] group and are
     * removed through it.
     */
    suspend fun uninstall(locale: String): Result<UninstallOutcome> = withContext(Dispatchers.IO) {
        try {
            Result.success(uninstallNow(locale))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PackInstallException) {
            Result.failure(failure)
        } catch (failure: RuntimeException) {
            Result.failure(PackInstallException("Could not remove $locale", failure))
        }
    }

    private fun uninstallNow(locale: String): UninstallOutcome {
        if (!CustomPackRegistryStore.isValidLocaleIdentifier(locale)) {
            throw PackInstallException("Not a usable locale identifier: '$locale'")
        }
        val directory = packDirectory(locale)
        val hadFiles = directory.exists()
        if (hadFiles && !directory.deleteRecursively()) {
            throw PackInstallException("Could not delete the files for $locale")
        }
        val language = locale.substringBefore('_')
        val country = locale.substringAfter('_', "").ifEmpty { null }
        // Both of these are no-ops when there is nothing to remove.
        CustomPackRegistryStore.remove(context, language, country)
        subtypes.withdrawSubtypeFor(context, language)
        LanguagePackManager.getInstance(context).reloadRegistry()
        events(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED)
        Logger.info(TAG, "Removed $locale (files were ${if (hadFiles) "present" else "already gone"})")
        return if (hadFiles) UninstallOutcome.REMOVED else UninstallOutcome.ALREADY_GONE
    }

    private fun install(
        file: File,
        locale: String,
        displayName: String,
        version: String,
        group: String?,
    ): InstalledPack {
        if (!file.isFile) throw PackInstallException("No such file: $file")
        if (file.length() < MIN_LDB_BYTES) {
            throw PackInstallException("File is too small to be a dictionary: ${file.length()} bytes")
        }
        if (!CustomPackRegistryStore.isValidLocaleIdentifier(locale)) {
            throw PackInstallException("Not a usable locale identifier: '$locale'")
        }
        if (group != null && !CustomPackRegistryStore.isValidLocaleIdentifier(group)) {
            throw PackInstallException("Not a usable group locale: '$group'")
        }
        val name = displayName.ifBlank { locale }
        val versionValue = version.trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: FALLBACK_VERSION

        return if (group != null) {
            installVariant(file, locale, name, group)
        } else {
            installBase(file, locale, name, versionValue)
        }
    }

    /**
     * A variant goes to [LanguageVariantStore], which parks it under `lang_variants/<group>/` and
     * copies it into the base language's slot. No [CustomPackRegistryStore] entry: the locale it
     * loads as is the base language, which the shipped registry already knows.
     */
    private fun installVariant(
        file: File,
        locale: String,
        displayName: String,
        group: String,
    ): InstalledPack {
        if (!LanguageVariantStore.installVariant(context, group, locale, displayName, file)) {
            throw PackInstallException("Could not install $locale as a variant of $group")
        }
        LanguagePackManager.getInstance(context).reloadRegistry()
        events(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED)
        Logger.info(TAG, "Installed $locale as an active variant of $group")
        return InstalledPack(
            locale = locale,
            displayName = displayName,
            group = group,
            directory = packDirectory(group),
            offeredSubtype = false,
            // The base language is what gets selected, and it is a shipped language in all four
            // cases, so it always has a subtype.
            selectable = true,
        )
    }

    private fun installBase(
        file: File,
        locale: String,
        displayName: String,
        version: Double,
    ): InstalledPack {
        val language = locale.substringBefore('_')
        val country = locale.substringAfter('_', "").ifEmpty { null }
        val directory = packDirectory(locale)
        val existed = directory.isDirectory

        if (!directory.isDirectory && !directory.mkdirs()) {
            throw PackInstallException("Could not create $directory")
        }
        try {
            file.copyTo(File(directory, "$locale.ldb"), overwrite = true)
            // getInstalledLocales() wants BOTH files; isInstalled() parses the trailing digits of
            // this one.
            File(directory, "version.txt").writeText(version.toString())
        } catch (failed: IOException) {
            if (!existed) directory.deleteRecursively()
            throw PackInstallException("Could not write the dictionary for $locale", failed)
        }

        val registered = CustomPackRegistryStore.add(
            context,
            language,
            country,
            displayName,
            "nuance/$locale/$locale.ldb",
            version,
        )
        if (!registered) {
            // Nothing half-installed: an unregistered directory is a locale the app does not
            // consider supported, which is indistinguishable from a pack that is simply broken.
            if (!existed) directory.deleteRecursively()
            throw PackInstallException("Could not register $locale; nothing was installed")
        }

        LanguagePackManager.getInstance(context).reloadRegistry()

        // If method.xml declares no subtype for this language the pack would install, load, and
        // have no way to be selected. Offer one at runtime instead - and report honestly when
        // even that is impossible (five languages have no layout in the tree).
        val builtIn = subtypes.hasBuiltInSubtypeFor(language)
        val offered = if (builtIn) false else subtypes.offerSubtypeFor(context, language)

        events(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED)
        Logger.info(TAG, "Installed $locale (subtype: builtIn=$builtIn offered=$offered)")
        return InstalledPack(
            locale = locale,
            displayName = displayName,
            group = null,
            directory = directory,
            offeredSubtype = offered,
            selectable = builtIn || offered,
        )
    }

    private fun packDirectory(locale: String): File =
        File(context.noBackupFilesDir, "nuance/$locale")

    /**
     * How a newly installed language gets something the user can select.
     *
     * An interface so the install path can be tested without the input-method framework: the
     * real implementation asks [RichInputMethodManager] what `method.xml` declares and registers
     * additional subtypes, neither of which exists in a plain JVM test.
     */
    interface SubtypeRegistrar {
        /** Does `res/xml/method.xml` already declare a subtype for this language? */
        fun hasBuiltInSubtypeFor(language: String): Boolean

        /** Add a runtime subtype for this language. `false` when there is no usable layout. */
        fun offerSubtypeFor(context: Context, language: String): Boolean

        /** Take back a runtime subtype added by [offerSubtypeFor]. A no-op when none was. */
        fun withdrawSubtypeFor(context: Context, language: String)
    }

    /** The production [SubtypeRegistrar]: the framework, plus [SideloadedSubtypes]. */
    object FrameworkSubtypeRegistrar : SubtypeRegistrar {
        override fun hasBuiltInSubtypeFor(language: String): Boolean {
            // Additional subtypes we registered ourselves are excluded, or the answer would flip
            // to true after the first install and the entry would never be withdrawn on delete.
            val info = try {
                RichInputMethodManager.getInstance().inputMethodInfoOfThisIme
            } catch (unavailable: RuntimeException) {
                Logger.warn(TAG, "No InputMethodManager to ask about subtypes: $unavailable")
                return false
            } ?: return false
            for (i in 0 until info.subtypeCount) {
                val subtype = info.getSubtypeAt(i)
                if (SubtypeFactory.isAdditionalSubtype(subtype)) continue
                if (subtype.locale.substringBefore('_') == language) return true
            }
            return false
        }

        override fun offerSubtypeFor(context: Context, language: String): Boolean {
            if (!SideloadedSubtypes.add(context, language)) return false
            return try {
                val rimm = RichInputMethodManager.getInstance()
                rimm.setAdditionalInputMethodSubtypes(rimm.getAdditionalSubtypes(context))
                true
            } catch (unavailable: RuntimeException) {
                // The subtype is recorded in our own preference either way; the framework will
                // pick it up the next time anything re-registers.
                Logger.warn(TAG, "Recorded a runtime subtype but could not register it: $unavailable")
                true
            }
        }

        override fun withdrawSubtypeFor(context: Context, language: String) {
            SideloadedSubtypes.remove(context, language)
            try {
                val rimm = RichInputMethodManager.getInstance()
                rimm.setAdditionalInputMethodSubtypes(rimm.getAdditionalSubtypes(context))
            } catch (unavailable: RuntimeException) {
                Logger.warn(TAG, "Withdrew a runtime subtype but could not re-register: $unavailable")
            }
        }
    }

    companion object {
        private const val TAG = "PackInstall"

        /**
         * A file smaller than this is not a dictionary. Kept from the picker path, where it is the
         * only thing standing between a mis-picked text file and a `nuance/` directory the engine
         * will try to load.
         */
        const val MIN_LDB_BYTES: Long = 1_000L

        /**
         * The `version.txt` value used when the caller has nothing better. The catalogue passes
         * its own version (`"1902.01"`); the picker has no version to pass at all.
         */
        const val FALLBACK_VERSION: Double = 1.0
    }
}

/** What [PackInstallService.uninstall] found to remove. Both are success. */
enum class UninstallOutcome {
    /** The pack's files were on disk and are now gone. */
    REMOVED,

    /** The files were already gone; only bookkeeping was left to clean up. */
    ALREADY_GONE,
}

/**
 * One installed pack, as the installer just left it.
 *
 * @property locale the identifier it installed under — for a variant, the variant's own
 *   identifier (`de_CH`), which is its tag within the group rather than a locale the engine knows.
 * @property group the base language it loads in place of, or `null` for an ordinary pack.
 * @property directory the `nuance/` directory the engine will read: the pack's own for an
 *   ordinary pack, the base language's for a variant.
 * @property offeredSubtype whether a runtime subtype had to be added for this language, which is
 *   what makes "enable it in Settings > Languages" worth saying.
 * @property selectable whether the language can be selected at all — `false` for the handful of
 *   languages with no keyboard layout in the tree, where the pack installs and sits unused.
 */
data class InstalledPack(
    val locale: String,
    val displayName: String,
    val group: String?,
    val directory: File,
    val offeredSubtype: Boolean,
    val selectable: Boolean,
) {
    val isVariant: Boolean get() = group != null
}

/**
 * An install that did not happen. Nothing was left on disk and nothing was registered.
 *
 * An [IOException] for the same reason [dev.bbkb.ime.core.distribution.DistributionException] is:
 * every way this can fail is I/O-shaped, and a caller that already handles `IOException` needs no
 * new arm. It is deliberately *not* a `DistributionException` — that type is the transport
 * layer's, and this failure happens after every byte has arrived and been verified.
 */
class PackInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)
