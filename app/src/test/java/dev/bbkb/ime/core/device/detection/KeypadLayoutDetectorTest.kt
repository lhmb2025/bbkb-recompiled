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
 * Pins [KeypadLayoutDetector]: each source alone, their order, and real device shapes. A source
 * with no evidence must abstain, not answer "qwerty" (some ROMs set no keypad property). The fake
 * Alt tables mirror the KEY2 firmware's `/vendor/usr/keychars/stmpe{,_azerty,_qwertz}.kcm`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeypadLayoutDetectorTest {
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

    @Test
    fun deviceConfigLayoutIsTaken() {
        assertDetected("qwertz", Source.DEVICE_CONFIG, detect(config = "qwertz"))
        assertDetected("azerty", Source.DEVICE_CONFIG, detect(config = "AZERTY"))
        assertDetected("qwerty", Source.DEVICE_CONFIG, detect(config = "  qwerty "))
    }

    @Test
    fun unrecognisedDeviceConfigLayoutAbstains() {
        // A junk value must let the live sources below answer, not fall back to qwerty.
        assertDetected("azerty", Source.DEVICE_NAME,
            detect(config = "dvorak", name = "stmpe_azerty_keypad"))
        assertDetected("qwerty", Source.FALLBACK, detect(config = ""))
    }

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
        // A QWERTY KEY2 is plain "stmpe_keypad": no variant, so this source must abstain.
        assertNull(KeypadLayoutDetector.fromDeviceName("stmpe_keypad"))
        assertNull(KeypadLayoutDetector.fromDeviceName("AT Translated Set 2 keyboard"))
        assertNull(KeypadLayoutDetector.fromDeviceName(null))
    }

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
        // Missing or unparseable (some LineageOS builds): abstain, never "qwerty".
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp(null))
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp(""))
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp("   "))
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp("1"))
        assertNull(KeypadLayoutDetector.fromKeypadLanguageProp("1 dvorak"))
    }

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

    @Test
    fun nothingKnownFallsBackToQwerty() {
        assertDetected("qwerty", Source.FALLBACK, detect())
        assertDetected("qwerty", Source.FALLBACK,
            detect(name = "stmpe_keypad", prop = "", kcm = genericKcm))
    }

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

    @Test
    fun athenaOnStockFirmware() {
        // A stock KEY2: InputDevice "stmpe_keypad" (source 2 abstains), ro.hwf.keypadlanguage
        // "1 qwerty", stmpe.kcm.
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
        // A QWERTZ KEY2 on a ROM with no property: the keypad must be named stmpe_qwertz_keypad
        // for stmpe_qwertz.kcm to load, so the name answers first.
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

    @Test
    fun detectLiveOnAJvmFallsBackAndRecordsItself() {
        // Robolectric has no InputDevices or keypad properties: FALLBACK, recorded so a later
        // observeKeyEvent may upgrade it.
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

    @Test
    fun theUsersChoiceOutranksEverySource() {
        val user = KeypadLayoutDetector.detect("azerty", "qwertz", "stmpe_qwertz_keypad", "1 qwerty", null, "qwerty")
        assertEquals("azerty", user.layout)
        assertEquals(KeypadLayoutDetector.Source.USER, user.source)
        val none = KeypadLayoutDetector.detect("", "qwertz", "stmpe_qwertz_keypad", "1 qwerty", null, "qwerty")
        assertEquals("an empty choice abstains", KeypadLayoutDetector.Source.DEVICE_CONFIG, none.source)
        val unknown = KeypadLayoutDetector.detect("dvorak", null, null, null, null, null)
        assertEquals("a name that is not a shape abstains", KeypadLayoutDetector.Source.FALLBACK, unknown.source)
    }
}
