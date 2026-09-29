package dev.bbkb.ime.core.settings.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice picker's fallback list when the recogniser cannot be asked. */
class VoiceLanguageFallbackTest {

    @Test
    fun aRememberedDiscoveryWinsOutright() {
        assertEquals(listOf("he", "el-GR"), composeFallback(listOf("he", "el-GR"), listOf("en-US", "de")))
    }

    @Test
    fun otherwiseTheKeyboardsLanguagesJoinTheStaticListSortedWithoutDuplicates() {
        val list = composeFallback(emptyList(), listOf("he", "el", "uk", "en-US", "de"))
        for (tag in listOf("he", "el", "uk", "de", "en-US", "en-GB", "ja-JP")) {
            assertTrue("missing $tag in $list", tag in list)
        }
        assertEquals(list.sorted(), list)
        assertEquals(list.distinct().size, list.size)
    }
}
