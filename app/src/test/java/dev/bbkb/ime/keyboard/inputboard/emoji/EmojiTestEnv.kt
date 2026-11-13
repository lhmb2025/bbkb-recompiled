package dev.bbkb.ime.keyboard.inputboard.emoji

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodSubtype
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import dev.bbkb.ime.core.AudioAndHapticFeedbackManager
import dev.bbkb.ime.core.device.ResourceConfigManager
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import dev.bbkb.ime.R
import org.mockito.Mockito
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Field
import java.time.Duration

/**
 * Runs the emoji board headless with real resources and keyboard XML. Singletons it reaches: the emoji subtype
 * is injected (`getEmojiSubtype()` needs RichInputMethodManager); RichInputMethodManager is initialised for the
 * XML parser; haptics and `SettingsManager` get an all-defaults `SettingsValues` shell; and the emojibase
 * provider is seeded with a known dataset, since `Paint.hasGlyph` is always false under legacy graphics.
 */
internal object EmojiTestEnv {

    const val RECENTS_KEY = "emoji_recent_keys"
    const val LAST_CATEGORY_KEY = "last_shown_emoji_category_id"
    const val PAGE_KEYS = 40

    val app: Context get() = ApplicationProvider.getApplicationContext()

    fun prefs(): SharedPreferences = PrefsManager.getPrefs(app)

    fun install() {
        field(SubtypeManager::class.java, "emojiSubtype").set(SubtypeManager.getInstance(), emojiSubtype())
        RichInputMethodManager.init(app)
        KeyboardBuilder.clearKeyboardCache()
        useSettings(settings())
        EmojibaseDataProvider.releaseShared()
    }

    fun uninstall() {
        EmojibaseDataProvider.releaseShared()
        KeyboardBuilder.clearKeyboardCache()
    }

    private fun emojiSubtype(): InputMethodSubtype = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale("zz")
        .setSubtypeMode("keyboard")
        .setSubtypeExtraValue("KeyboardLayoutSet=emoji")
        .setIsAsciiCapable(false)
        .build()

    fun settings(
        dynamicSearch: Boolean = false,
        replaceText: Boolean = false,
        emojiPredictions: Boolean = false,
        pkbEmojiSearch: Boolean = true,
    ): SettingsValues {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = field(unsafeClass, "theUnsafe").get(null)
        val sv = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, SettingsValues::class.java) as SettingsValues
        field(SettingsValues::class.java, "isEmojiDynamicSearchEnabled").setBoolean(sv, dynamicSearch)
        field(SettingsValues::class.java, "isEmojiSearchReplaceText").setBoolean(sv, replaceText)
        field(SettingsValues::class.java, "isEmojiPredictionsEnabled").setBoolean(sv, emojiPredictions)
        field(SettingsValues::class.java, "isPkbEmojiSearchEnabled").setBoolean(sv, pkbEmojiSearch)
        return sv
    }

    fun useSettings(sv: SettingsValues) {
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(sv, app)
        field(SettingsManager::class.java, "settingsValues").set(SettingsManager.getInstance(), sv)
    }

    fun field(cls: Class<*>, name: String): Field = cls.getDeclaredField(name).apply { isAccessible = true }

    fun read(target: Any, name: String): Any? = field(target.javaClass, name).get(target)

    fun emoji(e: String, group: Int, description: String) = EmojiData(
        emoji = e, codepoints = "0", description = description,
        group = group, subgroup = 0, emojiVersion = 1.0, order = 0
    )

    fun seed(byCategory: Map<EmojiCategory, List<EmojiData>>) {
        val provider = EmojibaseDataProvider(app)
        field(EmojibaseDataProvider::class.java, "emojiByCategory")
            .set(provider, byCategory.mapValues { it.value.toMutableList() }.toMutableMap())
        field(EmojibaseDataProvider::class.java, "sharedLoadedInstance").set(null, provider)
    }

    /**
     * Smileys: 41 emoji (two pages). People: none, so no tab and no pages. Animals: 3. Food: 2.
     * Position map: 0 Recents | 1-2 Smileys | 3 Animals | 4 Food. Tabs: Recents, Smileys, Animals, Food.
     */
    fun seedStandard() = seed(
        linkedMapOf(
            EmojiCategory.SMILEYS_EMOTION to (0 until 41).map { emoji("s$it", 0, "smile $it") },
            EmojiCategory.ANIMALS_NATURE to (0 until 3).map { emoji("a$it", 3, "cat $it") },
            EmojiCategory.FOOD_DRINK to (0 until 2).map { emoji("f$it", 4, "food $it") },
        )
    )

    fun emojiBuilder(): KeyboardBuilder {
        val res = app.resources
        return BoardKeyboardFactory.builder(
            app,
            SubtypeManager.getInstance().getEmojiSubtype(),
            ResourceConfigManager.getScreenWidthPixels(res),
            EmojiPalettesLayoutParams(res).pagerHeight
        )
    }

    /** The emoji page template the board builds every page from (element 12, `kbd_emoji_recents`). */
    fun templateKeyboard(): Keyboard = emojiBuilder().getKeyboard(12)

    /**
     * Inflates the board as `KeyboardSwitcher`'s ViewStub does and attaches it to [activity]. A mock
     * `KeyboardSwitcher` answers null for the alphabet keyboard, which selects the fallback height.
     */
    fun inflatePalettes(activity: Activity): EmojiPalettesView {
        val view = LayoutInflater.from(BoardKeyboardFactory.themedContext(activity))
            .inflate(R.layout.emoji_palettes_view, null) as EmojiPalettesView
        field(EmojiPalettesView::class.java, "keyboardSwitcher")
            .set(view, Mockito.mock(KeyboardSwitcher::class.java))
        activity.setContentView(view)
        idle()
        return view
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A ViewPager2's pages are children of its inner RecyclerView. */
    fun ViewPager2.recycler(): RecyclerView = getChildAt(0) as RecyclerView

    /** The page views the pager has laid out (each is wrapped in its full-size page frame). */
    fun ViewPager2.pageViews(): List<EmojiPageKeyboardView> {
        val r = recycler()
        return (0 until r.childCount).map { (r.getChildAt(it) as android.view.ViewGroup).getChildAt(0) as EmojiPageKeyboardView }
    }

    /** Pumps the main looper (advancing its clock) while the search executor runs on a real thread. */
    fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /** Lets any debounced search run to completion, for asserting that something did not happen. */
    fun settle(ms: Long = 600) {
        repeat((ms / 10).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(3)
        }
    }

    fun jsonArray(items: List<String>): String = items.joinToString(",", "[", "]") { "\"$it\"" }

    /** The recents grid as `EmojiKeyboard` writes it: entries, then `""` for every blank filler key. */
    fun padded(vararg head: String, size: Int = PAGE_KEYS): String =
        jsonArray(head.toList() + List(size - head.size) { "" })
}
