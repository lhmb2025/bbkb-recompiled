package dev.bbkb.ime.core.textinput

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The lock state the clipboard and input menu honour follows screen and unlock broadcasts (no
 * TrustManager call per field) and is right on the first field, after screen off, and on the lock screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceLockStateTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val keyguard get() = shadowOf(app.getSystemService(KeyguardManager::class.java))

    @Before
    fun setUp() {
        InputMethodHelper.resetLockStateForTest()
        InputMethodHelper.init(app)
    }

    @After
    fun tearDown() {
        InputMethodHelper.resetLockStateForTest()
    }

    private fun broadcast(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theFirstFieldAsksAndLaterFieldsDoNot() {
        keyguard.setIsDeviceLocked(true)
        assertTrue(InputMethodHelper.isDeviceLocked())
        keyguard.setIsDeviceLocked(false)
        // No event yet: the kept answer stands, with no per-field call.
        assertTrue(InputMethodHelper.isDeviceLocked())
    }

    @Test
    fun anUnlockIsFollowed() {
        keyguard.setIsDeviceLocked(true)
        assertTrue(InputMethodHelper.isDeviceLocked())
        keyguard.setIsDeviceLocked(false)
        broadcast(Intent.ACTION_USER_PRESENT)
        assertFalse(InputMethodHelper.isDeviceLocked())
    }

    @Test
    fun theScreenGoingOffAsksAgain() {
        keyguard.setIsDeviceLocked(false)
        assertFalse(InputMethodHelper.isDeviceLocked())
        keyguard.setIsDeviceLocked(true)
        broadcast(Intent.ACTION_SCREEN_OFF)
        assertTrue(InputMethodHelper.isDeviceLocked())
    }

    @Test
    fun theLockScreensOwnFieldAsksAgain() {
        keyguard.setIsDeviceLocked(false)
        assertFalse(InputMethodHelper.isDeviceLocked())
        keyguard.setIsDeviceLocked(true)
        InputMethodHelper.onEditorStarted(EditorInfo().apply { packageName = "com.example.app" })
        assertFalse(InputMethodHelper.isDeviceLocked())
        InputMethodHelper.onEditorStarted(EditorInfo().apply { packageName = "com.android.systemui" })
        assertTrue(InputMethodHelper.isDeviceLocked())
    }
}
