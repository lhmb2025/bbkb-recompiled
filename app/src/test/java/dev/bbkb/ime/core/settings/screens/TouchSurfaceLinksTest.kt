package dev.bbkb.ime.core.settings.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Touch surface helper's links: the OEM touchpad pages by their exact component names (the
 * OEM's "Cusor" spelling included), and the fallback chain when a page is missing on this
 * firmware or not exported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TouchSurfaceLinksTest {

    @Test
    fun theOemPagesAreNamedExactly() {
        assertEquals("com.agui.settings/com.agui.settings.touchpad.ScrollAssistantActivity",
            TouchSurfaceLinks.SCROLL_ASSISTANT.flattenToString())
        assertEquals("com.agui.settings/com.agui.settings.touchpad.CusorMoveAssistantActivity",
            TouchSurfaceLinks.CURSOR_ASSISTANT.flattenToString())
    }

    @Test
    fun scrollAssistant_fallsBackToGestures_thenToTheSettingsApp() {
        val chain = TouchSurfaceLinks.scrollAssistant()
        assertEquals(TouchSurfaceLinks.SCROLL_ASSISTANT, chain[0].component)
        assertEquals(TouchSurfaceLinks.SYSTEM_GESTURES, chain[1].component)
        assertEquals(Settings.ACTION_SETTINGS, chain[2].action)
        assertEquals(TouchSurfaceLinks.CURSOR_ASSISTANT, TouchSurfaceLinks.cursorAssistant()[0].component)
        assertEquals(chain.drop(1).map { it.toUri(0) }, TouchSurfaceLinks.cursorAssistant().drop(1).map { it.toUri(0) })
    }

    @Test
    fun aMissingPage_movesOnToTheNext() {
        val tried = mutableListOf<Intent>()
        val opened = TouchSurfaceLinks.openFirst(TouchSurfaceLinks.scrollAssistant()) { intent ->
            tried += intent
            if (intent.component == TouchSurfaceLinks.SCROLL_ASSISTANT) throw ActivityNotFoundException()
        }
        assertEquals(TouchSurfaceLinks.SYSTEM_GESTURES, opened!!.component)
        assertEquals(2, tried.size)
    }

    @Test
    fun anUnexportedPage_movesOnToo_andTheSettingsAppIsTheLastResort() {
        val opened = TouchSurfaceLinks.openFirst(TouchSurfaceLinks.cursorAssistant()) { intent ->
            when (intent.component) {
                TouchSurfaceLinks.CURSOR_ASSISTANT -> throw ActivityNotFoundException()
                TouchSurfaceLinks.SYSTEM_GESTURES -> throw SecurityException("not exported")
            }
        }
        assertEquals(Settings.ACTION_SETTINGS, opened!!.action)
    }

    @Test
    fun nothingOpens_isReportedAsNull() {
        assertNull(TouchSurfaceLinks.openFirst(TouchSurfaceLinks.scrollAssistant()) {
            throw ActivityNotFoundException()
        })
    }

    @Test
    fun installShizuku_opensTheReleasesPage() {
        val intent = TouchSurfaceLinks.installShizuku().single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/RikkaApps/Shizuku/releases", intent.dataString)
    }
}
