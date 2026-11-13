package com.blackberry.nuanceshim

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [NuanceSDK.DEVICE_ATHENA] and the shipped athena config's `<build-device>` rule must agree
 * (the PKB branch of [NuanceSDK.setKeyboardSize] relies on it); the rule also matches the KEY2
 * model number. The `(0, 0)` size itself is a native call, pinned on device only.
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
        // Some ROMs report the model number instead of "athena"; without a match those units get
        // the generic BlackBerry config (no CKB, no Y warp).
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
        // A "bbf100" key maps to {1080, 525}: a 525-tall stretch for a KDB authored 1080x450.
        assertTrue(
            "DEVICE_ATHENA must not be the original's model-number key",
            NuanceSDK.DEVICE_ATHENA != "bbf100",
        )
    }
}
