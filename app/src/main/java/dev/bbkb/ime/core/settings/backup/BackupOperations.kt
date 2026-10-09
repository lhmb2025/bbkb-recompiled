package dev.bbkb.ime.core.settings.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.keyevent.UserLetterMapRepository
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.personaldictionary.macro.CustomMacroRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Backing up, restoring and resetting the three kinds of data ([BackupBundle.Part]) on a phone:
 * the one place that knows where each lives and in what order it has to be put back.
 *
 * [BackupBundle] owns the file; this object owns the phone. Every function here is a suspend
 * function that does its file and dictionary work on the IO dispatcher and its keyboard-cache
 * work on the main thread, so the Backup and restore screen, the phone-backup agent and the
 * first-run restore all go through the same steps.
 *
 * ## Restore order
 *
 * Device profiles and letter maps (files) first, so a failure there leaves the preferences
 * untouched; then settings and macros; then the layout editors on the main thread, because
 * writing them clears `KeyboardBuilder`'s cache, which the keyboard reads on the main thread;
 * then the dictionary through [DictionaryRepository] (never by copying its files, which the
 * system-dictionary sync would undo); and the learned words last, through the engine's own
 * learn call, only when the engine is up.
 *
 * Settings, macros and layouts replace: the phone ends up with exactly what the backup holds
 * (a setting the backup does not name goes back to its default; [SettingsBackup.apply] with
 * `replace`). Dictionary entries and learned words are added, never removed: a word is never
 * worth losing to a restore.
 */
object BackupOperations {

    private const val TAG = "BackupOperations"

    /** `files/device_configs`: `CustomDeviceConfigManager`'s folder of imported profiles. */
    private const val DEVICE_CONFIG_DIR = "device_configs"

    /** What a restore did, for the summary shown afterwards. */
    class RestoreReport {
        var settings: Int = 0
        var macros: Int = 0
        var deviceConfigs: Int = 0
        var layoutSections: Int = 0
        var letterMaps: Int = 0
        var dictionaryAdded: Int = 0
        var learnedAdded: Int = 0
        /** The bundle carried learned words but the engine was not running to take them. */
        var learnedSkipped: Boolean = false

        val restoredSettings: Boolean get() = settings > 0 || macros > 0 || deviceConfigs > 0
        val restoredLayouts: Boolean get() = layoutSections > 0 || letterMaps > 0
        val restoredWords: Boolean get() = dictionaryAdded > 0 || learnedAdded > 0
    }

    /** How reading a picked file went. */
    sealed interface ReadResult {
        class Ok(val bundle: BackupBundle.Bundle) : ReadResult
        /** The file was read but is not a backup; [message] says why. */
        class NotABackup(val message: String) : ReadResult
        /** The storage layer would not hand over the bytes. */
        object Unreadable : ReadResult
    }

    // ── capture ──────────────────────────────────────────────────────────────

    /**
     * The selected parts of the phone's current data, serialised. Learned words are included
     * only when the engine is running with its model loaded (the keyboard has been used in this
     * process); otherwise the words part carries the dictionary alone, and a restore of it leaves
     * the learned words as they are.
     */
    suspend fun capture(context: Context, selection: BackupBundle.Selection): BackupBundle.Contents =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val prefs = PrefsManager.getPrefs(app)
            val settingsJson = if (selection.settings) {
                SettingsBackup.serialize(prefs, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                    excludeKeys = LayoutsBundle.PREF_KEYS)
            } else null
            val macrosJson = if (selection.settings) {
                CustomMacroRepository(app).getAllMacros().takeIf { it.isNotEmpty() }?.let { MacrosBundle.serialize(it) }
            } else null
            val deviceConfigs = if (selection.settings) captureDeviceConfigs(app) else emptyList()
            val layoutsJson = if (selection.layouts) {
                val layouts = LayoutsBundle.capture(app, UserLetterMapRepository(app).list())
                LayoutsBundle.serialize(layouts, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            } else null
            val wordsJson = if (selection.words) {
                val words = WordsBundle.Words(
                    dictionary = dictionaryEntries(app),
                    learnedWords = learnedWords(),
                )
                WordsBundle.serialize(words, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            } else null
            BackupBundle.Contents(settingsJson, macrosJson, deviceConfigs, layoutsJson, wordsJson)
        }

    private fun captureDeviceConfigs(context: Context): List<BackupBundle.DeviceConfigFile> {
        val dir = File(context.filesDir, DEVICE_CONFIG_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".xml") } ?: return emptyList()
        return files.sortedBy { it.name }
            .filter { it.length() <= BackupBundle.MAX_DEVICE_CONFIG_BYTES }
            .take(BackupBundle.MAX_DEVICE_CONFIGS)
            .mapNotNull { file ->
                try {
                    BackupBundle.DeviceConfigFile(file.name, file.readBytes())
                } catch (e: IOException) {
                    Logger.warn(TAG, "skipping device profile ${file.name}: $e")
                    null
                }
            }
    }

