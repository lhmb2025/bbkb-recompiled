package dev.bbkb.ime.keyboard.internal

import android.content.Context
import android.graphics.Paint
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/**
 * P-14: long-press popups no longer re-parse the more-keys template or reload icons per popup, and
 * keyboards of one theme share one lazily inflated icon set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoreKeysPopupReuseTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private val english: InputMethodSubtype = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale("en_US")
        .setSubtypeMode("keyboard")
        .setSubtypeExtraValue("KeyboardLayoutSet=qwerty")
        .setIsAsciiCapable(true)
        .build()

    @Before
    fun setUp() {
        ResourceLocaleUtils.reinit(app)
        RichInputMethodManager.init(app)
        EmojiTestEnv.useSettings(EmojiTestEnv.settings())
        KeyboardBuilder.clearKeyboardCache()
        KeyboardIconSet.clearSharedIconsForTest()
        MoreKeysKeyboard.TemplateAttributes.clearForTest()
    }

    @After
    fun tearDown() {
        KeyboardBuilder.clearKeyboardCache()
        KeyboardIconSet.clearSharedIconsForTest()
        MoreKeysKeyboard.TemplateAttributes.clearForTest()
    }

    private fun keyboard(context: Context, elementId: Int, imeAction: Int = 0): Keyboard {
        val editor = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = imeAction
        }
        return KeyboardBuilder.Builder(context, editor)
            .setKeyboardGeometry(1080, 700)
            .setSubtype(english)
            .build()
            .getKeyboard(elementId)
    }

    /** Every field `load(template, id)` writes, as the old per-popup parse left it. */
    @Test
    fun theCachedTemplateFillsTheParamsExactlyAsAParseDoes() {
        val themed = BoardKeyboardFactory.themedContext(app)
        val parent = keyboard(themed, 0)

        val parsed = MoreKeysKeyboard.MoreKeysKeyboardParams()
        KeyboardXMLParser(themed, parsed).load(parent.mMoreKeysTemplate, parent.mId)
        val first = MoreKeysKeyboard.MoreKeysKeyboardParams()
        MoreKeysKeyboard.TemplateAttributes.obtain(themed, parent).applyTo(first, parent.mId)
        val second = MoreKeysKeyboard.MoreKeysKeyboardParams()
        val cached = MoreKeysKeyboard.TemplateAttributes.obtain(themed, parent)
        cached.applyTo(second, parent.mId)
        assertSame("parsed once", MoreKeysKeyboard.TemplateAttributes.obtain(themed, parent), cached)

        var compared = 0
        var cls: Class<*>? = MoreKeysKeyboard.MoreKeysKeyboardParams::class.java
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val type = field.type
                if (!(type.isPrimitive || type == KeyboardId::class.java || type == KeyVisualAttributes::class.java)) continue
                field.isAccessible = true
                assertEquals("field ${field.name}", field.get(parsed), field.get(first))
                assertEquals("field ${field.name}", field.get(parsed), field.get(second))
                compared++
            }
            cls = cls.superclass
        }
        assertTrue("compared $compared fields", compared > 20)
        // Icons: every popup draws from the template's one set, which copies the theme's prototypes.
        val shiftIcon = KeyboardIconSet.getIconId("shift_key")
        assertNotNull(second.mIconsSet.getIconDrawable(shiftIcon))
        assertSame(cached.iconsSet.getIconDrawable(shiftIcon), second.mIconsSet.getIconDrawable(shiftIcon))
        assertSame(cached.iconsSet.getIconDrawable(shiftIcon), first.mIconsSet.getIconDrawable(shiftIcon))
        assertSame("one set of prototypes per theme", shared(parsed.mIconsSet), shared(cached.iconsSet))
    }

    private fun shared(set: KeyboardIconSet): Any? =
        KeyboardIconSet::class.java.getDeclaredField("mShared").apply { isAccessible = true }.get(set)

    @Test
    fun aCachedTemplatePopupHasTheSameKeysAsTheFirst() {
        val themed = BoardKeyboardFactory.themedContext(app)
        val parent = keyboard(themed, 0)
        val key = parent.getKeyByCode('e'.code)!!
        assertNotNull("e has accents", key.moreKeys)
        val paint = Paint()
        val first = MoreKeysKeyboard.Builder(themed, key, parent, false, 0, 0, paint).build()
        val second = MoreKeysKeyboard.Builder(themed, key, parent, false, 0, 0, paint).build()
        assertEquals(first.mOccupiedWidth, second.mOccupiedWidth)
        assertEquals(first.mOccupiedHeight, second.mOccupiedHeight)
        assertEquals(first.defaultCoordX, second.defaultCoordX)
        assertEquals(
            first.keys.map { "${it.label}@${it.x},${it.y} ${it.width}x${it.height}" },
            second.keys.map { "${it.label}@${it.x},${it.y} ${it.width}x${it.height}" })
    }

    @Test
    fun keyboardsOfOneThemeShareLazilyInflatedPrototypes() {
        val themed = BoardKeyboardFactory.themedContext(app)
        val alphabet = keyboard(themed, 0)
        val symbols = keyboard(themed, 5)
        val deleteIcon = KeyboardIconSet.getIconId("delete_key")
        val numberPadIcon = KeyboardIconSet.getIconId("number_pad_active_key")

        val icons = KeyboardIconSet::class.java.getDeclaredField("mIcons").apply { isAccessible = true }
        assertNull("not copied until asked for", (icons.get(alphabet.mIconsSet) as Array<*>)[numberPadIcon])
        assertSame("one set of prototypes per theme", shared(alphabet.mIconsSet), shared(symbols.mIconsSet))

        // Each keyboard tints its own copy (see KeyboardIconSet.sSharedIcons for why).
        val mine = alphabet.mIconsSet.getIconDrawable(deleteIcon)
        val theirs = symbols.mIconsSet.getIconDrawable(deleteIcon)
        assertNotNull(mine)
        assertNotSame(mine, theirs)
        assertSame("copied once", mine, alphabet.mIconsSet.getIconDrawable(deleteIcon))
        mine!!.setTint(0xFF00FF00.toInt())
        assertNotSame(mine.constantState, theirs!!.constantState)
        assertEquals(alphabet.mIconsSet.getIconResourceId("delete_key"), symbols.mIconsSet.getIconResourceId("delete_key"))
        assertEquals(mine.intrinsicWidth, theirs.intrinsicWidth)

        // A second themed context of the same style (a new service instance) shares the same
        // prototypes, rebound to its Resources, so cached keyboards survive an IME switch; the
        // icons it has not copied yet still resolve.
        val otherTheme = BoardKeyboardFactory.themedContext(app)
        KeyboardBuilder.clearKeyboardCache()
        val other = keyboard(otherTheme, 0)
        assertSame("same style shares prototypes across theme instances", shared(alphabet.mIconsSet), shared(other.mIconsSet))
        assertNotNull(other.mIconsSet.getIconDrawable(numberPadIcon))
    }

    @Test
    fun popupsAreCachedPerParentKeyboardAndKey() {
        val themed = BoardKeyboardFactory.themedContext(app)
        val lower = keyboard(themed, 0)
        val upper = keyboard(themed, 1)
        val lowerE = lower.getKeyByCode('e'.code)!!
        val upperE = upper.getKeyByCode('E'.code) ?: upper.getKeyByCode('e'.code)!!
        val paint = Paint()
        val cache = MoreKeysKeyboardCache()
        val lowerPopup = MoreKeysKeyboard.Builder(themed, lowerE, lower, false, 0, 0, paint).build()
        cache.put(lower, lowerE, 7, lowerPopup)

        assertSame(lowerPopup, cache.get(lower, lowerE, 7))
        assertNull("the shifted board has its own popups", cache.get(upper, upperE, 7))
        val upperPopup = MoreKeysKeyboard.Builder(themed, upperE, upper, false, 0, 0, paint).build()
        cache.put(upper, upperE, 7, upperPopup)
        assertSame(lowerPopup, cache.get(lower, lowerE, 7))
        assertSame(upperPopup, cache.get(upper, upperE, 7))
        assertEquals("the popup shows the shifted key's own labels",
            upperE.moreKeys.map { it.label }.toSet(),
            upperPopup.keys.mapNotNull { it.label }.toSet())

        assertNull("another preview geometry drops them", cache.get(lower, lowerE, 8))
        assertNull(cache.get(upper, upperE, 7))
    }
}
