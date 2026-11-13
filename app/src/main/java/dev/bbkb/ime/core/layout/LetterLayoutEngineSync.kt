package dev.bbkb.ime.core.layout

import android.content.Context
import android.content.res.AssetManager
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.blackberry.nuanceshim.Xt9KdbVariant
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.layout.LetterLayoutKdb.Template
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.keyboard.DrawnKeyboards
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the engine's key letters in step with the letters the keys type, for tap correction and swipe.
 * Physical keypad: a chosen layout that fits and changes a letter registers rewritten KDBs as
 * `layout:<id>`; else the keyboard's built-in document as `layout:builtin:<id>`; only keyboards with no
 * built-in document select the device variant. Rewrites start from the device's keypad KDBs, so key
 * geometry never changes. On-screen: the drawn keyboard's letters as `layout:screen:<fingerprint>`
 * (Chinese stroke/zhuyin/Cangjie keep shipped files). Built KDBs are stored ([KdbStore]) and kept
 * registered, so a switch only selects. Work runs on [Xt9KdbVariant]'s registration thread, which
 * `NuanceSDK.syncKeyboardLayout` awaits before loading. Inert without the owned KDB module.
 */
class LetterLayoutEngineSync @VisibleForTesting internal constructor(
    private val engine: Engine,
    private val kdbFiles: KdbFiles,
    /** The current keyboard's language in the app's spelling (`iw`, not `he`), or null. */
    private val currentLanguage: () -> String?,
    /** This phone's keypad shape (`qwerty`/`qwertz`/`azerty`), or null when unknown. */
    private val keypad: () -> String?,
    /** This phone's keypad type (`3row`, …), or null when unknown. */
    private val keypadType: () -> String?,
    /** The current keyboard's layout set (`translit`, `qwerty`, …), or null when unknown. */
    private val layoutSet: () -> String? = { null },
    /** The engine's letter on each letter key for a language: what a layout "changes". */
    private val engineLetters: (language: String?) -> (keyCode: Int) -> Int,
    /** The language's stored physical choice, `""` for none; cheap. */
    private val chosenId: (language: String?) -> String = { "" },
    /** The layout chosen for a language's physical keys, whatever it is for. */
    private val chosenLayout: (language: String?) -> LetterLayout? = { null },
    /** The on-screen keyboard as drawn, asked on the sync's thread; null source means on-screen syncs are ignored. */
    private val onScreenKeyboard: (() -> DrawnKeyboard?)? = null,
    /**
     * The built-in document a language's keyboard types on the physical keys (the keypad shape's Latin one
     * when the keypad's letters were chosen), or null. Null source keeps the shipped KDB files.
     */
    private val builtInLayout: (language: String?) -> LetterLayout? = { null },
    /** The built-in document with an engine id's keypad KDB letters ([KeypadKdbs.SHIPPED]); else shipped files alone. */
    private val shippedLayout: (language: String, layoutSet: String?) -> LetterLayout? = { _, _ -> null },
    /** Where built KDBs are kept between processes; none keeps them in memory only. */
    private val store: KdbStore? = null,
) {
    /** [Xt9KdbVariant] in the app. */
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

    /** The engine's KDB files, under `assets/kdb/`. */
    interface KdbFiles {
        /** The names of the files of [template]; a device's keypad geometry ([KeypadKdbs.isTemplate]) among them. */
        fun list(template: Template): List<String>

        fun read(name: String): ByteArray?
    }

    /**
     * Inputs of a queued decision; an equal one queues nothing. Layout set: two keyboards of one language
     * (Russian, transliteration) must re-decide. [pkb]: both keyboards share one selected variant.
     */
    private data class Request(
        val pkb: Boolean,
        val language: String?,
        val chosenId: String,
        val onScreen: String,
        val layoutSet: String?,
        val changes: Int,
    )

    /** What a layout variant's registered files were built from. */
    private data class Built(val content: String, val deviceVariant: String)

    private val lock = Any()

    /** Bumped by every change [Request] cannot see for itself; guarded by [lock]. */
    private var changes = 0

    /** Guarded by [lock]. */
    private var lastRequest: Request? = null

    /** The keyboard the last sync was for, where a settings change is decided; guarded by [lock]. */
    private var lastPkb = true

    /** By variant name and keyboard; touched on the registration thread only. */
    private val built = HashMap<Pair<String, Template>, Built>()

    /** The on-screen variants registered so far; touched on the registration thread only. */
    private val builtOnScreen = HashSet<String>()

    /** A layout sync of the physical keypad ([pkb]) or the on-screen keyboard is about to load a KDB. */
    fun onLayoutSync(pkb: Boolean = true) {
        if (!pkb && onScreenKeyboard == null) return
        request(currentLanguageSafely(), pkb)
    }

    /** The active layout, a language's choice, or what one of them says, may have changed: decide again. */
    fun onLayoutsChanged() {
        val pkb = synchronized(lock) {
            changes++
            lastPkb
        }
        request(currentLanguageSafely(), pkb)
    }

    /** Every physical-keypad variant came from the old variant's files. Runs on the registration thread. */
    fun onDeviceVariantChanged() {
        built.keys.removeAll { it.second == Template.PKB }
        val (language, pkb) = synchronized(lock) {
            changes++
            lastRequest = null
            currentLanguageSafely() to lastPkb
        }
        // On-screen files never came from the device variant; the next on-screen sync decides for it.
        if (pkb) decide(language, pkb = true)
    }

    private fun request(language: String?, pkb: Boolean) {
        if (!engine.isAvailable) return
        val chosen = try { chosenId(language) } catch (e: Exception) { "" }
        // Read on the caller's (keyboard's) thread, not the registration thread.
        val drawn = if (pkb) null else try { onScreenKeyboard?.invoke() } catch (e: Exception) { null }
        val set = try { layoutSet() } catch (e: Exception) { null }
        synchronized(lock) {
            lastPkb = pkb
            val request = Request(pkb, language, chosen, drawn?.fingerprint ?: "", set, changes)
            if (request == lastRequest) return
            lastRequest = request
            // Queued under the lock, so the job queued last is the one for the newest request.
            engine.submit { decide(language, pkb, drawn) }
        }
    }

    /** Physical keypad only. On the registration thread. */
    @VisibleForTesting
    internal fun decide(language: String?) = decide(language, pkb = true)

    /** Selects the variant for the physical keypad ([pkb]) or [drawn]. On the registration thread. */
    @VisibleForTesting
    internal fun decide(language: String?, pkb: Boolean, drawn: DrawnKeyboard? = null) {
        if (!engine.isAvailable) return
        // No device variant yet: its listener and the next sync (forgotten request) decide again.
        val device = engine.deviceVariant() ?: run {
            synchronized(lock) { lastRequest = null }
            return
        }
        if (!pkb) {
            decideOnScreen(device, drawn)
            return
        }
        val template = Template.PKB
        val chosen = try {
            wanted(language)
        } catch (e: Exception) {
            Log.w(TAG, "layout: could not read the active layout: ${e.message}")
            null
        }
        val builtIn = if (chosen != null) null else try {
            builtInLayout(language)?.takeIf { LetterLayout.Target.PKB in it.targets }
        } catch (e: Exception) {
            Log.w(TAG, "layout: could not read the built-in layout for '$language': ${e.message}")
            null
        }
        val layout = chosen ?: builtIn
        if (layout == null) {
            engine.select(device)
            return
        }
        val name = if (chosen != null) LetterLayoutKdb.variantName(layout, template) else LetterLayoutKdb.builtInVariantName(layout)
        val content = LetterLayoutXml.serialize(layout)
        val from = device
        if (built[name to template] != Built(content, from)) {
            built.remove(name to template)
            val registered = build(layout, name, device)
            if (registered == 0) {
                Log.w(TAG, "layout '$name': no KDB registered, keeping '$device'")
                engine.select(device)
                return
            }
            built[name to template] = Built(content, from)
        }
        engine.select(name)
    }

    /** The on-screen half of [decide]: [drawn]'s KDBs, built or read back, else the device variant. */
    private fun decideOnScreen(device: String, drawn: DrawnKeyboard?) {
        if (drawn == null || drawn.layoutSet in OnScreenKdbs.KEPT_LAYOUT_SETS) {
            engine.select(device)
            return
        }
        val name = SCREEN_VARIANT_PREFIX + drawn.fingerprint
        if (name !in builtOnScreen) {
            val files = onScreenFiles(drawn)
            var registered = 0
            files?.forEach { if (engine.register(name, it)) registered++ }
            if (registered == 0) {
                Log.w(TAG, "layout '$name': no on-screen KDB for rows ${drawn.shape} ('${drawn.layoutSet}'), keeping '$device'")
                engine.select(device)
                return
            }
            builtOnScreen += name
        }
        engine.select(name)
    }

    /** The language's chosen layout if it is for the physical keys, fits, and changes a letter key; else null. */
    private fun wanted(language: String?): LetterLayout? {
        val layout = chosenLayout(language)?.takeIf { fitsPhysicalKeys(it, language) } ?: return null
        if (!layout.changesLetters(engineLetters(language))) return null
        return layout
    }

    /** Whether [layout] targets the physical keyboard and applies to [language] on this keypad. */
    private fun fitsPhysicalKeys(layout: LetterLayout, language: String?): Boolean {
        if (LetterLayout.Target.PKB !in layout.targets) return false
        // The device profile is asked only for what the binding names; unknown is not held against it.
        val keypad = if (layout.binding.keypad == LetterLayout.ANY) null else keypad()
        val keypadType = if (layout.binding.keypadType == LetterLayout.ANY) null else keypadType()
        val layoutSet = if (layout.binding.layoutSet == LetterLayout.ANY) null else layoutSet()
        return layout.appliesTo(language, keypad, keypadType, layoutSet)
    }

    /** [device]'s keypad KDBs by engine id ([KeypadKdbs.forDevice]); built once per process, from any thread. */
    private val deviceKdbs = ConcurrentHashMap<String, Map<Int, String>>()

    private fun deviceKdbs(device: String): Map<Int, String> = deviceKdbs.getOrPut(device) {
        val template = kdbFiles.read(KeypadKdbs.templateFile(device))
            ?: kdbFiles.read(KeypadKdbs.templateFile(KeypadKdbs.GENERIC))
            ?: return@getOrPut emptyMap()
        KeypadKdbs.forDevice(String(template, Charsets.UTF_8), shippedLayout)
    }

    /** The on-screen row-shape templates by file name; read once, from any thread. */
    private val screenTemplates: Map<String, String> by lazy {
        kdbFiles.list(Template.VKB).filter(OnScreenKdbs::isTemplate).sorted()
            .mapNotNull { name -> kdbFiles.read(name)?.let { name to String(it, Charsets.UTF_8) } }.toMap()
    }

    /** Registers every rewritten [Template.PKB] KDB under [name]; returns how many registered. */
    private fun build(layout: LetterLayout, name: String, device: String): Int {
        var registered = 0
        val files = keypadFiles(layout, device)
        for (bytes in files) {
            if (engine.register(name, bytes)) registered++
            else Log.w(TAG, "layout '$name': engine refused a KDB")
        }
        Log.i(TAG, "layout '$name': registered $registered of ${files.size} KDB(s), from '$device'")
        return registered
    }

    /**
     * [layout]'s rewrite of every keypad KDB the engine could load ([device]'s per id it builds, shipped
     * otherwise), unchanged ones included: for a missing id the engine falls back to the shipped root file.
     */
    internal fun keypadFiles(layout: LetterLayout, device: String): List<ByteArray> {
        val key = KdbStore.key("pkb", device, LetterLayoutXml.serialize(layout))
        store?.get(key)?.let { return it }
        val started = System.nanoTime()
        val bases = LinkedHashMap(deviceKdbs(device))
        for (file in kdbFiles.list(Template.PKB).sorted()) {
            if (KeypadKdbs.isTemplate(file)) continue
            val text = kdbFiles.read(file)?.toString(Charsets.UTF_8) ?: continue
            val id = LetterLayoutKdb.engineIds(text)?.first ?: continue
            bases.putIfAbsent(id, text)
        }
        val files = bases.map { (id, original) ->
            try {
                LetterLayoutKdb.rewrite(original, layout, Template.PKB).toByteArray(Charsets.UTF_8)
            } catch (e: RuntimeException) {
                Log.w(TAG, "layout '${layout.id}': could not rewrite the KDB of id $id, keeping it unchanged: ${e.message}")
                original.toByteArray(Charsets.UTF_8)
            }
        }
        store?.put(key, files)
        Log.i(TAG, "keypad KDBs for '${layout.id}' on '$device' built in ${(System.nanoTime() - started) / 1_000_000} ms")
        return files
    }

    /** [drawn]'s on-screen KDBs, from the [store] when built before; null when no shipped file has its shape. */
    internal fun onScreenFiles(drawn: DrawnKeyboard): List<ByteArray>? {
        val key = KdbStore.key("vkb", drawn.fingerprint)
        store?.get(key)?.let { return it }
        val started = System.nanoTime()
        val template = OnScreenKdbs.choose(screenTemplates, drawn) ?: return null
        val files = OnScreenKdbs.build(screenTemplates.getValue(template), drawn)?.map { it.toByteArray(Charsets.UTF_8) } ?: return null
        store?.put(key, files)
        Log.i(TAG, "on-screen KDBs for '${drawn.layoutSet}' ${drawn.shape} built on $template in ${(System.nanoTime() - started) / 1_000_000} ms")
        return files
    }

    /** Builds [layout]'s keypad KDBs into the [store] ahead of first use. Off the registration thread. */
    fun prebuildKeypad(layout: LetterLayout) {
        if (!engine.isAvailable || store == null || LetterLayout.Target.PKB !in layout.targets) return
        val device = engine.deviceVariant() ?: return
        try {
            keypadFiles(layout, device)
        } catch (e: RuntimeException) {
            Log.w(TAG, "layout '${layout.id}': building ahead failed: ${e.message}")
        }
    }

    /** As [prebuildKeypad], for a drawn on-screen keyboard. */
    fun prebuildOnScreen(drawn: DrawnKeyboard) {
        if (!engine.isAvailable || store == null || drawn.layoutSet in OnScreenKdbs.KEPT_LAYOUT_SETS) return
        try {
            onScreenFiles(drawn)
        } catch (e: RuntimeException) {
            Log.w(TAG, "on-screen '${drawn.layoutSet}': building ahead failed: ${e.message}")
        }
    }

    private fun currentLanguageSafely(): String? = try { currentLanguage() } catch (e: Exception) { null }

    companion object {
        private const val TAG = "XT9KDB"

        /** A `layout:` prefix, which the owned module lets reach the on-screen keyboard. */
        const val SCREEN_VARIANT_PREFIX: String = LetterLayoutKdb.VARIANT_PREFIX + "screen:"

        @JvmStatic
        fun current(): LetterLayoutEngineSync? = instance

        @Volatile
        private var instance: LetterLayoutEngineSync? = null

        /** Takes the first decision and listens for the device variant; calling again only decides again. */
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

        /** From [LetterLayoutRegistry.invalidate]. */
        @JvmStatic
        fun onActiveLayoutChanged() {
            instance?.onLayoutsChanged()
        }

        /**
         * From `NuanceSDK.syncKeyboardLayout`, before it waits and loads a KDB: where a language or keyboard
         * change shows first. [isPkb] is false for an on-screen keyboard's load.
         */
        @JvmStatic
        fun beforeEngineLayoutSync(isPkb: Boolean) {
            instance?.onLayoutSync(isPkb)
        }

        /** Tests only; null removes the instance. */
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
                currentLanguage = {
                    SubtypeManager.getInstance()?.currentSubtypeLocale?.let { LocaleUtils.languageCode(it) }
                },
                keypad = keypad,
                keypadType = keypadType,
                layoutSet = layoutSet,
                engineLetters = { language -> engineLettersFor(builtIns, language, keypad(), keypadType(), layoutSet()) },
                chosenId = { language -> LetterLayoutChoice.chosenId(PrefsManager.getPrefs(app), language, LetterLayout.Target.PKB) },
                chosenLayout = { language -> LetterLayoutChoice.forTyping(app, language) },
                onScreenKeyboard = { drawnOnScreen() },
                builtInLayout = { language ->
                    if (LetterLayoutChoice.keypadLettersChosen(app, language)) keypadLettersDocument(builtIns, keypad())
                    else builtIns.builtIn(language, keypad(), keypadType(), layoutSet())
                },
                shippedLayout = { language, set -> KeypadKdbs.shippedLetters(builtIns)(language, set) },
                store = KdbStore.forApp(app),
            )
        }

        /** The last keyboard read on the main thread, for decisions taken elsewhere. */
        @Volatile
        private var lastDrawn: DrawnKeyboard? = null

        /** Alphabet and Shift layer off the switcher's builder; off the main thread, the last one read there. */
        private fun drawnOnScreen(): DrawnKeyboard? {
            if (Looper.myLooper() != Looper.getMainLooper()) return lastDrawn
            val builder = KeyboardSwitcher.getInstance()?.keyboardBuilder ?: return lastDrawn
            val drawn = try {
                val alphabet = builder.getKeyboardForShift(0, false)
                val shifted = try { builder.getKeyboardForShift(1, false) } catch (e: Exception) { null }
                DrawnKeyboards.read(alphabet, shifted)
            } catch (e: Exception) {
                Log.w(TAG, "on-screen keyboard: could not read it: ${e.message}")
                null
            }
            if (drawn != null) lastDrawn = drawn
            return drawn ?: lastDrawn
        }

        /**
         * The built-in document's letter on each key, else the Latin letter. Documents are generated from the
         * engine's KDBs (held by LetterLayoutsMatchEngineTest). Not gated on the alphabet switch.
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

        /** The document with the keypad's own letters (en/fr/de for QWERTY/AZERTY/QWERTZ, from firmware key maps). */
        @VisibleForTesting
        @JvmStatic
        internal fun keypadLettersDocument(builtIns: LetterLayoutRegistry, keypad: String?): LetterLayout? =
            builtIns.builtIn(
                when (keypad?.lowercase(java.util.Locale.ROOT)) {
                    "azerty" -> "fr"
                    "qwertz" -> "de"
                    else -> "en"
                },
            )
    }

    @VisibleForTesting
    internal object XtEngine : Engine {
        override val isAvailable: Boolean get() = Xt9KdbVariant.isAvailable()
        override fun deviceVariant(): String? = Xt9KdbVariant.deviceVariant()
        override fun register(variant: String, xml: ByteArray): Boolean = Xt9KdbVariant.registerGenerated(variant, xml)
        override fun select(variant: String) = Xt9KdbVariant.selectVariant(variant)
        override fun submit(job: Runnable) = Xt9KdbVariant.submit(job)
    }

    @VisibleForTesting
    internal class AssetKdbFiles(private val assets: AssetManager) : KdbFiles {
        override fun list(template: Template): List<String> = try {
            // Folder's own files only: Robolectric lists sub-folder files as "sub/...".
            assets.list(FOLDER).orEmpty().filter { '/' !in it && it.endsWith(template.fileSuffix) }
        } catch (e: IOException) {
            emptyList()
        }

        override fun read(name: String): ByteArray? = try {
            assets.open("$FOLDER/$name").use { it.readBytes() }
        } catch (e: IOException) {
            Log.w(TAG, "layout: cannot read KDB $FOLDER/$name: ${e.message}")
            null
        }

        private companion object {
            const val FOLDER = "kdb"
        }
    }
}
