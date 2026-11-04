package dev.bbkb.ime.keyboard

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A new field with the same keyboard reuses the parsed layout set and the built keyboards
 * (P-12, D-10), and the keyboard cache neither keeps the app's EditorInfo nor grows without
 * bound (P-14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardBuilderCacheTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ResourceLocaleUtils.reinit(app)
        RichInputMethodManager.init(app)
        EmojiTestEnv.useSettings(EmojiTestEnv.settings())
        KeyboardBuilder.clearKeyboardCache()
        KeyboardBuilder.clearLayoutSetCacheForTest()
    }

    @After
    fun tearDown() {
        KeyboardBuilder.clearKeyboardCache()
    }

    private val english: InputMethodSubtype = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale("en_US")
        .setSubtypeMode("keyboard")
        .setSubtypeExtraValue("KeyboardLayoutSet=qwerty")
        .setIsAsciiCapable(true)
        .build()

    private fun field(withAppState: Boolean = false) = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "com.example.app"
        if (withAppState) {
            extras = Bundle().apply { putString("secret", "x") }
            hintText = "Search"
        }
    }

    private fun builder(editor: EditorInfo, width: Int = 1080): KeyboardBuilder =
        KeyboardBuilder.Builder(BoardKeyboardFactory.themedContext(app), editor)
            .setKeyboardGeometry(width, 900)
            .setSubtype(english)
            .build()

    private fun elementsOf(builder: KeyboardBuilder): Any? {
        val params = KeyboardBuilder::class.java.getDeclaredField("mParams").apply { isAccessible = true }.get(builder)
        return KeyboardBuilder.Params::class.java.getDeclaredField("mMoreKeySpecIdToParamsMap").apply { isAccessible = true }.get(params)
    }

    @Test
    fun aNewFieldReusesTheParsedLayoutSet() {
        val first = builder(field())
        val second = builder(field(withAppState = true))
        assertEquals(1, KeyboardBuilder.layoutSetCacheSizeForTest())
        assertSame(elementsOf(first), elementsOf(second))
        assertEquals(first.supportedScriptId, second.supportedScriptId)
        assertEquals(first.hasPkbLayout(), second.hasPkbLayout())
    }

    @Test
    fun aNewFieldReusesTheAlphabetAndSymbolKeyboards() {
        val first = builder(field())
        val second = builder(field(withAppState = true))
        assertSame(first.getKeyboard(0), second.getKeyboard(0))
        assertSame(first.getKeyboard(5), second.getKeyboard(5))
    }

    @Test
    fun theKeyboardDoesNotKeepTheAppsEditorInfo() {
        val editor = field(withAppState = true)
        val keyboard = builder(editor).getKeyboard(0)
        assertNotSame(editor, keyboard.mId.mEditorInfo)
        assertNull(keyboard.mId.mEditorInfo.extras)
        assertNull(keyboard.mId.mEditorInfo.hintText)
        assertEquals(editor.inputType, keyboard.mId.mEditorInfo.inputType)
        val keyFields = KeyboardBuilder.KeyboardCacheKey::class.java.declaredFields
        assertFalse(keyFields.any { EditorInfo::class.java.isAssignableFrom(it.type) || it.type == keyboard.mId.javaClass })
    }

    @Test
    fun aCollectedKeyboardLeavesTheCache() {
        builder(field()).getKeyboard(0)
        assertEquals(1, KeyboardBuilder.keyboardCacheSizeForTest())
        val cache = KeyboardBuilder::class.java.getDeclaredField("sKeyboardCache").apply { isAccessible = true }.get(null) as Map<*, *>
        // What the collector does to a soft reference under memory pressure.
        synchronized(cache) { cache.values.forEach { (it as java.lang.ref.Reference<*>).enqueue() } }
        assertEquals(0, KeyboardBuilder.keyboardCacheSizeForTest())
    }

    @Test
    fun theKeyboardCacheIsBounded() {
        val editor = field()
        repeat(60) { builder(editor, width = 1000 + it).getKeyboard(0) }
        assertEquals(48, KeyboardBuilder.keyboardCacheSizeForTest())
    }
}