    /** Every dictionary entry on the phone, across locales, each once. */
    private suspend fun dictionaryEntries(context: Context): List<DictionaryEntry> {
        val repository = DictionaryRepository(context)
        repository.initialize()
        if (!repository.isLoaded()) return emptyList()
        val locales = LinkedHashSet(repository.getLocales()).apply { add("") }
        val seen = HashSet<String>()
        val entries = ArrayList<DictionaryEntry>()
        for (locale in locales) {
            repository.getAllEntries(locale).forEach { entry ->
                if (seen.add(entryKey(entry))) entries += entry.copy(id = null)
            }
        }
        return entries
    }

    private fun entryKey(entry: DictionaryEntry): String = entry.locale + "\u0000" + entry.displayKey

    /** The engine's learned words, or null when the engine is not running with its model. */
    private fun learnedWords(): List<String>? {
        if (!NuanceSDKManager.isDLMReady()) return null
        val sdk = NuanceSDKManager.getInstance() ?: return null
        return try {
            sdk.getDLMWords().filter { it.isNotEmpty() && it.length <= WordsBundle.MAX_WORD_LENGTH }
        } catch (e: RuntimeException) {
            Logger.warn(TAG, "learned words unavailable: $e")
            null
        }
    }

    /** The phone this bundle is written on, for the restore summary on another one. */
    fun deviceInfo(): BackupBundle.DeviceInfo = BackupBundle.DeviceInfo(Build.MANUFACTURER, Build.MODEL)

    // ── files ────────────────────────────────────────────────────────────────

    /** Writes the selected parts to [uri] (a document the user chose). False if storage refused. */
    suspend fun writeTo(context: Context, uri: Uri, selection: BackupBundle.Selection): Boolean {
        val contents = capture(context, selection)
        return withContext(Dispatchers.IO) {
            try {
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("no output stream for $uri")
                writeTo(stream, contents)
                true
            } catch (e: IOException) {
                Logger.warn(TAG, "backup not written: $e")
                false
            } catch (e: SecurityException) {
                Logger.warn(TAG, "backup not written: $e")
                false
            }
        }
    }

    /** Writes [contents] to [file] atomically: a temp file beside it, renamed into place. */
    @Throws(IOException::class)
    fun writeTo(file: File, contents: BackupBundle.Contents) {
        val dir = file.parentFile ?: throw IOException("no parent for $file")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        val temp = File(dir, file.name + ".tmp")
        try {
            writeTo(FileOutputStream(temp), contents)
            if (!temp.renameTo(file)) throw IOException("could not move $temp into place")
        } finally {
            temp.delete()
        }
    }

    @Throws(IOException::class)
    private fun writeTo(stream: OutputStream, contents: BackupBundle.Contents) {
        BackupBundle.write(stream, contents, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, deviceInfo())
    }

