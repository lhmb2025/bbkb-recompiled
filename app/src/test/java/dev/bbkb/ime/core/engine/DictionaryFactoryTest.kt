package dev.bbkb.ime.core.engine

import android.content.Context
import dev.bbkb.ime.core.locale.SubtypeManager
import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * CHARACTERISATION of [DictionaryFactory] — Wave 3b package G1.
 *
 * The factory's decision: `locale == null` -> [FallbackDictionary] without touching the engine;
 * otherwise a [NuanceSDKDictionaryBridge] over the primary (or, with `secondary`, the secondary)
 * engine instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class DictionaryFactoryTest {

    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private lateinit var lpmStatic: MockedStatic<LanguagePackManager>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var primary: NuanceSDK
    private lateinit var secondary: NuanceSDK
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        primary = mock(NuanceSDK::class.java)
        secondary = mock(NuanceSDK::class.java)
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(primary)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getSecondary() }.thenReturn(secondary)
        // The bridge's constructor asks the language-pack manager to set languages; answer "no
        // pack", which returns before any engine call.
        val lpm = mock(LanguagePackManager::class.java)
        lpmStatic = mockStatic(LanguagePackManager::class.java)
        lpmStatic.`when`<LanguagePackManager> { LanguagePackManager.getInstance(any()) }.thenReturn(lpm)
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.ENGLISH)
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)
    }

    @After
    fun tearDown() {
        subtypeStatic.close()
        lpmStatic.close()
        sdkStatic.close()
    }

    private fun engineOf(d: Dictionary): Any? =
        NuanceSDKDictionaryBridge::class.java.getDeclaredField("nuanceSdk")
            .apply { isAccessible = true }.get(d)

    @Test
    fun nullLocale_isTheFallback_andNeverTouchesTheEngine() {
        val d = DictionaryFactory.createDictionary(ctx, null)
        assertEquals(FallbackDictionary::class.java, d.javaClass)
        assertEquals("main", d.dictType)
        sdkStatic.verify({ NuanceSDKManager.getInstance() }, never())
        sdkStatic.verify({ NuanceSDKManager.getSecondary() }, never())

        assertEquals(FallbackDictionary::class.java, DictionaryFactory.createDictionary(ctx, null, true).javaClass)
    }

    @Test
    fun twoArgForm_isThePrimaryEngine() {
        val d = DictionaryFactory.createDictionary(ctx, Locale.ENGLISH)
        assertEquals(NuanceSDKDictionaryBridge::class.java, d.javaClass)
        assertTrue(engineOf(d) === primary)
        sdkStatic.verify({ NuanceSDKManager.getSecondary() }, never())
    }

    @Test
    fun secondaryFlag_selectsTheSecondaryEngine() {
        val d = DictionaryFactory.createDictionary(ctx, Locale.ENGLISH, true)
        assertEquals(NuanceSDKDictionaryBridge::class.java, d.javaClass)
        assertTrue(engineOf(d) === secondary)
        sdkStatic.verify({ NuanceSDKManager.getInstance() }, never())
    }

    @Test
    fun engineFailedToLoad_isTheFallback_notABridgeOverNull() {
        // Audit §6 defect 9: NuanceSDKManager reports a failed engine load as null; the factory now
        // falls back instead of wrapping null (which NPE'd once a supported language pack was found).
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(null)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getSecondary() }.thenReturn(null)

        val d = DictionaryFactory.createDictionary(ctx, Locale.ENGLISH)
        assertEquals(FallbackDictionary::class.java, d.javaClass)
        assertEquals("main", d.dictType)

        val s = DictionaryFactory.createDictionary(ctx, Locale.ENGLISH, true)
        assertEquals(FallbackDictionary::class.java, s.javaClass)
    }
}
