package dev.bbkb.ime.core.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `enabled_input_methods` parsing, against the value shape the owner's KEY2 actually stores.
 *
 * `UncachedInputMethodManagerUtils.isThisImeEnabled` used to compare our id against
 * `default_input_method`, i.e. it answered "are we the selected keyboard?" while every caller
 * read it as "are we switched on?". `SystemBroadcastReceiver` acted on that answer by killing
 * the process, so any boot with another IME selected cost us the process (2026-09-21).
 *
 * Robolectric only for `android.text.TextUtils`; nothing here touches a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnabledInputMethodsParsingTest {

    private val ourId = "dev.bbkb.ime.debug/dev.bbkb.ime.core.BlackBerryIME"

    /** The real KEY2 value: our id carries two subtype hashes, AOSP LatinIME follows. */
    private val key2Value =
        "dev.bbkb.ime.debug/dev.bbkb.ime.core.BlackBerryIME;242746067;-921088104" +
            ":com.android.inputmethod.latin/.LatinIME" +
            ":com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService"

    @Test
    fun ourIdWithSubtypeHashesIsFound() {
        assertTrue(UncachedInputMethodManagerUtils.isImeIdEnabled(ourId, key2Value))
    }

    /** Every other entry in that same value is matched too - the split is not position-dependent. */
    @Test
    fun laterEntriesAreFound() {
        assertTrue(
            UncachedInputMethodManagerUtils.isImeIdEnabled(
                "com.android.inputmethod.latin/.LatinIME", key2Value
            )
        )
        assertTrue(
            UncachedInputMethodManagerUtils.isImeIdEnabled(
                "com.google.android.tts/com.google.android.apps.speech.tts.googletts" +
                    ".settings.asr.voiceime.VoiceInputMethodService",
                key2Value
            )
        )
    }

    /** An id that is only a prefix of an enabled one must not match. */
    @Test
    fun prefixOfAnEnabledIdIsNotEnabled() {
        assertFalse(UncachedInputMethodManagerUtils.isImeIdEnabled("com.android.inputmethod.latin/.Latin", key2Value))
        assertFalse(UncachedInputMethodManagerUtils.isImeIdEnabled("dev.bbkb.ime.debug", key2Value))
    }

    /**
     * ...and neither must the release id when the debug build is the enabled one. Since the app
     * moved to its own id, the *original* BlackBerry Keyboard can be installed alongside this one,
     * so a neighbouring `com.blackberry.keyboard` entry must not read as us either. Only the
     * package half of that second id is the original app's; the class half is spelled with our
     * own class root, since this app no longer writes the original's down anywhere.
     */
    @Test
    fun aDifferentPackageIsNotEnabled() {
        assertFalse(
            UncachedInputMethodManagerUtils.isImeIdEnabled(
                "dev.bbkb.ime/dev.bbkb.ime.core.BlackBerryIME", key2Value
            )
        )
        assertFalse(
            UncachedInputMethodManagerUtils.isImeIdEnabled(
                "com.blackberry.keyboard/dev.bbkb.ime.core.BlackBerryIME", key2Value
            )
        )
    }

    /** A single entry, with and without subtype hashes. */
    @Test
    fun singleEntryValues() {
        assertTrue(UncachedInputMethodManagerUtils.isImeIdEnabled(ourId, ourId))
        assertTrue(UncachedInputMethodManagerUtils.isImeIdEnabled(ourId, "$ourId;1;2;3"))
        assertTrue(UncachedInputMethodManagerUtils.isImeIdEnabled(ourId, "$ourId;"))
    }

    /** Our id last in the list, i.e. the final entry has no trailing separator. */
    @Test
    fun lastEntryIsFound() {
        assertTrue(
            UncachedInputMethodManagerUtils.isImeIdEnabled(ourId, "com.android.inputmethod.latin/.LatinIME:$ourId")
        )
    }

    /** Degenerate values answer "not enabled" rather than throwing. */
    @Test
    fun emptyAndNullValues() {
        assertFalse(UncachedInputMethodManagerUtils.isImeIdEnabled(ourId, null))
        assertFalse(UncachedInputMethodManagerUtils.isImeIdEnabled(ourId, ""))
        assertFalse(UncachedInputMethodManagerUtils.isImeIdEnabled(ourId, ":::"))
        assertFalse(UncachedInputMethodManagerUtils.isImeIdEnabled(null, key2Value))
        assertFalse(UncachedInputMethodManagerUtils.isImeIdEnabled("", key2Value))
    }

    /**
     * The regression itself: being the enabled-but-not-default IME is "enabled". The old check
     * compared against `default_input_method`, which on the KEY2 held the LatinIME id.
     */
    @Test
    fun enabledButNotDefaultIsStillEnabled() {
        val defaultInputMethod = "com.android.inputmethod.latin/.LatinIME"
        assertTrue(UncachedInputMethodManagerUtils.isImeIdEnabled(ourId, key2Value))
        assertFalse("precondition: we are not the default IME", ourId == defaultInputMethod)
    }
}
