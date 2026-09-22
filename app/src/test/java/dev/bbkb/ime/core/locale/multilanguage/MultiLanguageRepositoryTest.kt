package dev.bbkb.ime.core.locale.multilanguage

import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The repository's config list against the persisted "multi_lang_input_subtypes" string, on a
 * fresh process (a new repository over prefs that already hold configs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MultiLanguageRepositoryTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = PrefsManager.getPrefs(context)

    private fun config(primary: String, vararg supporting: String, layout: String = "qwerty") =
        MultiLanguageConfig(LocaleItem(primary), ArrayList(supporting.map { LocaleItem(it) }), layout)

    private fun stored(): String = prefs.getString(KEY, "")!!

    /** A fresh process: no InputMethodInfo needed for the config list. */
    private fun freshRepository() = MultiLanguageRepository(context, null)

    @Before
    fun setUp() {
        prefs.edit().putString(KEY, "en_US/fr_FR:qwerty;de_DE/it_IT:qwertz").commit()
    }

    @Test
    fun freshRepositoryReadsTheStoredListAsIs() {
        val configs = freshRepository().getConfigs()
        assertEquals(2, configs.size)
        assertEquals(
            listOf("de_DE/it_IT:qwertz", "en_US/fr_FR:qwerty"),
            configs.map { MultiLanguageUtils.serializeConfig(it) }
        )
    }

    @Test
    fun addConfigOnAFreshProcessKeepsThePersistedList() {
        // The persisted list is loaded before the mutation, so the new entry joins it.
        val repository = freshRepository()
        assertTrue(repository.addConfig(config("es_ES", "pt_BR")))
        assertEquals("de_DE/it_IT:qwertz;en_US/fr_FR:qwerty;es_ES/pt_BR:qwerty", stored())
    }

    @Test
    fun addingAStoredConfigOnAFreshProcessReportsItAlreadyExists() {
        assertFalse(freshRepository().addConfig(config("en_US", "fr_FR")))
        assertEquals("de_DE/it_IT:qwertz;en_US/fr_FR:qwerty", stored())
    }

    @Test
    fun removeConfigOnAFreshProcessRemovesOnlyThatEntry() {
        assertTrue(freshRepository().removeConfig(config("en_US", "fr_FR")))
        assertEquals("de_DE/it_IT:qwertz", stored())
    }

    @Test
    fun configAppendedBehindTheRepositorysBackIsSeen() {
        // CombineLanguages appends to the pref directly; a cached repository must not drop it.
        val repository = freshRepository()
        repository.getConfigs()
        MultiLanguageUtils.appendConfigToPrefs(prefs, config("es_ES", "pt_BR"))
        repository.removeConfig(config("de_DE", "it_IT"))
        assertEquals("en_US/fr_FR:qwerty;es_ES/pt_BR:qwerty", stored())
    }

    @Test
    fun unparseableStoredEntryIsSkippedNotFatal() {
        prefs.edit().putString(KEY, "garbage;en_US/fr_FR:qwerty").commit()
        assertEquals(1, freshRepository().getConfigs().size)
    }

    private companion object {
        const val KEY = "multi_lang_input_subtypes"
    }
}
