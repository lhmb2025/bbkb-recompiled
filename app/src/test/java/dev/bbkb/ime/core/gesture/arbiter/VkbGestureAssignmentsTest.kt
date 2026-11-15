package dev.bbkb.ime.core.gesture.arbiter

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The on-screen swipe slots and the one-time move from the two old switches ("Swipe gestures",
 * which only mattered with swipe typing on, and "Swipe down to dismiss"). Nobody's behavior may
 * change on upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VkbGestureAssignmentsTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("vkb_gestures_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun migrate(swipeTypingDefault: Boolean = false) =
        VkbGestureAssignments.migrate(prefs, swipeTypingDefault)

    private fun enabled() = prefs.getBoolean(VkbGestureAssignments.KEY_ENABLED, true)
    private fun slots() = VkbGestureAssignments.fromPrefs(prefs)

    @Test
    fun defaults_matchWhatTheKeyboardDidBefore() {
        val d = VkbGestureAssignments()
        assertEquals(GestureAction.DELETE_WORD, d.forSwipe(VkbSwipe.LEFT))
        assertEquals(GestureAction.NONE, d.forSwipe(VkbSwipe.RIGHT))
        assertEquals(GestureAction.DISMISS_KEYBOARD, d.forSwipe(VkbSwipe.DOWN))
    }

    @Test
    fun choices_excludeCursorModeAndCommit_includeTheRest() {
        val options = VkbGestureAssignments.assignableActions
        assertFalse(GestureAction.ENTER_CURSOR_MODE in options)
        assertFalse(GestureAction.COMMIT_SUGGESTION in options)
        for (a in listOf(GestureAction.NONE, GestureAction.DELETE_WORD, GestureAction.NEXT_LANGUAGE,
                GestureAction.CYCLE_SYMBOLS, GestureAction.DISMISS_KEYBOARD, GestureAction.UNDO, GestureAction.EMOJI)) {
            assertTrue("$a", a in options)
        }
    }

    @Test
    fun storedActionsTheKeyboardDoesNotOffer_readAsTheDefault() {
        prefs.edit()
            .putString(VkbGestureAssignments.KEY_SWIPE_LEFT, GestureAction.ENTER_CURSOR_MODE.key)
            .putString(VkbGestureAssignments.KEY_SWIPE_RIGHT, GestureAction.COMMIT_SUGGESTION.key)
            .putString(VkbGestureAssignments.KEY_SWIPE_DOWN, GestureAction.EMOJI.key)
            .commit()
        assertEquals(VkbGestureAssignments(swipeDown = GestureAction.EMOJI), slots())
    }

    @Test
    fun aProfileDefaultOfCursorMode_isIgnored() {
        val d = VkbGestureAssignments.defaults { key ->
            when (key) {
                VkbGestureAssignments.KEY_SWIPE_LEFT -> "enter_cursor_mode"
                VkbGestureAssignments.KEY_SWIPE_RIGHT -> "undo"
                else -> null
            }
        }
        assertEquals(VkbGestureAssignments(swipeRight = GestureAction.UNDO), d)
    }

    @Test
    fun freshInstall_gesturesOn_defaultSlots() {
        migrate()
        assertTrue(enabled())
        assertEquals(VkbGestureAssignments(), slots())
    }

    @Test
    fun swipeTypingOn_oldSwitchNeverSet_gesturesStayOff() {
        prefs.edit().putBoolean("type_by_swiping_vkb", true).commit()
        migrate()
        assertFalse("they only had the prediction flick", enabled())
    }

    @Test
    fun swipeTypingOnByDefault_oldSwitchNeverSet_gesturesStayOff() {
        migrate(swipeTypingDefault = true)
        assertFalse(enabled())
    }

    @Test
    fun swipeTypingOn_oldSwitchOn_gesturesStayOn() {
        prefs.edit().putBoolean("type_by_swiping_vkb", true).putBoolean("swipe_gesture_vkb", true).commit()
        migrate()
        assertTrue(enabled())
    }

    @Test
    fun swipeTypingOff_oldSwitchOff_gesturesStayOn_becauseTheSwitchDidNothing() {
        prefs.edit().putBoolean("swipe_gesture_vkb", false).commit()
        migrate()
        assertTrue(enabled())
    }

    @Test
    fun swipeDownToDismissOff_becomesCycleSymbols_andTheOldKeyGoes() {
        prefs.edit().putBoolean("swipe_down_vkb", false).commit()
        migrate()
        assertEquals(GestureAction.CYCLE_SYMBOLS, slots().swipeDown)
        assertFalse(prefs.contains("swipe_down_vkb"))
    }

    @Test
    fun swipeDownToDismissOn_staysDismiss() {
        prefs.edit().putBoolean("swipe_down_vkb", true).commit()
        migrate()
        assertEquals(GestureAction.DISMISS_KEYBOARD, slots().swipeDown)
        assertFalse(prefs.contains("swipe_down_vkb"))
    }

    @Test
    fun runsOnce_laterChoicesStand() {
        migrate()
        prefs.edit().putBoolean(VkbGestureAssignments.KEY_ENABLED, false)
            .putString(VkbGestureAssignments.KEY_SWIPE_DOWN, GestureAction.UNDO.key).commit()
        migrate()
        assertFalse(enabled())
        assertEquals(GestureAction.UNDO, slots().swipeDown)
    }

    @Test
    fun restoringAnOlderBackup_isConvertedAgain() {
        migrate()
        // An old backup brings the pre-slot keys back over the migrated ones.
        prefs.edit().putBoolean("swipe_down_vkb", false)
            .putBoolean("type_by_swiping_vkb", true).putBoolean("swipe_gesture_vkb", false).commit()
        migrate()
        assertEquals(GestureAction.CYCLE_SYMBOLS, slots().swipeDown)
        assertFalse(enabled())
        assertFalse(prefs.contains("swipe_down_vkb"))
    }
}
