package dev.bbkb.ime.core.device.detection

import android.view.KeyEvent
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [KeypadLayoutDetector]: each source in isolation, the order they are consulted in, and the
 * three device shapes that motivated the class.
 *
 * The bug being fixed: the old detector read one system property and returned `"qwerty"` whenever
 * it could not parse it. The owner's KEY2 (athena) sets `ro.hwf.keypadlanguage=1 qwerty`, but
 * several LineageOS builds set nothing at all, so an AZERTY or QWERTZ KEY2 on one of those ROMs
 * silently got the QWERTY symbol rows and no later evidence could dislodge that. Every assertion
 * below about a source *abstaining* — returning no answer rather than `"qwerty"` — is guarding
 * that regression.
 *
 * The fingerprints come from the three keymaps the KEY2 firmware ships
 * (`/vendor/usr/keychars/stmpe{,_azerty,_qwertz}.kcm`), read on the owner's device 2026-09-18.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeypadLayoutDetectorTest {

    // ── fake Alt tables, standing in for the three vendor .kcm files ─────────

    /** `KeyCharacterMap.get(keyCode, META_ALT_ON)` for one keymap; 0 for anything unlisted. */
    private fun altTable(vararg entries: Pair<Int, Char>): KeypadLayoutDetector.AltCharLookup {
        val map = entries.toMap()
        return KeypadLayoutDetector.AltCharLookup { keyCode, meta ->
            if (meta != KeyEvent.META_ALT_ON) 0 else map[keyCode]?.code ?: 0
        }
    }

    private val qwertyKcm = altTable(KeyEvent.KEYCODE_A to '*', KeyEvent.KEYCODE_Y to ')')
    private val azertyKcm = altTable(KeyEvent.KEYCODE_A to '#', KeyEvent.KEYCODE_Y to ')')
    private val qwertzKcm = altTable(KeyEvent.KEYCODE_A to '*', KeyEvent.KEYCODE_Y to '7')

    /** Generic.kcm: no Alt layer at all, so both probe lookups come back 0. */
    private val genericKcm = altTable()

    private fun detect(
        config: String? = null,
        name: String? = null,
        prop: String? = null,
        kcm: KeypadLayoutDetector.AltCharLookup? = null,
        observed: String? = null,
    ) = KeypadLayoutDetector.detect(config, name, prop, kcm, observed)

    private fun assertDetected(
        layout: String,
        source: Source,
        actual: KeypadLayoutDetector.Detection,
    ) {
        assertEquals("layout", layout, actual.layout)
        assertEquals("source", source, actual.source)
    }

    @Before
    fun clearProcessState() = KeypadLayoutDetector.resetForTest()

    @After
    fun clearProcessStateAfter() = KeypadLayoutDetector.resetForTest()

    // ── source 1: device config ─────────────────────────────────────────────

    @Test
    fun deviceConfigLayoutIsTaken() {
        assertDetected("qwertz", Source.DEVICE_CONFIG, detect(config = "qwertz"))
        assertDetected("azerty", Source.DEVICE_CONFIG, detect(config = "AZERTY"))
        assertDetected("qwerty", Source.DEVICE_CONFIG, detect(config = "  qwerty "))
    }

    @Test
    fun unrecognisedDeviceConfigLayoutAbstains() {
        // Not "fall back to qwerty with source DEVICE_CONFIG" — a junk value must let the live
        // sources below answer, which is the whole point of the rewrite.
        assertDetected("azerty", Source.DEVICE_NAME,
            detect(config = "dvorak", name = "stmpe_azerty_keypad"))
        assertDetected("qwerty", Source.FALLBACK, detect(config = ""))
    }

    // ── source 2: InputDevice name ──────────────────────────────────────────

    @Test
    fun deviceNameNamesTheLayout() {
        // Android matches /vendor/usr/idc/<device name>.idc, so a non-QWERTY KEY2 keypad must be
        // named stmpe_azerty_keypad / stmpe_qwertz_keypad for its own keymap to load at all.
        assertDetected("azerty", Source.DEVICE_NAME, detect(name = "stmpe_azerty_keypad"))
        assertDetected("qwertz", Source.DEVICE_NAME, detect(name = "stmpe_qwertz_keypad"))
        assertDetected("qwertz", Source.DEVICE_NAME, detect(name = "STMPE_QWERTZ_KEYPAD"))
    }

    @Test
    fun plainDeviceNameAbstains() {
        // The owner's QWERTY KEY2 is plain "stmpe_keypad": it names no variant, so this source
        // must say nothing rather than claim qwerty by absence.
        assertNull(KeypadLayoutDetector.fromDeviceName("stmpe_keypad"))
        assertNull(KeypadLayoutDetector.fromDeviceName("AT Translated Set 2 keyboard"))
        assertNull(KeypadLayoutDetector.fromDeviceName(null))
    }

    // ── source 3: keypad-language system property ───────────────────────────

    @Test
    fun syspropIndexAndNameIsParsed() {
        // The stock athena value.
        assertDetected("qwerty", Source.SYSPROP, detect(prop = "1 qwerty"))
        assertDetected("azerty", Source.SYSPROP, detect(prop = "2 AZERTY"))
        assertDetected("qwertz", Source.SYSPROP, detect(prop = "3 qwertz"))
    }

    @Test
    fun bareSyspropNameIsParsed() {
        assertDetected("qwertz", Source.SYSPROP, detect(prop = "qwertz"))
    }

    @Test
    fun missingOrUnparsableSyspropAbstains() {
        // This is the reporter's LineageOS case. The old code returned "qwerty" for every one of
        // these, which is exactly the wrong answer on a non-QWERTY unit.
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp(null))
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp(""))
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp("   "))
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp("1"))
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp("1 dvorak"))
    }

    // ── source 4: KCM Alt fingerprint ───────────────────────────────────────

    @Test
    fun kcmAltFingerprintSeparatesTheThreeKeymaps() {
        assertDetected("qwerty", Source.KCM, detect(kcm = qwertyKcm))
        assertDetected("azerty", Source.KCM, detect(kcm = azertyKcm))
        assertDetected("qwertz", Source.KCM, detect(kcm = qwertzKcm))
    }

    @Test
    fun kcmWithoutAnAltLayerAbstains() {
        // Both probes 0 (Generic.kcm, or no physical keyboard at all) is "unknown", not qwerty.
        assertNull(KeypadLayoutDetector.fromKeyCharacterMap(genericKcm))
        assertNull(KeypadLayoutDetector.fromKeyCharacterMap(null))
    }

    // ── source 5: scancode observation ──────────────────────────────────────

    @Test
    fun scancodeObservationReadsTheTwoMovingKeys() {
        assertEquals("azerty", KeypadLayoutDetector.layoutFromScancode(16, KeyEvent.KEYCODE_A))
        assertEquals("qwerty", KeypadLayoutDetector.layoutFromScancode(16, KeyEvent.KEYCODE_Q))
        assertEquals("qwertz", KeypadLayoutDetector.layoutFromScancode(21, KeyEvent.KEYCODE_Z))
        assertEquals("qwerty", KeypadLayoutDetector.layoutFromScancode(21, KeyEvent.KEYCODE_Y))
        // M is at scancode 50 on all three KEY2 keymaps, so it proves nothing.
        assertNull(KeypadLayoutDetector.layoutFromScancode(50, KeyEvent.KEYCODE_M))
        assertNull(KeypadLayoutDetector.layoutFromScancode(16, KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun observationUpgradesAFallbackAndAsksForOneRebuild() {
        KeypadLayoutDetector.recordForTest("qwerty", Source.FALLBACK)

        assertTrue("first azerty proof must ask for a profile rebuild",
            KeypadLayoutDetector.observeKeyEvent(16, KeyEvent.KEYCODE_A))
        assertDetected("azerty", Source.SCANCODE, KeypadLayoutDetector.last()!!)

        // Every later key with the same proof is a no-op; the rebuild must happen once.
        assertFalse(KeypadLayoutDetector.observeKeyEvent(16, KeyEvent.KEYCODE_A))
        assertFalse(KeypadLayoutDetector.observeKeyEvent(50, KeyEvent.KEYCODE_M))
    }

    @Test
    fun observationConfirmingTheFallbackAsksForNoRebuild() {
        KeypadLayoutDetector.recordForTest("qwerty", Source.FALLBACK)

        assertFalse("qwerty confirming qwerty changes nothing",
            KeypadLayoutDetector.observeKeyEvent(16, KeyEvent.KEYCODE_Q))
        assertEquals("qwerty", KeypadLayoutDetector.last()!!.layout)
    }

    @Test
    fun observationCannotDisplaceAHigherRankedSource() {
        for (source in listOf(Source.DEVICE_CONFIG, Source.DEVICE_NAME, Source.SYSPROP, Source.KCM)) {
            KeypadLayoutDetector.resetForTest()
            KeypadLayoutDetector.recordForTest("qwerty", source)
            assertFalse("$source must outrank a scancode observation",
                KeypadLayoutDetector.observeKeyEvent(16, KeyEvent.KEYCODE_A))
            assertDetected("qwerty", source, KeypadLayoutDetector.last()!!)
        }
    }

    @Test
    fun anObservationIsHonouredByTheNextDetect() {
        // The rebuild path: observeKeyEvent records the proof, DeviceProfile re-initialises, and
        // detect() runs again with the observation as its last source.
        assertDetected("azerty", Source.SCANCODE, detect(observed = "azerty"))
    }

    // ── source 6: fallback ──────────────────────────────────────────────────

    @Test
    fun nothingKnownFallsBackToQwerty() {
        assertDetected("qwerty", Source.FALLBACK, detect())
        assertDetected("qwerty", Source.FALLBACK,
            detect(name = "stmpe_keypad", prop = "", kcm = genericKcm))
    }

    // ── precedence ──────────────────────────────────────────────────────────

    @Test
    fun configBeatsNameBeatsSyspropBeatsKcmBeatsScancode() {
        // Five mutually contradictory sources, peeled off one at a time.
        assertDetected("azerty", Source.DEVICE_CONFIG, detect(
            config = "azerty", name = "stmpe_qwertz_keypad", prop = "1 qwerty",
            kcm = qwertzKcm, observed = "qwertz"))

        assertDetected("qwertz", Source.DEVICE_NAME, detect(
            name = "stmpe_qwertz_keypad", prop = "1 qwerty",
            kcm = azertyKcm, observed = "azerty"))

        assertDetected("qwerty", Source.SYSPROP, detect(
            name = "stmpe_keypad", prop = "1 qwerty",
            kcm = azertyKcm, observed = "qwertz"))

        assertDetected("azerty", Source.KCM, detect(
            name = "stmpe_keypad", prop = "", kcm = azertyKcm, observed = "qwertz"))

        assertDetected("qwertz", Source.SCANCODE, detect(
            name = "stmpe_keypad", prop = "", kcm = genericKcm, observed = "qwertz"))
    }

    // ── the device fixtures ─────────────────────────────────────────────────

    @Test
    fun athenaOnStockFirmware() {
        // Verified on the owner's KEY2 2026-09-18: InputDevice name "stmpe_keypad" (no variant in
        // the name, so source 2 abstains), ro.hwf.keypadlanguage "1 qwerty", stmpe.kcm.
        assertDetected("qwerty", Source.SYSPROP, detect(
            name = "stmpe_keypad", prop = "1 qwerty", kcm = qwertyKcm))
    }

    @Test
    fun athenaOnStockFirmwareStillAnswersWithTheSyspropGone() {
        // Same unit, a ROM that drops the property: the KCM fingerprint carries it.
        assertDetected("qwerty", Source.KCM, detect(name = "stmpe_keypad", kcm = qwertyKcm))
    }

    @Test
    fun qwertzKey2OnLineageOsWithNoSysprop() {
        // The reporter's shape. The keypad MUST be named stmpe_qwertz_keypad for the ROM to have
        // loaded stmpe_qwertz.kcm at all, so the name answers before anything else is needed.
        assertDetected("qwertz", Source.DEVICE_NAME, detect(
            name = "stmpe_qwertz_keypad", prop = null, kcm = qwertzKcm))
    }

    @Test
    fun nonBlackBerryQwertzKeypadWithAnUninformativeName() {
        // A third-party QWERTZ pad whose name says nothing and whose ROM sets no keypad property:
        // only the Alt fingerprint is left.
        assertDetected("qwertz", Source.KCM, detect(
            name = "AT Translated Set 2 keyboard", prop = null, kcm = qwertzKcm))
    }

    @Test
    fun genericKeymapNoPropsThenAnAzertyKeystroke() {
        // Nothing to go on at boot...
        val atBoot = detect(name = "keypad", prop = null, kcm = genericKcm)
        assertDetected("qwerty", Source.FALLBACK, atBoot)
        KeypadLayoutDetector.recordForTest(atBoot.layout, atBoot.source)

        // ...until the user presses the key at scancode 16 and it arrives as A.
        assertTrue(KeypadLayoutDetector.observeKeyEvent(16, KeyEvent.KEYCODE_A))

        // The rebuilt profile re-runs detect() with the proof in hand.
        assertDetected("azerty", Source.SCANCODE,
            detect(name = "keypad", prop = null, kcm = genericKcm, observed = "azerty"))
    }

    // ── the live adapter ────────────────────────────────────────────────────

    @Test
    fun detectLiveOnAJvmFallsBackAndRecordsItself() {
        // No InputDevices and no keypad properties under Robolectric — the shape every JVM test
        // of DeviceProfile runs in. It must land on FALLBACK, and must record itself so a later
        // observeKeyEvent knows it is allowed to upgrade.
        val detection = KeypadLayoutDetector.detectLive(null, null)
        assertDetected("qwerty", Source.FALLBACK, detection)
        assertDetected("qwerty", Source.FALLBACK, KeypadLayoutDetector.last()!!)
        assertTrue(KeypadLayoutDetector.observeKeyEvent(21, KeyEvent.KEYCODE_Z))
        assertDetected("qwertz", Source.SCANCODE, KeypadLayoutDetector.last()!!)
    }

    @Test
    fun detectLiveTakesTheConfigOverride() {
        assertDetected("azerty", Source.DEVICE_CONFIG,
            KeypadLayoutDetector.detectLive("azerty", null))
    }
}
