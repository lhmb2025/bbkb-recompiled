package dev.bbkb.ime.keyboard.inputboard

import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView
import dev.bbkb.ime.keyboard.auxbar.AuxBarView
import dev.bbkb.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.ref.WeakReference
import java.time.Duration

/**
 * The input-board bar's typing-delay handler: every soft key press disables the bar's keys at once
 * and restores them `config_typing_delay_inputboard_bar` ms later, so a tap that lands on the bar
 * while typing does nothing.
 *
 * Two contracts, both against a manager built by its **real constructor** (the callback under test
 * is the anonymous one that constructor creates):
 *  1. the delayed disable/restore still fires and brings the keys back after the delay;
 *  2. a pending restore message does not keep the manager reachable. `UnifiedInputBoardHandler`
 *     extends `WeakOwnerHandler`, but used to also keep the callback in a strong field — and the
 *     callback is an inner class of the manager, so a queued message pinned manager → key view →
 *     IME for the length of the delay.
 *
 * Every mock is `stubOnly()`: a recording mock keeps its invocation arguments (the manager passes
 * itself to `setOnKeyEventListener`) in Mockito's mock registry, which would hold the manager for
 * reasons that have nothing to do with the handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UnifiedInputBoardHandlerRetentionTest {

    private fun <T> stub(type: Class<T>): T = Mockito.mock(type, Mockito.withSettings().stubOnly())

    private class Built(val manager: UnifiedInputBoardManager, val handler: UnifiedInputBoardHandler)

    private fun buildManager(keyboard: Keyboard?): Built {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val keyView = stub(SimplifiedKeyboardView::class.java)
        Mockito.`when`(keyView.resources).thenReturn(context.resources)
        Mockito.`when`(keyView.keyboard).thenReturn(keyboard)
        val auxBarView = stub(AuxBarView::class.java)
        Mockito.`when`(auxBarView.sharedKeyView).thenReturn(keyView)
        val root = stub(View::class.java)
        Mockito.`when`(root.findViewById<View>(R.id.aux_bar_view)).thenReturn(auxBarView)
        val ime = stub(BlackBerryIME::class.java)
        Mockito.`when`(ime.getUiCoordinator()).thenReturn(stub(InputViewCoordinator::class.java))

        val manager = UnifiedInputBoardManager(context, root, ime)
        val handler = ReflectionHelpers.getField<UnifiedInputBoardHandler>(manager, "uimHandler")
        return Built(manager, handler)
    }

    private fun activeKey(): Key {
        val key = Mockito.mock(Key::class.java, Mockito.withSettings().stubOnly().defaultAnswer(Mockito.CALLS_REAL_METHODS))
        key.setActive(true)
        return key
    }

    private val delayMs: Long
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.getInteger(R.integer.config_typing_delay_inputboard_bar).toLong()

    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun theBarKeysAreDisabledAtOnceAndRestoredAfterTheTypingDelay() {
        val keys = listOf(activeKey(), activeKey())
        val keyboard = stub(Keyboard::class.java)
        Mockito.`when`(keyboard.keys).thenReturn(keys)
        val built = buildManager(keyboard)
        val looper = shadowOf(Looper.getMainLooper())

        built.handler.scheduleKeyStateRestore()
        looper.idle()
        assertFalse("disabled as soon as the key is typed", keys.any { it.isActive })

        // The handler holds its callback weakly, so a collection while the restore is queued must
        // not lose it: the manager (alive here) is what keeps the callback alive. Without that, the
        // restore silently never runs and the bar's keys stay disabled.
        forceGc()

        looper.idleFor(Duration.ofMillis(delayMs - 1))
        assertFalse("still disabled inside the delay", keys.any { it.isActive })

        looper.idleFor(Duration.ofMillis(1))
        assertTrue("restored once the delay has passed", keys.all { it.isActive })
        assertFalse(built.handler.hasMessages(1))
        java.lang.ref.Reference.reachabilityFence(built.manager)
    }

    @Test
    fun aPendingRestoreDoesNotKeepTheManagerReachable() {
        assertEquals(300L, delayMs)
        val (managerRef, handler) = postRestoreAndDropTheManager()
        assertTrue("the delayed restore is still queued", handler.hasMessages(1))

        assertNull("a queued restore must not pin the manager", gcUntilCleared(managerRef))

        // The message outliving its manager is a silent no-op, not a crash.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(delayMs))
        assertFalse(handler.hasMessages(1))
    }

    /** Kept out of the test body so no local slot of the test frame still holds the manager. */
    private fun postRestoreAndDropTheManager(): Pair<WeakReference<UnifiedInputBoardManager>, UnifiedInputBoardHandler> {
        val built = buildManager(null)
        built.handler.scheduleKeyStateRestore()
        return WeakReference(built.manager) to built.handler
    }

    /** Runs collections until a weakly-held probe object is actually cleared. */
    private fun forceGc() {
        val probe = WeakReference(Any())
        assertNull("no GC happened", gcUntilCleared(probe))
    }

    private fun gcUntilCleared(ref: WeakReference<*>): Any? {
        repeat(40) {
            if (ref.get() == null) return null
            @Suppress("UNUSED_VARIABLE") val pressure = ByteArray(1 shl 20)
            System.gc()
            System.runFinalization()
            Thread.sleep(5)
        }
        return ref.get()
    }
}
