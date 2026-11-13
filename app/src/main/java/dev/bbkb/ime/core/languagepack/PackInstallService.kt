package dev.bbkb.ime.core.languagepack

import android.content.Context
import com.blackberry.nuanceshim.languagepack.CustomPackRegistryStore
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import com.blackberry.nuanceshim.languagepack.LanguageVariantStore
import dev.bbkb.ime.core.engine.EngineThreads
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.locale.SubtypeEnabler
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
 * The one path that installs a `.ldb`: copies it to `no_backup/nuance/<locale>/<locale>.ldb` plus
 * `version.txt` (both required to count as installed) and adds the `files/nuance_custom_packs.json`
 * registry entry (required to be supported). The running engine enumerates packs only at start, so
 * [EngineRegistry] hands it new directories. Regional variants (de_CH, fr_CH, it_CH, nl_BE) go
 * through [LanguageVariantStore]. The locale always comes from the caller (the manifest for
 * downloads), never the file name, which would misplace `es_419`, `zh_TW`, `zh_HK`, `en_ZH`.
 * The source file is copied, not moved; a failed install leaves nothing behind.
 */
class PackInstallService @JvmOverloads constructor(
    private val context: Context,
    private val subtypes: SubtypeRegistrar = FrameworkSubtypeRegistrar,
    private val engine: EngineRegistry = NuanceEngineRegistry,
    private val events: (String) -> Unit = { action ->
        InAppEventBus.getInstance().post(action, null)
    },
) {

    /**
     * @param locale install id (`language` or `language_COUNTRY`); for downloads `PackEntry.locale`
     * @param version written to `version.txt`; a marker, not compared, but must parse as a positive
     *   number or [FALLBACK_VERSION] is used
     * @param group base language a regional variant loads in place of, else `null`
     * @param turnOn false for a dictionary fetched only as an extra prediction language
     */
    suspend fun installFromFile(
        file: File,
        locale: String,
        displayName: String,
        version: String,
        group: String?,
        turnOn: Boolean = true,
    ): Result<InstalledPack> = withContext(Dispatchers.IO) {
        try {
            Result.success(install(file, locale, displayName, version, group, turnOn))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PackInstallException) {
            Result.failure(failure)
        } catch (failure: IOException) {
            Result.failure(PackInstallException("Could not install $locale", failure))
        }
    }

    /**
     * Idempotent: the screen works from a disk snapshot, so the files may already be gone, which
     * returns [UninstallOutcome.ALREADY_GONE] after cleaning bookkeeping. Variants are removed
     * through [LanguageVariantStore], not here.
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
        CustomPackRegistryStore.remove(context, language, country)
        subtypes.withdrawSubtypeFor(context, language)
        engine.deregister(language, country)
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
        turnOn: Boolean,
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
            installVariant(file, locale, name, group, turnOn)
        } else {
            installBase(file, locale, name, versionValue, turnOn)
        }
    }

    /** No registry entry: a variant loads as its base language, which the shipped registry knows. */
    private fun installVariant(
        file: File,
        locale: String,
        displayName: String,
        group: String,
        turnOn: Boolean,
    ): InstalledPack {
        if (!LanguageVariantStore.installVariant(context, group, locale, displayName, file)) {
            throw PackInstallException("Could not install $locale as a variant of $group")
        }
        LanguagePackManager.getInstance(context).reloadRegistry()
        val enabled = turnOn && subtypes.enableSubtypeFor(context, group)
        events(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED)
        Logger.info(TAG, "Installed $locale as an active variant of $group (enabled=$enabled)")
        return InstalledPack(
            locale = locale,
            displayName = displayName,
            group = group,
            directory = packDirectory(group),
            offeredSubtype = false,
            // The base language is shipped in all four cases, so it has a subtype.
            selectable = true,
            enabled = enabled,
        )
    }

    private fun installBase(
        file: File,
        locale: String,
        displayName: String,
        version: Double,
        turnOn: Boolean,
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
            // getInstalledLocales() needs both files; isInstalled() parses this one's trailing digits.
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
            // An unregistered directory is unsupported, so roll it back.
            if (!existed) directory.deleteRecursively()
            throw PackInstallException("Could not register $locale; nothing was installed")
        }

        LanguagePackManager.getInstance(context).reloadRegistry()
        if (!engine.register(directory)) {
            // Not fatal: the next engine start finds the files.
            Logger.warn(TAG, "The running engine did not accept $locale; it loads on the next restart")
        }

        // No method.xml subtype means the pack could never be selected, so offer one at runtime
        // (five languages have no layout, so this can fail).
        val builtIn = subtypes.hasBuiltInSubtypeFor(language)
        val offered = if (builtIn) false else subtypes.offerSubtypeFor(context, language)
        // The language must also be on in the language list; Android allows that on API 34+ only.
        val enabled = turnOn && (builtIn || offered) && subtypes.enableSubtypeFor(context, locale)

        events(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED)
        Logger.info(TAG, "Installed $locale (subtype: builtIn=$builtIn offered=$offered enabled=$enabled)")
        return InstalledPack(
            locale = locale,
            displayName = displayName,
            group = null,
            directory = directory,
            offeredSubtype = offered,
            selectable = builtIn || offered,
            enabled = enabled,
        )
    }

    private fun packDirectory(locale: String): File =
        File(context.noBackupFilesDir, "nuance/$locale")

    /** Interface so the install path is testable without the input-method framework. */
    interface SubtypeRegistrar {
        /** Whether `res/xml/method.xml` declares a subtype for this language. */
        fun hasBuiltInSubtypeFor(language: String): Boolean

        /** `false` when there is no usable layout. */
        fun offerSubtypeFor(context: Context, language: String): Boolean

        fun withdrawSubtypeFor(context: Context, language: String)

        /** Whether it is on afterwards; false before API 34, where a keyboard cannot do this. */
        fun enableSubtypeFor(context: Context, locale: String): Boolean
    }

    interface EngineRegistry {
        /** True when no live engine refused it. */
        fun register(directory: File): Boolean
        fun deregister(language: String, country: String?)
    }

    /**
     * Registers with whichever of the keyboard and spell-checker engines exist (an unstarted one
     * enumerates on its own). Runs on the engine loader thread and blocks the caller: Dispatchers.IO
     * threads can exit, so they must never call the engine directly.
     */
    object NuanceEngineRegistry : EngineRegistry {
        override fun register(directory: File): Boolean {
            val path = directory.absolutePath
            return onLoader {
                var accepted = true
                NuanceSDKManager.peekInstance()?.let { accepted = it.registerLdb(path) && accepted }
                NuanceSDKManager.peekSecondary()?.let { accepted = it.registerLdb(path) && accepted }
                accepted
            }
        }

        override fun deregister(language: String, country: String?) {
            onLoader {
                NuanceSDKManager.peekInstance()?.deregisterLdb(language, country)
                NuanceSDKManager.peekSecondary()?.deregisterLdb(language, country)
            }
        }

        private fun <T> onLoader(block: () -> T): T {
            EngineThreads.assertNotMain("PackInstallService.NuanceEngineRegistry")
            return try {
                EngineThreads.load(java.util.concurrent.Callable { block() }).get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    object FrameworkSubtypeRegistrar : SubtypeRegistrar {
        override fun hasBuiltInSubtypeFor(language: String): Boolean {
            // Exclude our own additional subtypes, or this flips true after install and delete
            // never withdraws the runtime entry.
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
                // Recorded in our preference; the framework picks it up on the next re-register.
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

        override fun enableSubtypeFor(context: Context, locale: String): Boolean =
            SubtypeEnabler.enable(context, locale).also { on ->
                // A keyboard the user just added: ask for its voice language too.
                if (on) dev.bbkb.ime.keyboard.inputboard.voice.VoiceLanguages.downloadForKeyboard(context, locale)
            }
    }

    companion object {
        private const val TAG = "PackInstall"

        /** Below this it is not a dictionary (guards a mis-picked file from the picker). */
        const val MIN_LDB_BYTES: Long = 1_000L

        /** Used when the caller has no version (the file picker). */
        const val FALLBACK_VERSION: Double = 1.0
    }
}

/** Both outcomes are success. */
enum class UninstallOutcome {
    REMOVED,

    /** Files were already gone; only bookkeeping was cleaned up. */
    ALREADY_GONE,
}

/**
 * One installed pack.
 *
 * @property locale for a variant, its own tag (`de_CH`), not a locale the engine knows
 * @property directory the directory the engine reads: the base language's for a variant
 * @property offeredSubtype a runtime subtype had to be added for this language
 * @property selectable `false` for languages with no keyboard layout (the pack sits unused)
 * @property enabled on in the language list now; `false` before Android 14 unless it already was
 */
data class InstalledPack(
    val locale: String,
    val displayName: String,
    val group: String?,
    val directory: File,
    val offeredSubtype: Boolean,
    val selectable: Boolean,
    val enabled: Boolean,
) {
    val isVariant: Boolean get() = group != null
}

/**
 * An install that did not happen (nothing left on disk or registered). Not a
 * `DistributionException`: that is the transport layer's, and this fails after verification.
 */
class PackInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)
