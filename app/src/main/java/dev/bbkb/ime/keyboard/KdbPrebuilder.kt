package dev.bbkb.ime.keyboard

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import com.blackberry.nuanceshim.Xt9KdbVariant
import dev.bbkb.ime.core.device.ResourceConfigManager
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.layout.KdbStore
import dev.bbkb.ime.core.layout.LetterLayoutChoice
import dev.bbkb.ime.core.layout.LetterLayoutEngineSync
import dev.bbkb.ime.core.layout.LetterLayoutRegistry
import dev.bbkb.ime.core.layout.OnScreenKdbs
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Builds the engine's KDBs for every enabled keyboard ahead of its first use, into the
 * [KdbStore], so switching to a keyboard only reads and registers them
 * ([LetterLayoutEngineSync.prebuildKeypad], [LetterLayoutEngineSync.prebuildOnScreen]).
 *
 * The IME starts it a few seconds after its own start-up, to stay out of its way. The keypad's KDBs are built on
 * a low-priority worker thread. The on-screen ones need each keyboard's drawn letters, and
 * keyboards are built on the main thread only (their caches are not thread-safe), so one keyboard
 * is built per idle moment of the main thread and its letters handed to the worker. A keyboard
 * done once is not built again until the install or its drawn layout set changes.
 */
object KdbPrebuilder {
    private const val TAG = "XT9KDB"

    /** How long after the IME's start-up to begin. */
    const val START_DELAY_MS: Long = 4000L

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KdbPrebuild").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile
    private var started = false

    /** On the main thread, [START_DELAY_MS] after the IME's start-up; once per process. */
    @JvmStatic
    fun start(context: Context) {
        if (started) return
        started = true
        run(context.applicationContext)
    }

    private fun run(app: Context) {
        val subtypes = try {
            RichInputMethodManager.getInstance()?.enabledSubtypesOfThisIme.orEmpty()
        } catch (e: RuntimeException) {
            Log.w(TAG, "build ahead: no enabled keyboards: ${e.message}")
            return
        }
        if (subtypes.isEmpty()) return
        worker.execute { keypads(app, subtypes) }
        val queue = ArrayDeque(subtypes)
        Looper.myQueue().addIdleHandler {
            val subtype = queue.pollFirst() ?: return@addIdleHandler false
            try {
                onScreen(app, subtype)
            } catch (e: RuntimeException) {
                Log.w(TAG, "build ahead: on-screen ${subtype.locale}: ${e.message}")
            }
            queue.isNotEmpty()
        }
    }

    /** Every enabled keyboard's keypad layouts: its built-in letters and its language's chosen layout. */
    private fun keypads(app: Context, subtypes: List<InputMethodSubtype>) {
        Xt9KdbVariant.awaitReady(5000)
        val sync = LetterLayoutEngineSync.current() ?: return
        val registry = LetterLayoutRegistry(app)
        val profile = DeviceProfile.current()
        val started = System.nanoTime()
        for (subtype in subtypes) {
            val language = LocaleUtils.languageCode(ResourceLocaleUtils.getSubtypeLocale(subtype))
            val set = ResourceLocaleUtils.getKeyboardLayoutSetName(subtype)
            registry.builtIn(language, profile?.keypadLayout, profile?.effectiveKeypadType, set)?.let(sync::prebuildKeypad)
            LetterLayoutChoice.forTyping(app, language)?.let(sync::prebuildKeypad)
        }
        Log.i(TAG, "build ahead: keypad KDBs for ${subtypes.size} keyboard(s) ready in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** [subtype]'s on-screen keyboard, built here on the main thread, read, and handed to the worker. */
    private fun onScreen(app: Context, subtype: InputMethodSubtype) {
        val sync = LetterLayoutEngineSync.current() ?: return
        val set = LetterLayoutChoice.keyboardLayoutSet(app, subtype)
        if (set in OnScreenKdbs.KEPT_LAYOUT_SETS) return
        val store = KdbStore.forApp(app)
        val done = KdbStore.key("vkb-ahead", subtype.locale, subtype.extraValue ?: "", set)
        if (store.has(done)) return
        val themed = BoardKeyboardFactory.themedContext(app)
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val builder = KeyboardBuilder.Builder(themed, editor)
            .setKeyboardGeometry(
                ResourceConfigManager.getScreenWidthPixels(themed.resources),
                ResourceConfigManager.getKeyboardHeight(themed.resources),
            )
            .setSubtype(subtype)
            .useChosenLetterLayout()
            .build()
        val alphabet = builder.getKeyboardForShift(0, false)
        val shifted = try { builder.getKeyboardForShift(1, false) } catch (e: Exception) { null }
        val drawn = DrawnKeyboards.read(alphabet, shifted) ?: return
        worker.execute {
            sync.prebuildOnScreen(drawn)
            store.put(done, emptyList())
        }
    }
}
