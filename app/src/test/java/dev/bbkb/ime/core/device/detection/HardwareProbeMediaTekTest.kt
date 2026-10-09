package dev.bbkb.ime.core.device.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HardwareProbe.isMediaTek]: the Touch surface helper names Shizuku 13.5.4 on MediaTek phones,
 * because 13.6.0 does not work fully there. Pure JVM.
 */
class HardwareProbeMediaTekTest {

    @Test
    fun theTitansAreMediaTek() {
        // The original Titan: Build.HARDWARE mt6771.
        assertTrue(HardwareProbe.isMediaTek("mt6771", "Titan", null, null))
        // An Android 12+ phone that names its SoC.
        assertTrue(HardwareProbe.isMediaTek("unknown", "G71BoardV1", "Mediatek", "MT6789"))
        assertTrue(HardwareProbe.isMediaTek(null, null, "MediaTek Inc.", null))
        assertTrue(HardwareProbe.isMediaTek(null, "MT6893", null, null))
        assertTrue(HardwareProbe.isMediaTek(null, null, null, "mt6833v"))
    }

    @Test
    fun otherChipsAreNot() {
        assertFalse(HardwareProbe.isMediaTek("qcom", "sdm660", "QTI", "SDM660")) // KEY2
        assertFalse(HardwareProbe.isMediaTek("ranchu", "goldfish_x86_64", "unknown", "unknown"))
        assertFalse(HardwareProbe.isMediaTek("exynos", "universal9810", "Samsung", "s5e9810"))
        assertFalse("mt without a part number", HardwareProbe.isMediaTek("mtk", "smt100", null, null))
        assertFalse(HardwareProbe.isMediaTek(null, null, null, null))
    }
}
