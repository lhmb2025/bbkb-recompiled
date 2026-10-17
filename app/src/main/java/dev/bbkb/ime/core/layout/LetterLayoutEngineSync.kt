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
 * Keeps the engine's key letters in step with the letter layout the keys show, so tap correction
 * and swipe typing read the letters the keys actually type: on the physical keypad and on the
 * on-screen keyboard.
 *
 * **Physical keypad.** The layout that drives the physical keys of the current keyboard is the
 * one chosen for its language on the Language screen ([LetterLayoutChoice]) when that is for the
 * physical keys and fits — what [PhysicalKeyResolver.userLayout] types with. While that layout
 * fits this keyboard and keypad and changes at least one letter key
 * against the engine's own letters for the language, every physical-keyboard KDB the engine could
 * load is rewritten from it ([LetterLayoutKdb]) and registered under the engine variant
 * `layout:<id>`, which is then selected. Otherwise, when the keyboard has a built-in document (the
 * letters its keys type: QWERTY on most Latin keyboards, the script on Arabic, Cyrillic, Hebrew,
 * Greek and Korean ones), every physical-keyboard KDB is rewritten from that document instead and
 * registered under `layout:builtin:<id>` ([LetterLayoutKdb.builtInVariantName]), so the engine
 * always reads the letters the keys type, even where several keyboards share one engine id
 * (Italian, Polish and Belgian Dutch all ask for English's). A language that chose the keypad's
 * own letters ([LetterLayoutChoice.KEYPAD_LETTERS]) gets the keypad shape's Latin document the
 * same way, so the engine decodes the Latin letters its keys now type. Only a keyboard with no
 * built-in document (the native-script keyboards whose keypad types the KEY2's etched Latin
 * letters) selects the device variant ([Xt9KdbVariant.deviceVariant]) again: the device's own
 * keypad KDBs ([KeypadKdbs]) and the shipped files. The rewritten files start from the device's: its keypad
 * KDB for each engine id it builds ([KeypadKdbs.forDevice], with that id's own letters on the keys
 * the layout does not name) and the shipped file for every other id, so a layout changes letters
 * and never the key geometry the decoder is tuned to.
 *
 * **On-screen keyboard.** The engine's on-screen KDB is built from the keyboard actually drawn
 * ([DrawnKeyboard], read off the built keyboard on every on-screen sync): its letters on the shipped
 * on-screen file of its row shape ([OnScreenKdbs]), under every id the on-screen keyboard may ask
 * for, registered as `layout:screen:<fingerprint>` and selected. Whatever the keyboard was drawn
 * from (its own layout set, a chosen Colemak, a language's text table), the engine reads the drawn
 * letters. Only the Chinese stroke, zhuyin and Cangjie keyboards select the device variant and
 * their shipped files ([OnScreenKdbs.KEPT_LAYOUT_SETS]). The owned KDB module applies only `layout:`
 * variants to the on-screen keyboard (the device variant must never reach it).
 *
 * **Built once.** Built KDBs are kept on storage ([KdbStore]), so a keyboard is built once per
 * install; [prebuildKeypad] and [prebuildOnScreen] build them ahead, off the registration thread,
 * for the keyboards the user has enabled, so a switch only reads and registers them.
 *
 * The decision is taken again whenever its inputs may have changed: the IME starting ([start]),
 * the registry saving, deleting or switching layouts or a language's choice changing
 * ([onActiveLayoutChanged]), the device variant changing (the variant's own listener), and every
 * layout sync of either keyboard, which is where a change of keyboard language or of surface shows
 * ([beforeEngineLayoutSync]). The work — reading the KDB assets, rewriting, registering, selecting —
 * runs on [Xt9KdbVariant]'s registration thread, the one `NuanceSDK.syncKeyboardLayout` waits for
 * through `Xt9KdbVariant.awaitReady` before it loads a layout, so the engine's next load always
 * sees the outcome. A sync whose inputs have not changed queues nothing.
 *
 * A layout's KDBs are built once per (layout content, device variant) and keyboard, and kept
 * registered, so switching keyboards back and forth only switches the selection. The physical
 * keypad's cover every language: the selection, not the files, is what follows the binding.
 *
 * Inert without the owned KDB module (stock and DIFF builds select nothing; see [Xt9KdbVariant]).
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
    /** The letter the engine has on each letter key for a language: what a layout "changes". */
    private val engineLetters: (language: String?) -> (keyCode: Int) -> Int,
    /** The physical choice of a language on the Language screen as stored, `""` for none; cheap. */
    private val chosenId: (language: String?) -> String = { "" },
    /** The layout chosen for a language's physical keys on the Language screen, whatever it is for. */
    private val chosenLayout: (language: String?) -> LetterLayout? = { null },
    /**
     * The on-screen keyboard as drawn right now, or null when there is none to read. Asked on the
     * thread of the on-screen sync. Without this source, on-screen syncs are not followed at all.
     */
    private val onScreenKeyboard: (() -> DrawnKeyboard?)? = null,
    /**
     * The built-in document a keyboard in a language types with on the physical keys (the keypad
     * shape's Latin document for a language that chose the keypad's own letters), or null where
     * none is bound. Without this source, a keyboard no chosen layout applies to keeps the shipped
     * KDB files.
     */
    private val builtInLayout: (language: String?) -> LetterLayout? = { null },
    /**
     * The built-in document holding the letters of an engine id's keypad KDB ([KeypadKdbs.SHIPPED]):
     * what the device's keypad KDBs are built with. Without it, a layout's keypad KDBs are built
     * from the shipped files alone.
     */
    private val shippedLayout: (language: String, layoutSet: String?) -> LetterLayout? = { _, _ -> null },
    /** Where built KDBs are kept between processes; none keeps them in memory only. */
    private val store: KdbStore? = null,
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

    /** The engine's KDB files, under `assets/kdb/`. */
    interface KdbFiles {
        /** The names of the files of [template]; a device's keypad geometry ([KeypadKdbs.isTemplate]) among them. */
        fun list(template: Template): List<String>

        fun read(name: String): ByteArray?
    }

    /**
     * The inputs a queued decision was taken for: an equal one queues nothing. The layout set is
     * part of it because both a user layout's binding and the engine's own letters can depend on
     * it: switching between two keyboards of one language (Russian and its transliteration) must
     * make the engine decide again. The keyboard ([pkb]) is part of it because the two keyboards
     * decide differently and share one selected variant: each must decide again when the other
     * synced last.
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

    /**
     * The keyboard the last sync was for: where a change made in settings is decided. The physical
     * keypad until an on-screen sync says otherwise. Guarded by [lock].
     */
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

    /**
     * The device variant changed: every physical-keypad variant came from the old one's files.
     * Runs on the registration thread (the variant's listener), so it decides right here.
     */
    fun onDeviceVariantChanged() {
        built.keys.removeAll { it.second == Template.PKB }
        val (language, pkb) = synchronized(lock) {
            changes++
            lastRequest = null
            currentLanguageSafely() to lastPkb
        }
        // The on-screen files never came from the device variant; the next on-screen sync, which
        // the forgotten request no longer matches, decides for that keyboard.
        if (pkb) decide(language, pkb = true)
    }

    private fun request(language: String?, pkb: Boolean) {
        if (!engine.isAvailable) return
        val chosen = try { chosenId(language) } catch (e: Exception) { "" }
        // What the screen shows is read here, on the caller's thread (the keyboard's), not on the
        // registration thread, and handed to the decision.
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

    /** Selects the variant the engine's physical keypad should load from now. On the registration thread. */
    @VisibleForTesting
    internal fun decide(language: String?) = decide(language, pkb = true)

    /**
     * Selects the variant the engine should load from now, for the physical keypad ([pkb]) or the
     * on-screen keyboard as [drawn]. On the registration thread.
     */
    @VisibleForTesting
    internal fun decide(language: String?, pkb: Boolean, drawn: DrawnKeyboard? = null) {
        if (!engine.isAvailable) return
        // Before the device profile has applied its variant there is nothing to build on; the
        // variant's listener decides again once it has, and so does the next sync, which the
        // forgotten request no longer matches.
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

    /**
     * The layout the physical keys follow for [language] when the engine should follow it too,
     * else null: the language's own choice when it is for the physical keys, fits, and changes a
     * letter key.
     */
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

    /**
     * Rewrites every KDB of [Template.PKB] the engine could load ([keypadFiles]) and registers each
     * under [name]. Returns how many were registered.
     */
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
     * [layout]'s keypad KDBs on [device]: every one the engine could load — [device]'s keypad KDB
     * for each engine id it builds, the shipped file for every other id — rewritten with the
     * layout's letters, unchanged files included (the engine falls back to the shipped root file,
     * not the device's, for an id a selected variant lacks). Read back from the [store] when built
     * before. From any thread.
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

    /**
     * [drawn]'s on-screen KDBs ([OnScreenKdbs.build]), read back from the [store] when built
     * before; null when no shipped file has its row shape. From any thread.
     */
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

    /**
     * Builds [layout]'s keypad KDBs into the [store] ahead of its keyboard's first use, so the
     * switch only reads them. Off the registration thread; nothing before the device variant is
     * known.
     */
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

        /** The prefix of an on-screen variant: a `layout:` one, which the owned module lets reach the on-screen keyboard. */
        const val SCREEN_VARIANT_PREFIX: String = LetterLayoutKdb.VARIANT_PREFIX + "screen:"

        /** The process's instance, once [start] has made it. */
        @JvmStatic
        fun current(): LetterLayoutEngineSync? = instance

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

        /**
         * From [LetterLayoutRegistry.invalidate]: a layout was saved, deleted or switched, or a
         * language's choice changed.
         */
        @JvmStatic
        fun onActiveLayoutChanged() {
            instance?.onLayoutsChanged()
        }

        /**
         * From `NuanceSDK.syncKeyboardLayout`, just before it waits for the registration thread
         * and loads a KDB: a change of keyboard language, or of keyboard, shows here first. [isPkb]
         * is the engine's physical-keyboard flag: false for an on-screen keyboard's load.
         */
        @JvmStatic
        fun beforeEngineLayoutSync(isPkb: Boolean) {
            instance?.onLayoutSync(isPkb)
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

        /** The last keyboard read on the main thread, for a decision taken elsewhere (a settings change). */
        @Volatile
        private var lastDrawn: DrawnKeyboard? = null

        /**
         * The on-screen keyboard as drawn: its alphabet and Shift layer, read off the switcher's
         * builder. Keyboards are built and cached on the main thread only, so elsewhere this is the
         * last one read there.
         */
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

        /**
         * The built-in document whose letters are the keypad's own on a [keypad] of that shape:
         * the English, French and German keyboards' (QWERTY, AZERTY, QWERTZ), which the generator
         * writes from the firmware key maps. What the engine decodes against for a language that
         * chose the keypad's letters.
         */
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
        override fun list(template: Template): List<String> = try {
            // The folder's own files only: a phone lists a sub-folder by its name, which the suffix
            // already excludes, but some asset managers (Robolectric's) list its files as "sub/…".
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