    /** Reads and validates the document at [uri]; nothing is applied. */
    suspend fun readFrom(context: Context, uri: Uri): ReadResult = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("no input stream for $uri")
            stream.use { readFrom(it) }
        } catch (e: IOException) {
            Logger.warn(TAG, "backup not read: $e")
            ReadResult.Unreadable
        } catch (e: SecurityException) {
            Logger.warn(TAG, "backup not read: $e")
            ReadResult.Unreadable
        }
    }

    @Throws(IOException::class)
    fun readFrom(stream: InputStream): ReadResult {
        val result = BackupBundle.read(stream)
        val bundle = result.getOrNull() ?: return ReadResult.NotABackup(result.exceptionOrNull()?.message ?: "not a BBKB backup")
        return ReadResult.Ok(bundle)
    }

    // ── restore ──────────────────────────────────────────────────────────────

    /**
     * Applies the parts of [bundle] that [selection] asks for and the bundle has. Safe to call
     * with parts the bundle lacks: they count as nothing restored.
     */
    suspend fun restore(context: Context, bundle: BackupBundle.Bundle, selection: BackupBundle.Selection): RestoreReport {
        val app = context.applicationContext
        val report = RestoreReport()
        val wanted = selection.limitedTo(bundle)

        withContext(Dispatchers.IO) {
            if (wanted.settings) {
                report.deviceConfigs = restoreDeviceConfigs(app, bundle.deviceConfigs)
            }
            if (wanted.layouts) {
                bundle.layouts?.let { layouts ->
                    LayoutsBundle.saveLetterMaps(UserLetterMapRepository(app), layouts.pkbLetterMaps)
                    report.letterMaps = layouts.pkbLetterMaps.size
                }
            }
            if (wanted.settings) {
                bundle.settings?.let {
                    // Replace: the phone ends up with exactly the backup's settings. The layout
                    // keys are kept because they are the Layouts part's, restored or not above.
                    report.settings = SettingsBackup.apply(PrefsManager.getPrefs(app), it,
                        replace = true, keepKeys = LayoutsBundle.PREF_KEYS)
                }
                bundle.macros?.let {
                    CustomMacroRepository(app).replaceAll(it)
                    report.macros = it.size
                }
            }
        }
        if (wanted.layouts) {
            bundle.layouts?.let { layouts ->
                withContext(Dispatchers.Main) { report.layoutSections = LayoutsBundle.applyEditors(app, layouts) }
            }
        }
        if (wanted.words) {
            bundle.words?.let { words ->
                withContext(Dispatchers.IO) {
                    words.dictionary?.let { report.dictionaryAdded = restoreDictionary(app, it) }
                    words.learnedWords?.let { learned ->
                        val added = restoreLearnedWords(learned)
                        if (added == null) report.learnedSkipped = true else report.learnedAdded = added
                    }
                }
            }
        }
        AutoBackup.noteDataChanged(app)
        return report
    }

    /** Writes each profile into the imported-profiles folder, replacing a file of the same name. */
    private fun restoreDeviceConfigs(context: Context, configs: List<BackupBundle.DeviceConfigFile>): Int {
        if (configs.isEmpty()) return 0
        val dir = File(context.filesDir, DEVICE_CONFIG_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Logger.warn(TAG, "could not create $dir")
            return 0
        }
        var written = 0
        configs.forEach { config ->
            // The bundle reader already pinned the name to a plain file name. Parse before
            // writing, so an XML that is not a device profile does not land in the folder the
            // profile loader scans.
            if (DeviceInputMappingParser.parseConfigFromStream(ByteArrayInputStream(config.xml)) == null) {
                Logger.warn(TAG, "skipping device profile ${config.name}: not a device config")
                return@forEach
            }
            try {
                File(dir, config.name).writeBytes(config.xml)
                written++
            } catch (e: IOException) {
                Logger.warn(TAG, "device profile ${config.name} not written: $e")
            }
        }
        return written
    }

    /** Adds the entries the phone does not already have. */
    private suspend fun restoreDictionary(context: Context, entries: List<DictionaryEntry>): Int {
        val repository = DictionaryRepository(context)
        repository.initialize()
        if (!repository.isLoaded()) {
            Logger.warn(TAG, "dictionary not loaded; its words were not restored")
            return 0
        }
        val existing = HashSet<String>()
        val locales = LinkedHashSet(repository.getLocales()).apply { add("") }
        for (locale in locales) {
            repository.getAllEntries(locale).forEach { existing += entryKey(it) }
        }
        var added = 0
        entries.forEach { entry ->
            if (entryKey(entry) in existing) return@forEach
            if (repository.addEntry(entry).isSuccess) added++
        }
        return added
    }

    /** Teaches the engine each word; null when the engine is not running with its model. */
    private fun restoreLearnedWords(words: List<String>): Int? {
        if (!NuanceSDKManager.isDLMReady()) return null
        val sdk = NuanceSDKManager.getInstance() ?: return null
        val known = try {
            sdk.getDLMWords().toHashSet()
        } catch (e: RuntimeException) {
            HashSet<String>()
        }
        var added = 0
        words.forEach { word ->
            if (word in known) return@forEach
            if (sdk.addWord(word)) added++
        }
        return added
    }

    // ── reset ────────────────────────────────────────────────────────────────

    /**
     * Puts the selected parts back to a fresh install's state. Settings: every preference that is
     * not a layout key, plus the macros. Layouts: the editor sections and every letter map. Words:
     * the whole dictionary and the engine's learned-word model.
     */
    suspend fun reset(context: Context, selection: BackupBundle.Selection) {
        val app = context.applicationContext
        if (selection.settings) {
            withContext(Dispatchers.IO) {
                val prefs = PrefsManager.getPrefs(app)
                val editor = prefs.edit()
                prefs.all.keys.forEach { key -> if (key !in LayoutsBundle.PREF_KEYS) editor.remove(key) }
                editor.apply()
                CustomMacroRepository(app).replaceAll(emptyList())
            }
            // What DebugSettingsUtils.clearAllSettings did after its clear: the subtype list is
            // rebuilt from the phone's languages so the keyboard is not left with none.
            SubtypeManager.ensureInitialized(app)
        }
        if (selection.layouts) {
            withContext(Dispatchers.IO) {
                val repository = UserLetterMapRepository(app)
                repository.list().forEach { repository.delete(it.id) }
                repository.activeId = ""
            }
            withContext(Dispatchers.Main) { LayoutsBundle.resetEditors(app) }
        }
        if (selection.words) {
            withContext(Dispatchers.IO) {
                val repository = DictionaryRepository(app)
                repository.initialize()
                if (repository.isLoaded()) {
                    val locales = LinkedHashSet(repository.getLocales()).apply { add("") }
                    for (locale in locales) {
                        repository.getAllEntries(locale).forEach { repository.deleteEntry(it) }
                    }
                }
                NuanceSDKManager.getInstance()?.let { sdk ->
                    try {
                        sdk.resetDynamicModel(File(app.filesDir, "nuance"))
                    } catch (e: RuntimeException) {
                        Logger.warn(TAG, "learned words not reset: $e")
                    }
                }
            }
        }
        AutoBackup.noteDataChanged(app)
    }
}
