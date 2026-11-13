package com.blackberry.nuanceshim.languagepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [LanguagePackInstaller]'s pack lookup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LanguagePackInstallerTest {

    /**
     * The catalogs spell Indonesian `in` and Hebrew `iw`, but a `Locale` reports `id` / `he` on
     * Java 17 and on Android 15 (targetSdk 35+); the lookup must map them back.
     */
    @Test
    fun retiredLanguageCodesStillFindTheirPack() {
        val context = RuntimeEnvironment.getApplication()
        val registry = LanguagePackRegistry()
        for (language in listOf("in", "iw")) {
            registry.addLanguagePack(LanguagePackInfo().apply {
                this.language = language
                country = null
                name = language
                path = "ldb/$language"
                version = 1.0
                setPreinstalledFlag(false)
            })
        }

        for (spelling in listOf("in", "id", "iw", "he")) {
            val byLocale = LanguagePackInstaller(context, java.util.Locale(spelling), registry)
            assertTrue("no pack found for Locale(\"$spelling\")", byLocale.isSupported())
        }
        assertEquals("in", LanguagePackInstaller(context, java.util.Locale("id"), registry).getLocaleIdentifier())
        assertEquals("iw", LanguagePackInstaller(context, java.util.Locale("he"), registry).getLocaleIdentifier())
    }
}
