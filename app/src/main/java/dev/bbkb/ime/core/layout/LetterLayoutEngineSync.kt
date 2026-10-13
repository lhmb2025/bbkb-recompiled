package dev.bbkb.ime.core.layout

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.blackberry.nuanceshim.Xt9KdbVariant
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.layout.LetterLayoutKdb.Template
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import java.io.IOException

/**
 * Keeps the engine's key letters in step with the active letter layout, so tap correction and
 * swipe typing on the physical keypad read the letters the keys actually type.
 *
 * While the active layout ([LetterLayoutRegistry.activeForTyping]) drives the physical keys of the
 * current keyboard — it targets the physical keyboard, its binding matches the keyboard's language
 * and this phone's keypad — and changes at least one letter key against the engine's own letters
 * for that language, every physical-keyboard KDB the engine could load is rewritten from it
 * ([LetterLayoutKdb]) and registered under the engine variant `layout:<id>`, which is then
 * selected. Otherwise the device variant ([Xt9KdbVariant.deviceVariant], `athena` or the root
 * layouts) is selected again. The rewritten files start from the device variant's copy of each
 * file where it has one and the root copy otherwise, so a layout changes letters and never the
 * key geometry the decoder is tuned to.
 *
 * The decision is taken again whenever its inputs may have changed: the IME starting ([start]),
 * the registry saving, deleting or switching layouts ([onActiveLayoutChanged]), the device variant
 * changing (the variant's own listener), and every physical-keyboard layout sync, which is where a
 * change of keyboard language shows ([beforeEngineLayoutSync]). The work — reading the KDB assets,
 * rewriting, registering, selecting — runs on [Xt9KdbVariant]'s registration thread, the one
 * `NuanceSDK.syncKeyboardLayout` waits for through `Xt9KdbVariant.awaitReady` before it loads a
 * layout, so the engine's next load always sees the outcome. A sync whose inputs have not changed
 * queues nothing.
 *
 * A layout's KDBs are built once per (layout content, device variant) and kept registered, so
 * switching keyboards back and forth only switches the selection. They cover every language: the
 * selection, not the files, is what follows the binding.
 *
 * Inert without the owned KDB module (stock and DIFF builds select nothing; see [Xt9KdbVariant]).
 */
