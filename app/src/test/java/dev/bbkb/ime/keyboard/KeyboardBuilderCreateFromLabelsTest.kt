package dev.bbkb.ime.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.keyboard.internal.KeyHintPosition
import dev.bbkb.ime.keyboard.internal.MoreKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Characterisation of [KeyboardBuilder.createFromLabels] — the throwaway single-row keyboard the
 * aux bar's accent strip is built from (`AuxBarManager.buildAccentKeyboard`).
 *
 * Written before the P8 §2(c) consolidation of its two inline `new Key(...)` blocks. Each built key
 * is compared field-by-field AND through `Key.equals`/`hashCode` against a key constructed here
 * with the original argument list — `Keyboard.replaceKey` and the proximity grid look keys up by
 * that hash, so a factory that shifts any constructor argument is a behaviour change even when
 * the visible fields agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardBuilderCreateFromLabelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screenWidth get() = context.resources.displayMetrics.widthPixels

    /** The label key exactly as the pre-consolidation code constructed it. */
    private fun expectedLabelKey(label: String, x: Int, keyWidth: Int, keyHeight: Int) = Key(
        MoreKeySpec(label, false, Locale.getDefault()), MoreKeySpec.getEmpty(),
        KeyHintPosition.HIDDEN, null, 0, 1, x, 0, keyWidth, keyHeight, 0, 0,
        label, null, null, 0, 0, 0
    )

    private fun assertSameKey(expected: Key, actual: Key) {
        val where = "key ${expected.label}@${expected.x}"
        assertEquals("$where label", expected.label, actual.label)
        assertEquals("$where code", expected.code, actual.code)
        assertEquals("$where outputText", expected.outputText, actual.outputText)
        assertEquals("$where hintLabel", expected.hintLabel, actual.hintLabel)
        assertEquals("$where x", expected.x, actual.x)
        assertEquals("$where y", expected.y, actual.y)
        assertEquals("$where width", expected.width, actual.width)
        assertEquals("$where height", expected.height, actual.height)
        assertEquals("$where backgroundType", expected.backgroundType, actual.backgroundType)
        assertEquals("$where labelFlags", expected.labelFlags, actual.labelFlags)
        assertEquals("$where actionFlags", expected.actionFlags, actual.actionFlags)
        assertEquals("$where moreKeysFlags", expected.moreKeysFlags, actual.moreKeysFlags)
        assertEquals("$where scanCode", expected.scanCode, actual.scanCode)
        assertNull("$where moreKeys", actual.moreKeys)
        assertNull("$where keyLabelSet", actual.keyLabelSet)
        assertEquals("$where isEnabled", expected.isEnabled, actual.isEnabled)
        assertEquals("$where hashCode", expected.hashCode(), actual.hashCode())
        assertEquals("$where equals", expected, actual)
    }

    @Test
    fun emptyLabelsWithoutPageNav_returnsNull() {
        assertNull(KeyboardBuilder.createFromLabels(context, emptyList(), 90))
    }

    @Test
    fun labelsOnly_oneEqualWidthKeyPerLabel() {
        val labels = listOf<CharSequence>("a", "é", "ß", "😀")
        val keyboard = KeyboardBuilder.createFromLabels(context, labels, 90)!!
        val keyWidth = screenWidth / 4
        val keys = keyboard.keys
        assertEquals(4, keys.size)
        labels.forEachIndexed { i, label ->
            assertSameKey(expectedLabelKey(label.toString(), i * keyWidth, keyWidth, 90), keys[i])
        }
    }

    /** The always-throwing page-nav variant is gone: the only overload takes no page-nav flag. */
    @Test
    fun noPageNavOverloadExists() {
        val signatures = KeyboardBuilder::class.java.declaredMethods
            .filter { it.name == "createFromLabels" }
            .map { it.parameterTypes.toList() }
        assertEquals(
            listOf(listOf(Context::class.java, List::class.java, Int::class.javaPrimitiveType)),
            signatures,
        )
    }

    @Test
    fun keyboardGeometryAndId() {
        val keyboard = KeyboardBuilder.createFromLabels(context, listOf<CharSequence>("q", "w"), 77)!!
        assertEquals(77, keyboard.mOccupiedHeight)
        assertEquals(screenWidth, keyboard.mOccupiedWidth)
        assertEquals(77, keyboard.mBaseHeight)
        assertEquals(screenWidth, keyboard.mBaseWidth)
        assertEquals(0, keyboard.mHorizontalGap)
        assertEquals(0, keyboard.mVerticalGap)
        assertEquals(77, keyboard.mMostCommonKeyHeight)
        assertEquals(77, keyboard.mMostCommonKeyWidth)
        assertEquals(2, keyboard.mMaxMoreKeysKeyboardColumn)
        assertEquals(0, keyboard.mId.mElementId)
        assertEquals(0, keyboard.mId.mMode)
        assertEquals(screenWidth, keyboard.mId.mWidth)
        assertEquals(77, keyboard.mId.mHeight)
        assertNull(keyboard.mId.mSubtype)
    }

    /**
     * CHARACTERISED: the label loop reads `Character.codePointAt(label, 0)` (its result unused), so
     * an empty label throws rather than producing an empty key. A factory that drops that read —
     * as the audit sketch did — changes this.
     */
    @Test
    fun emptyLabel_throwsStringIndexOutOfBounds() {
        try {
            KeyboardBuilder.createFromLabels(context, listOf<CharSequence>("a", ""), 90)
            fail("expected StringIndexOutOfBoundsException")
        } catch (e: StringIndexOutOfBoundsException) {
            assertTrue(true)
        }
    }
}
