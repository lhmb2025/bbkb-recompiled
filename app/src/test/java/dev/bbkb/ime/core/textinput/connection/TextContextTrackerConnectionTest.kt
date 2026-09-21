package dev.bbkb.ime.core.textinput.connection

import dev.bbkb.ime.core.settings.util.SpacingAndPunctuation
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §5.3 step 12: [TextContextTracker] holds the [RichInputConnection] it listens to, instead of
 * holding an `InputLogic` and reaching through `InputLogic.mRichInputConnection` for every read.
 *
 * That reach-through was the only thing making `textinput.connection` import its own parent
 * package, and it meant the class could not be constructed at all without an `InputLogic` — which
 * needs a live `BlackBerryIME`. This test is the proof that it no longer does: the whole fixture is
 * a mock connection.
 *
 * The functional behaviour is unchanged (it is the same connection object either way), so what is
 * pinned here is the seam: reads must go to the injected connection, and the constructor must not
 * require anything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TextContextTrackerConnectionTest {

    private val connection = mock(RichInputConnection::class.java)

    /** An enabled tracker needs a locale (for the BreakIterator) and a spacing table. */
    private fun enabledTracker(): TextContextTracker = TextContextTracker(connection).apply {
        updateLocale(Locale.US, mock(SpacingAndPunctuation::class.java))
        setEnabled(true)
    }

    @Test
    fun readsTheTextItRebuildsContextFromTheInjectedConnection() {
        `when`(connection.getTextBeforeCursor(anyInt(), anyInt())).thenReturn("Hello there.")
        `when`(connection.getTextAfterCursor(anyInt(), anyInt())).thenReturn("")

        // onCursorPositionChanged with no prior context falls through to rebuildContext, which is
        // the read path. Before step 12 this dereferenced inputLogic.mRichInputConnection.
        enabledTracker().onCursorPositionChanged(12)

        verify(connection).getTextBeforeCursor(512, 0)
        verify(connection).getTextAfterCursor(512, 0)
    }

    @Test
    fun isConstructibleFromAConnectionAlone() {
        // The point of the change: no InputLogic, and therefore no BlackBerryIME, in the fixture.
        val ctor = TextContextTracker::class.java.declaredConstructors.single()
        assertEquals(
            "TextContextTracker must take exactly the connection it listens to",
            listOf(RichInputConnection::class.java),
            ctor.parameterTypes.toList(),
        )
    }

    @Test
    fun aDisabledTrackerNeverTouchesTheConnection() {
        // isEnabled() is false until both setEnabled(true) and updateLocale() have run (TI-24), and
        // every read path is behind it. Pinned so the injected connection cannot be read early.
        TextContextTracker(connection).onCursorPositionChanged(12)

        org.mockito.Mockito.verifyNoInteractions(connection)
    }
}
