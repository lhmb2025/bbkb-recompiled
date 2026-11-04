package dev.bbkb.ime.core.locale

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Looper
import android.speech.RecognitionService
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * P-12: the input-method caches survive field focus and are dropped by the events that change
 * them (RichInputMethodManager's invalidation matrix); the values built on them are asked for at
 * most once per generation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InputMethodCacheInvalidationTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val rimm get() = RichInputMethodManager.getInstance()

    private val subtype: InputMethodSubtype = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale("en_US")
        .setSubtypeMode("keyboard")
        .setSubtypeExtraValue("KeyboardLayoutSet=qwerty")
        .build()

    @Before
    fun setUp() {
        RichInputMethodManager.resetForTest()
        RichInputMethodManager.init(app)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        RichInputMethodManager.resetForTest()
    }

    /** Puts a mock InputMethodManager behind the singleton; this IME is enabled with [subtype]. */
    private fun mockImm(): InputMethodManager {
        val imm = mock(InputMethodManager::class.java)
        val thisIme = InputMethodInfo(app.packageName, "Ime", "BBKB", null)
        `when`(imm.inputMethodList).thenReturn(listOf(thisIme))
        `when`(imm.getEnabledInputMethodSubtypeList(thisIme, true)).thenReturn(listOf(subtype))
        `when`(imm.currentInputMethodSubtype).thenReturn(subtype)
        `when`(imm.shortcutInputMethodsAndSubtypes).thenReturn(emptyMap())
        RichInputMethodManager::class.java.getDeclaredField("imm").apply { isAccessible = true }.set(rimm, imm)
        rimm.clearSubtypeCaches()
        return imm
    }

    @Test
    fun fieldFocusKeepsTheCaches() {
        val imm = mockImm()
        repeat(5) {
            rimm.onInputViewStarted()
            rimm.hasMultipleEnabledSubtypesInThisIme(false)
            rimm.getMyEnabledInputMethodSubtypeList(true)
        }
        verify(imm, times(1)).inputMethodList
        verify(imm, times(1)).getEnabledInputMethodSubtypeList(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(true))
    }

    @Test
    fun theCachesAgeOut() {
        val imm = mockImm()
        rimm.getMyEnabledInputMethodSubtypeList(true)
        val generation = rimm.cacheGeneration
        rimm.onInputViewStarted()
        assertEquals(generation, rimm.cacheGeneration)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RichInputMethodManager.MAX_CACHE_AGE_MS + 1))
        rimm.onInputViewStarted()
        assertNotEquals(generation, rimm.cacheGeneration)
        rimm.getMyEnabledInputMethodSubtypeList(true)
        verify(imm, times(2)).inputMethodList
    }

    @Test
    fun aPackageChangeDropsTheCaches() {
        val generation = rimm.cacheGeneration
        app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:com.example.voice")))
        shadowOf(Looper.getMainLooper()).idle()
        assertNotEquals(generation, rimm.cacheGeneration)
    }

    @Test
    fun recognitionIsAskedOncePerGeneration() {
        assertFalse(rimm.isRecognitionAvailable(app))
        val service = ComponentName("com.example.voice", "com.example.voice.Recogniser")
        val pm = shadowOf(app.packageManager)
        pm.addServiceIfNotPresent(service)
        pm.addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        assertFalse("kept until something drops the caches", rimm.isRecognitionAvailable(app))
        rimm.clearSubtypeCaches()
        assertTrue(rimm.isRecognitionAvailable(app))
    }

    @Test
    fun offeringTheSwitcherIsAskedOncePerGeneration() {
        val ims = mock(InputMethodService::class.java)
        `when`(ims.shouldOfferSwitchingToNextInputMethod()).thenReturn(true)
        assertTrue(rimm.shouldOfferSwitchingToNextInputMethod(ims))
        assertTrue(rimm.shouldOfferSwitchingToNextInputMethod(ims))
        verify(ims, times(1)).shouldOfferSwitchingToNextInputMethod()
        `when`(ims.shouldOfferSwitchingToNextInputMethod()).thenReturn(false)
        rimm.clearSubtypeCaches()
        assertFalse(rimm.shouldOfferSwitchingToNextInputMethod(ims))
        verify(ims, times(2)).shouldOfferSwitchingToNextInputMethod()
    }

    @Test
    fun theEnabledSubtypeCheckRunsOncePerGeneration() {
        val imm = mockImm()
        val ims = mock(InputMethodService::class.java)
        repeat(3) { assertFalse(rimm.moveToEnabledSubtypeIfCurrentIsNot(ims)) }
        verify(imm, times(1)).currentInputMethodSubtype
        rimm.clearSubtypeCaches()
        assertFalse(rimm.moveToEnabledSubtypeIfCurrentIsNot(ims))
        verify(imm, times(2)).currentInputMethodSubtype
    }

    @Test
    fun theShortcutImeIsReadAgainOnlyAfterAnInvalidation() {
        val imm = mockImm()
        SubtypeManager.initialize(app)
        // initialize() reads it once, through onSubtypeChanged.
        verify(imm, times(1)).shortcutInputMethodsAndSubtypes
        repeat(3) { SubtypeManager.getInstance().updateParametersOnStartInputView() }
        verify(imm, times(1)).shortcutInputMethodsAndSubtypes
        rimm.clearSubtypeCaches()
        SubtypeManager.getInstance().updateParametersOnStartInputView()
        SubtypeManager.getInstance().updateParametersOnStartInputView()
        verify(imm, times(2)).shortcutInputMethodsAndSubtypes
    }

    @Test
    fun aSubtypeChangeDropsTheCaches() {
        mockImm()
        SubtypeManager.initialize(app)
        val generation = rimm.cacheGeneration
        SubtypeManager.getInstance().onSubtypeChanged(subtype)
        assertNotEquals(generation, rimm.cacheGeneration)
    }
}
