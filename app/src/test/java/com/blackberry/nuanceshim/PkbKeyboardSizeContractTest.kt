package com.blackberry.nuanceshim

import org.junit.Assert.assertEquals
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
 * `"bbf100"`, the KEY2's model number — and the lookup misses only because the KEY2's
 * `Build.DEVICE` is `"athena"`. The corrected comment now rests on that string, so pin it: if
 * [NuanceSDK.DEVICE_ATHENA] and the `<build-device>` the shipped athena config matches on ever
 * drift apart, the comment's reasoning is wrong again AND the athena KDB variant stops being
 * selected (its selection is what makes "athena" a measured fact rather than an assumption).
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
    fun deviceAthenaIsTheStringTheShippedAthenaConfigMatchesOn() {
        val xml = resFile("xml/device_config_athena.xml").readText()
        val exact = Regex("""<build-device\s+exact="([^"]+)"""").find(xml)?.groupValues?.get(1)
        assertNotNull("device_config_athena.xml has no <build-device exact=...>", exact)
        assertEquals(
            "NuanceSDK.DEVICE_ATHENA must be the Build.DEVICE the athena config matches on — " +
                "it is why the original app's sPkbDimensionsMap lookup misses on the KEY2, and " +
                "why our athena KDB variant is selected there at all",
            exact,
            NuanceSDK.DEVICE_ATHENA,
        )
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
