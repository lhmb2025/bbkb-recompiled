package dev.bbkb.ime.personaldictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.personaldictionary.storage.DefaultWSLoader
import dev.bbkb.ime.personaldictionary.sync.AudSyncer
import dev.bbkb.ime.personaldictionary.util.CompletionListener
import com.blackberry.nuanceshim.NuanceSDK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The on-disk `personal_words` file must come back into memory on load.
 *
 * KEY2, 2026-09-21: the file held eight words and the load logged
 * "Error parsing JSON with Gson: null" — the load path's add threw
 * [InitialisationIncompleteException] on the first word because the loaded flag is (correctly)
 * set only after everything is in. Not one personal word was loaded from disk, on any start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.blackberry.nuanceshim"])
class PersonalDictionaryLoadTest {

    private lateinit var context: Context
    private lateinit var pdu: PersonalDictionaryUtil

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val provider = FakeUserDictionaryProvider()
        provider.attachInfo(context, null)
        ShadowContentResolver.registerProviderInternal("user_dictionary", provider)
        DefaultWSLoader.invalidateCache()
        pduDir().deleteRecursively()
        pduDir().mkdirs()
        File(pduDir(), PersonalDictionaryConstants.PERSONAL_WORDS_FILE).writeText(
            """[{"version":1,"autoCapsEnabled":false,"locale":"en_US","word":"politicus"},""" +
                """{"version":1,"autoCapsEnabled":false,"locale":"en_US","word":"viewshed"}]"""
        )
        pdu = PersonalDictionaryUtil.getInstance(
            context, context.filesDir.absolutePath, "substitution_macros", mock(NuanceSDK::class.java)
        )
    }

    @After
    fun tearDown() {
        if (this::pdu.isInitialized) pdu.shutDown(true)
        AudSyncer.getInstance().shutDown(true)
        pduDir().deleteRecursively()
    }

    private fun pduDir() = File(context.filesDir, "dev.bbkb.ime.basl.pdu")

    @Test
    fun personalWordsOnDiskAreInMemoryAfterLoad() {
        val loaded = CountDownLatch(1)
        pdu.load(listOf(Locale.US), CompletionListener { loaded.countDown() }, false, false)
        assertTrue("PDU load never completed", loaded.await(15, TimeUnit.SECONDS))

        val words = pdu.personalDictionary.keys
        assertEquals("both on-disk words must be loaded: $words", setOf("politicus", "viewshed"), words)
    }
}
