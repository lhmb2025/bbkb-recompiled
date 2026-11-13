package dev.bbkb.ime.core.engine

import dev.bbkb.ime.personaldictionary.DictionaryManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The trailing-punctuation retry in `NuanceSDKDictionaryBridge.getWordSubstitution`: with one
 * trailing ASCII punctuation char stripped, the lookup retries and re-appends it, so `bb.` expands.
 * Also pins the bail-out that compares the stripped input with the full input's result. Driven on a
 * Mockito mock of the bridge (private methods are not intercepted, so the real body runs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class MacroTrailingPunctuationTest {

    private val en = Locale.US

    /**
     * Run the real `getWordSubstitution` against a personal dictionary that expands exactly the
     * entries in [macros] and returns everything else verbatim.
     */
    private fun substitute(typed: String, macros: Map<String, String>): String {
        val store = mock(DictionaryManager::class.java)
        `when`(store.getWordSubstitutionForInput(any(), any(), any())).thenAnswer { inv ->
            val input = inv.arguments[0] as String
            macros[input] ?: input
        }
        mockStatic(DictionaryManager::class.java).use { dictStatic ->
            dictStatic.`when`<DictionaryManager> { DictionaryManager.getInstance() }.thenReturn(store)
            val bridge = mock(NuanceSDKDictionaryBridge::class.java)
            val m = NuanceSDKDictionaryBridge::class.java.getDeclaredMethod(
                "getWordSubstitution",
                String::class.java,
                DictionaryManager.CapsMode::class.java,
                Locale::class.java,
            ).apply { isAccessible = true }
            return m.invoke(bridge, typed, DictionaryManager.CapsMode.NO_CAPS, en) as String
        }
    }

    @Test
    fun plainMacro_expands() {
        assertEquals("BlackBerry", substitute("bb", mapOf("bb" to "BlackBerry")))
    }

    @Test
    fun macroWithTrailingPeriod_expandsAndKeepsThePeriod() {
        assertEquals("BlackBerry.", substitute("bb.", mapOf("bb" to "BlackBerry")))
    }

    @Test
    fun macroWithTrailingComma_expandsAndKeepsTheComma() {
        assertEquals("a lot,", substitute("alot,", mapOf("alot" to "a lot")))
    }

    @Test
    fun nonMacroWithTrailingPunctuation_isUnchanged() {
        // The stripped word has no substitution either, so the retry re-appends the punctuation
        // to the unchanged stem and the caller's `!equals(typed)` test rejects it.
        assertEquals("hello.", substitute("hello.", emptyMap()))
    }

    @Test
    fun wordWithNoTrailingPunctuation_takesTheFastPath() {
        assertEquals("hello", substitute("hello", emptyMap()))
    }

    @Test
    fun bareLonePunctuation_isUnchanged() {
        // Stripping leaves an empty stem, which the length guard rejects before the retry.
        assertEquals(".", substitute(".", emptyMap()))
    }

    @Test
    fun theOriginalsOddBailOut_isPreserved() {
        // The bail-out compares the stripped input with the full input's result, not the retry's,
        // so a macro mapping `x.` to `x` returns that; without it this would be "X." instead.
        assertEquals("x", substitute("x.", mapOf("x." to "x", "x" to "X")))
    }

    @Test
    fun caseModeAndLocale_areForwardedToBothLookups() {
        val store = mock(DictionaryManager::class.java)
        `when`(store.getWordSubstitutionForInput(any(), any(), any())).thenAnswer { inv ->
            inv.arguments[0] as String
        }
        mockStatic(DictionaryManager::class.java).use { dictStatic ->
            dictStatic.`when`<DictionaryManager> { DictionaryManager.getInstance() }.thenReturn(store)
            val bridge = mock(NuanceSDKDictionaryBridge::class.java)
            val m = NuanceSDKDictionaryBridge::class.java.getDeclaredMethod(
                "getWordSubstitution",
                String::class.java,
                DictionaryManager.CapsMode::class.java,
                Locale::class.java,
            ).apply { isAccessible = true }
            m.invoke(bridge, "bb.", DictionaryManager.CapsMode.ALL_CAPS, Locale.FRANCE)
            // Both the full form and the stripped retry carry the caps mode and locale through.
            org.mockito.Mockito.verify(store)
                .getWordSubstitutionForInput(eq("bb."), eq(DictionaryManager.CapsMode.ALL_CAPS), eq(Locale.FRANCE))
            org.mockito.Mockito.verify(store)
                .getWordSubstitutionForInput(eq("bb"), eq(DictionaryManager.CapsMode.ALL_CAPS), eq(Locale.FRANCE))
        }
    }
}
