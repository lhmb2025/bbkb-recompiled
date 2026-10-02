package com.blackberry.nuanceshim

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audit L6 (docs/2026-09_kdb-touch-abi_audit.md §5): the PKB branch of
 * [NuanceSDK.setKeyboardSize] passes a hardcoded `(0, 0)`, and the comment justifying that is
 * an argument about the ORIGINAL app's `sPkbDimensionsMap`.
 *
 * That comment used to say the original's map had no athena entry. It has one — keyed
 * `"bbf100"`, the KEY2's model number — and the lookup misses only on a KEY2 whose
 * `Build.DEVICE` is `"athena"`. The corrected comment now rests on that string, so pin it: if
 * [NuanceSDK.DEVICE_ATHENA] and the `<build-device>` the shipped athena config matches on ever
 * drift apart, the comment's reasoning is wrong again AND the athena config stops being
 * selected (its selection is what makes "athena" a measured fact rather than an assumption).
 *
 * The config matches the model number too (2026-10-01): a KEY2 can report either string.
 *
 * Nothing here calls the engine: [NuanceSDK.setKeyboardSize]'s PKB branch ends in a `native`
 * method, so the (0,0) itself is pinned on device, not on the JVM.
 */
class PkbKeyboardSizeContractTest {

    private fun resFile(name: String): File {
        val res = sequenceOf("src/main/res", "app/src/main/res", "../app/src/main/res")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertNotNull("app/src/main/res not found from ${File(".").canonicalPath}", res)
        return File(res, name)
    }

    @Test
    fun deviceAthenaIsAStringTheShippedAthenaConfigMatchesOn() {
        val rule = shippedAthenaBuildDeviceRule()
        assertTrue(
            "NuanceSDK.DEVICE_ATHENA must be a Build.DEVICE the athena config matches on — " +
                "it is why the original app's sPkbDimensionsMap lookup misses on a KEY2 that " +
                "reports \"athena\", and why our athena config is selected there at all",
            rule.matches(NuanceSDK.DEVICE_ATHENA),
        )
    }

    @Test
    fun theShippedAthenaConfigAlsoMatchesTheModelNumber() {
        // Stock firmware and some custom LineageOS builds report the KEY2's model number in
        // ro.product.device instead of the "athena" codename. The config has to claim both, or
        // those units fall through to the generic BlackBerry config: no CKB, no Y warp.
        val rule = shippedAthenaBuildDeviceRule()
        assertTrue("the athena config must match Build.DEVICE = bbf100", rule.matches("bbf100"))
        assertTrue("the match must not depend on case", rule.matches("BBF100"))
        // The KEY2 LE (bbe100) and KEYone (bbb100) have no capacitive keypad; neither may match.
        assertTrue("the athena config must not claim the KEY2 LE", !rule.matches("bbe100"))
        assertTrue("the athena config must not claim the KEYone", !rule.matches("bbb100"))
    }

    /** The shipped athena config's `<build-device regex=...>`, compiled as the matcher does. */
    private fun shippedAthenaBuildDeviceRule(): Regex {
        val xml = resFile("xml/device_config_athena.xml").readText()
        val regex = Regex("""<build-device\s+regex="([^"]+)"""").find(xml)?.groupValues?.get(1)
        assertNotNull("device_config_athena.xml has no <build-device regex=...>", regex)
        return Regex(regex!!)
    }

    @Test
    fun deviceAthenaIsNotTheOriginalAppsModelNumberKey() {
        // The original keys its athena entry "bbf100" -> {1080, 525}. Ours is keyed by
        // Build.DEVICE. Adopting the original's key would silently hand the engine a 525-tall
        // stretch for a KDB authored 1080x450 — the interim fix reverted on 2026-08-13.
        assertTrue(
            "DEVICE_ATHENA must not be the original's model-number key",
            NuanceSDK.DEVICE_ATHENA != "bbf100",
        )
    }
}