class LetterLayoutEngineSync @VisibleForTesting internal constructor(
    private val engine: Engine,
    private val kdbFiles: KdbFiles,
    /** The active layout's id as stored, read cheaply on every sync to notice a change. */
    private val activeId: () -> String,
    /** The active layout itself, parsed (a file read after a change). */
    private val activeLayout: () -> LetterLayout?,
    /** The current keyboard's language in the app's spelling (`iw`, not `he`), or null. */
    private val currentLanguage: () -> String?,
    /** This phone's keypad shape (`qwerty`/`qwertz`/`azerty`), or null when unknown. */
    private val keypad: () -> String?,
    /** This phone's keypad type (`3row`, …), or null when unknown. */
    private val keypadType: () -> String?,
    /** The current keyboard's layout set (`translit`, `qwerty`, …), or null when unknown. */
    private val layoutSet: () -> String? = { null },
    /** The letter the engine has on each letter key for a language: what a layout "changes". */
    private val engineLetters: (language: String?) -> (keyCode: Int) -> Int,
) {

    /** What the sync needs from the engine's KDB registry; [Xt9KdbVariant] in the app. */
    interface Engine {
        /** False where there is no owned KDB module: nothing to keep in step. */
        val isAvailable: Boolean

        /** The device variant the engine falls back to: `""` for the root layouts, null before it is known. */
        fun deviceVariant(): String?

        fun register(variant: String, xml: ByteArray): Boolean

        fun select(variant: String)

        /** Runs [job] on the registration thread, where layout syncs wait for it. */
        fun submit(job: Runnable)
    }

    /** The engine's KDB files: `kdb/` for variant `""`, `kdb/<variant>/` otherwise. */
    interface KdbFiles {
        /** The names of the files of [template] in [variant]'s folder; empty when it has none. */
        fun list(variant: String, template: Template): List<String>

        fun read(variant: String, name: String): ByteArray?
    }

    /**
     * The inputs a queued decision was taken for: an equal one queues nothing. The layout set is
     * part of it because both a user layout's binding and the engine's own letters can depend on
     * it: switching between two keyboards of one language (Russian and its transliteration) must
     * make the engine decide again.
     */
    private data class Request(val language: String?, val activeId: String, val layoutSet: String?, val changes: Int)

    /** What a layout variant's registered files were built from. */
    private data class Built(val content: String, val deviceVariant: String)

    private val lock = Any()

    /** Bumped by every change [Request] cannot see for itself; guarded by [lock]. */
    private var changes = 0

    /** Guarded by [lock]. */
    private var lastRequest: Request? = null

    /** By variant name; touched on the registration thread only. */
    private val built = HashMap<String, Built>()

    /** A physical-keyboard layout sync is about to load a KDB: decide again if anything changed. */
    fun onLayoutSync() = request(currentLanguageSafely())

    /** The active layout, or what it says, may have changed: decide again. */
    fun onLayoutsChanged() {
        synchronized(lock) { changes++ }
        request(currentLanguageSafely())
    }

    /**
     * The device variant changed: every built variant came from the old one's files. Runs on the
     * registration thread (the variant's listener), so it decides right here.
     */
    fun onDeviceVariantChanged() {
        built.clear()
        val language = synchronized(lock) {
            changes++
            lastRequest = null
            currentLanguageSafely()
        }
        decide(language)
    }

    private fun request(language: String?) {
        if (!engine.isAvailable) return
        val id = try { activeId() } catch (e: Exception) { "" }
        val set = try { layoutSet() } catch (e: Exception) { null }
        synchronized(lock) {
            val request = Request(language, id, set, changes)
            if (request == lastRequest) return
            lastRequest = request
            // Queued under the lock, so the job queued last is the one for the newest request.
            engine.submit { decide(language) }
        }
    }

    /** Selects the variant the engine should load from now. On the registration thread. */
    @VisibleForTesting
    internal fun decide(language: String?) {
        if (!engine.isAvailable) return
        // Before the device profile has applied its variant there is nothing to build on; the
        // variant's listener decides again once it has, and so does the next sync, which the
        // forgotten request no longer matches.
        val device = engine.deviceVariant() ?: run {
            synchronized(lock) { lastRequest = null }
            return
        }
        val layout = try { wanted(language) } catch (e: Exception) {
            Log.w(TAG, "layout: could not read the active layout: ${e.message}")
            null
        }
        if (layout == null) {
            engine.select(device)
            return
        }
        val name = LetterLayoutKdb.variantName(layout, TEMPLATE)
        val content = LetterLayoutXml.serialize(layout)
        if (built[name] != Built(content, device)) {
            built.remove(name)
            val registered = build(layout, name, device)
            if (registered == 0) {
                Log.w(TAG, "layout '$name': no KDB registered, keeping '${device.ifEmpty { "(default)" }}'")
                engine.select(device)
                return
            }
            built[name] = Built(content, device)
        }
        engine.select(name)
    }

    /**
     * The active layout when the engine should follow it for [language], else null: it must
     * target the physical keyboard, apply to [language] on this keypad, and change a letter key.
     */
    private fun wanted(language: String?): LetterLayout? {
        val layout = activeLayout() ?: return null
        if (LetterLayout.Target.PKB !in layout.targets) return null
        // The device profile is asked only for what the binding names; unknown is not held against it.
        val keypad = if (layout.binding.keypad == LetterLayout.ANY) null else keypad()
        val keypadType = if (layout.binding.keypadType == LetterLayout.ANY) null else keypadType()
        val layoutSet = if (layout.binding.layoutSet == LetterLayout.ANY) null else layoutSet()
        if (!layout.appliesTo(language, keypad, keypadType, layoutSet)) return null
        if (!layout.changesLetters(engineLetters(language))) return null
        return layout
    }

    /**
     * Rewrites every KDB of [TEMPLATE] the engine could load — [device]'s copy where it has one,
     * the root's otherwise — and registers each under [name], unchanged files included: the engine
     * falls back to the ROOT copy, not the device variant's, for a file a selected variant lacks.
     * Returns how many were registered.
     */
    private fun build(layout: LetterLayout, name: String, device: String): Int {
        val fromDevice = if (device.isEmpty()) emptySet() else kdbFiles.list(device, TEMPLATE).toSet()
        val files = (kdbFiles.list("", TEMPLATE) + fromDevice).toSortedSet()
        var registered = 0
        var rewritten = 0
        for (file in files) {
            val folder = if (file in fromDevice) device else ""
            val original = kdbFiles.read(folder, file) ?: continue
            val bytes = try {
                val text = String(original, Charsets.UTF_8)
                val out = LetterLayoutKdb.rewrite(text, layout, TEMPLATE)
                if (out === text) original else out.toByteArray(Charsets.UTF_8).also { rewritten++ }
            } catch (e: RuntimeException) {
                Log.w(TAG, "layout '$name': could not rewrite $file, registering it unchanged: ${e.message}")
                original
            }
            if (engine.register(name, bytes)) registered++
            else Log.w(TAG, "layout '$name': engine refused $file")
        }
        Log.i(
            TAG,
            "layout '$name': registered $registered of ${files.size} KDB(s), $rewritten rewritten, " +
                "from '${device.ifEmpty { "(default)" }}'",
        )
        return registered
    }

    private fun currentLanguageSafely(): String? = try { currentLanguage() } catch (e: Exception) { null }

    companion object {
        private const val TAG = "XT9KDB"

        /** The engine's physical-keyboard KDBs: the only ones a letter layout rewrites so far. */
        private val TEMPLATE = Template.PKB

        @Volatile
        private var instance: LetterLayoutEngineSync? = null

        /**
         * Starts keeping the engine in step, from the IME's start-up: takes the first decision, and
         * listens for the device profile applying its KDB variant, which decides again on top of
         * it. Calling it again only decides again.
         */
        @JvmStatic
        fun start(context: Context) {
            val sync = instance ?: synchronized(this) {
                instance ?: forApp(context.applicationContext).also { created ->
                    instance = created
                    Xt9KdbVariant.setDeviceVariantListener { created.onDeviceVariantChanged() }
                }
            }
            sync.onLayoutsChanged()
        }

        /** From [LetterLayoutRegistry.invalidate]: a layout was saved, deleted or switched. */
        @JvmStatic
        fun onActiveLayoutChanged() {
            instance?.onLayoutsChanged()
        }

        /**
         * From `NuanceSDK.syncKeyboardLayout`, just before it waits for the registration thread
         * and loads a KDB: a change of keyboard language shows here first. [isPkb] is the engine's
         * physical-keyboard flag; an on-screen layout load is not affected by variants.
         */
        @JvmStatic
        fun beforeEngineLayoutSync(isPkb: Boolean) {
            if (isPkb) instance?.onLayoutSync()
        }

        /** Replaces (or, with null, removes) the process's instance. Tests only. */
        @VisibleForTesting
        @JvmStatic
        internal fun setInstanceForTest(sync: LetterLayoutEngineSync?) {
            instance = sync
            Xt9KdbVariant.setDeviceVariantListener(sync?.let { s -> Runnable { s.onDeviceVariantChanged() } })
        }

        private fun forApp(app: Context): LetterLayoutEngineSync {
            val keypad = { DeviceProfile.current()?.keypadLayout }
            val keypadType = { DeviceProfile.current()?.effectiveKeypadType }
            val layoutSet = {
                SubtypeManager.getInstance()?.currentSubtype?.let { ResourceLocaleUtils.getKeyboardLayoutSetName(it) }
            }
            // The built-in documents are parsed once per process whichever registry asks.
            val builtIns by lazy { LetterLayoutRegistry(app) }
            return LetterLayoutEngineSync(
                engine = XtEngine,
                kdbFiles = AssetKdbFiles(app.assets),
                activeId = {
                    PrefsManager.getPrefs(app).getString(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT, "") ?: ""
                },
                activeLayout = { LetterLayoutRegistry.activeForTyping(app) },
                currentLanguage = {
                    SubtypeManager.getInstance()?.currentSubtypeLocale?.let { LocaleUtils.languageCode(it) }
                },
                keypad = keypad,
                keypadType = keypadType,
                layoutSet = layoutSet,
                engineLetters = { language -> engineLettersFor(builtIns, language, keypad(), keypadType(), layoutSet()) },
            )
        }

        /**
         * The engine's own letter on each letter key for a keyboard in [language] on this [keypad],
         * [keypadType] and [layoutSet]: the letter the built-in document the registry selects for
         * that keyboard puts on the key, else the key's Latin letter. The documents are generated
         * from the engine's KDBs, and `LetterLayoutsMatchEngineTest` holds them to the same letters,
         * so this is the script letter on an Arabic, Cyrillic, Hebrew, Greek or Korean keyboard and
         * the QWERTY letter everywhere else, as every Latin KDB has it. Not gated on the "active
         * language's alphabet" switch: the engine loads the script KDB either way.
         */
        @VisibleForTesting
        @JvmStatic
        internal fun engineLettersFor(
            builtIns: LetterLayoutRegistry,
            language: String?,
            keypad: String?,
            keypadType: String?,
            layoutSet: String?,
        ): (Int) -> Int {
            val document = builtIns.builtIn(language, keypad, keypadType, layoutSet)
            return { keyCode -> document?.keys?.get(keyCode)?.keySpec ?: latinLetter(keyCode) }
        }

        @JvmStatic
        internal fun latinLetter(keyCode: Int): Int = 'a'.code + (keyCode - android.view.KeyEvent.KEYCODE_A)
    }

    /** [Engine] over [Xt9KdbVariant]. */
    @VisibleForTesting
    internal object XtEngine : Engine {
        override val isAvailable: Boolean get() = Xt9KdbVariant.isAvailable()
        override fun deviceVariant(): String? = Xt9KdbVariant.deviceVariant()
        override fun register(variant: String, xml: ByteArray): Boolean = Xt9KdbVariant.registerGenerated(variant, xml)
        override fun select(variant: String) = Xt9KdbVariant.selectVariant(variant)
        override fun submit(job: Runnable) = Xt9KdbVariant.submit(job)
    }

    /** [KdbFiles] over the app's assets. */
    @VisibleForTesting
    internal class AssetKdbFiles(private val assets: AssetManager) : KdbFiles {
        override fun list(variant: String, template: Template): List<String> = try {
            // The folder's own files only: a phone lists a sub-folder by its name, which the suffix
            // already excludes, but some asset managers (Robolectric's) list its files as "athena/…".
            assets.list(folder(variant)).orEmpty().filter { '/' !in it && it.endsWith(template.fileSuffix) }
        } catch (e: IOException) {
            emptyList()
        }

        override fun read(variant: String, name: String): ByteArray? = try {
            assets.open("${folder(variant)}/$name").use { it.readBytes() }
        } catch (e: IOException) {
            Log.w(TAG, "layout: cannot read KDB ${folder(variant)}/$name: ${e.message}")
            null
        }

        private fun folder(variant: String) = if (variant.isEmpty()) "kdb" else "kdb/$variant"
    }
}
