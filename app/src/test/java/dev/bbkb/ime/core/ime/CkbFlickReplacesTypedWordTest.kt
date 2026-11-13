package dev.bbkb.ime.core.ime

import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.textinput.InputLogic
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins that a keypad flick-up pick replaces the typed word. The flick's samples also feed swipe
 * typing, whose start commits the typed word before the flick is recognised at UP; the pick must
 * take that commit back, or "helo" + "hello" gives "helohello".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CkbFlickReplacesTypedWordTest {

    private lateinit var ime: BlackBerryIME
    private lateinit var inputLogic: InputLogic
    private lateinit var bridge: CkbGestureBridge

    private val words = SuggestedWords(
        arrayListOf(SuggestedWords.SuggestedWordInfo("hello", 100, 0, null, -1, -1, null)),
        null, false, false, 0,
    )

    @Before
    fun setUp() {
        val sv = allocate(SettingsValues::class.java)
        val settings = Mockito.mock(SettingsManager::class.java)
        Mockito.`when`(settings.getSettingsValues()).thenReturn(sv)
        ime = Mockito.mock(BlackBerryIME::class.java)
        Mockito.`when`(ime.getSettingsManager()).thenReturn(settings)
        Mockito.`when`(ime.resources).thenReturn(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
        inputLogic = Mockito.mock(InputLogic::class.java)
        InputLogic::class.java.getField("mCurrentSuggestions").set(inputLogic, words)
        Mockito.`when`(ime.getInputLogic()).thenReturn(inputLogic)
        bridge = CkbGestureBridge(ime)
    }

    private fun flickCommit(): Boolean {
        val m = CkbGestureBridge::class.java.getDeclaredMethod("commitSuggestionAt", Float::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(bridge, 0.5f) as Boolean
    }

    /** The flick's samples started a swipe, which committed "helo": the pick takes it back first. */
    @Test
    fun aFlickWhoseSamplesStartedASwipeTakesTheCommittedWordBackBeforeThePick() {
        bridge.onContactStart(1000L)
        bridge.onSwipeStartCommittedTypedWord("helo")

        assertTrue(flickCommit())

        val order = inOrder(inputLogic, ime)
        order.verify(inputLogic).takeBackWordCommittedForSwipe("helo")
        order.verify(ime).onSuggestionPicked(words.getWordInfo(0), InputSource.SOFTWARE)
    }

    /** A flick that never became a swipe touches nothing but the pick (it replaces the composing word). */
    @Test
    fun aFlickThatStartedNoSwipeOnlyPicks() {
        bridge.onContactStart(1000L)

        assertTrue(flickCommit())

        verify(inputLogic, never()).takeBackWordCommittedForSwipe(anyString())
        verify(ime).onSuggestionPicked(words.getWordInfo(0), InputSource.SOFTWARE)
    }

    /** A swipe start in an earlier contact is not this flick's to undo. */
    @Test
    fun aSwipeStartFromAnEarlierContactIsForgotten() {
        bridge.onContactStart(1000L)
        bridge.onSwipeStartCommittedTypedWord("helo")
        bridge.onContactStart(2000L)

        assertTrue(flickCommit())

        verify(inputLogic, never()).takeBackWordCommittedForSwipe(anyString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
    }
}
