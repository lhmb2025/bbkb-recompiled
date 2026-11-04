package dev.bbkb.ime.core.settings.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * A field focus costs the per-editor overlay only (P-12): `SettingsValues.forEditor` copies the
 * stored and resource-derived fields and reads no preference, and `SettingsManager.loadSettings`
 * takes that path until a preference, the keyboard locale or the configuration changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsValuesEditorOverlayTest {

    private lateinit var context: Context
    private val manager get() = SettingsManager.getInstance()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        SpacingAndPunctuation.clearCache()
    }

    @After
    fun tearDown() {
        // Same reset as SettingsValuesSnapshotTest: the singleton outlives this class.
        runCatching { manager.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it).apply { isAccessible = true }.set(manager, null)
        }
        SettingsManager::class.java.getDeclaredField("rebuildPending").apply { isAccessible = true }.setBoolean(manager, false)
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
    }

    private fun prefs() = PrefsManager.getPrefs(context)

    private fun caps(inputType: Int, locale: Locale = Locale.US): EditorCapabilities {
        val editor = EditorInfo().apply {
            this.inputType = inputType
            packageName = "com.example.app"
        }
        return EditorCapabilities(editor, false, context.packageName, locale, false)
    }

    private val textField get() = caps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
    private val passwordField get() = caps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val emailField get() = caps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)

    private fun full(prefs: SharedPreferences, caps: EditorCapabilities) =
        SettingsValues(context, prefs, context.resources, caps)

    private fun setManagerPrefs(prefs: SharedPreferences) {
        SettingsManager::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }.set(manager, prefs)
    }

    private fun built(): SettingsValues =
        SettingsManager::class.java.getDeclaredField("settingsValues").apply { isAccessible = true }.get(manager) as SettingsValues

    /** Every instance field of SettingsValues, by name, for a field-for-field comparison. */
    private fun fieldsOf(sv: SettingsValues): Map<String, Any?> =
        SettingsValues::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .associate { f -> f.isAccessible = true; f.name to f.get(sv) }

    private fun assertSameValues(expected: SettingsValues, actual: SettingsValues) {
        val e = fieldsOf(expected)
        val a = fieldsOf(actual)
        assertEquals(e.keys, a.keys)
        for ((name, value) in e) {
            val other = a[name]
            val same = when (value) {
                is IntArray -> value.contentEquals(other as IntArray)
                else -> value == other
            }
            assertTrue("field $name: $value vs $other", same)
        }
    }

    @Test
    fun theOverlayReadsNoPreference() {
        val counting = CountingPreferences(prefs())
        val base = full(counting, textField)
        val fullReads = counting.reads
        counting.reads = 0
        base.forEditor(context, passwordField)
        val overlayReads = counting.reads
        println("SettingsValues per field focus: full build = $fullReads preference reads, overlay = $overlayReads")
        assertTrue("a full build reads the stored settings ($fullReads)", fullReads > 100)
        assertEquals(0, overlayReads)
    }

    @Test
    fun theOverlayGivesWhatAFullBuildGivesForTheSameEditor() {
        prefs().edit()
            .putBoolean("pref_key_use_double_space_period", true)
            .putInt("auto_correction_mode_VKB", 2)
            .putInt("auto_correction_mode_PKB", 1)
            .putBoolean("show_predictions", false)
            .putString("quick_phrase_2", "on my way")
            .commit()
        for (target in listOf(textField, passwordField, emailField)) {
            val expected = full(prefs(), target)
            val overlaid = full(prefs(), caps(InputType.TYPE_CLASS_PHONE)).forEditor(context, target)
            assertSameValues(expected, overlaid)
            assertSame(target, overlaid.editorCapabilities)
        }
    }

    @Test
    fun aNewFieldWithNothingElseChangedReadsNoPreference() {
        manager.loadSettings(context, Locale.US, textField)
        val counting = CountingPreferences(prefs())
        setManagerPrefs(counting)

        val password = passwordField
        manager.loadSettings(context, Locale.US, password)
        assertSame(password, built().editorCapabilities)
        manager.loadSettings(context, Locale.US, emailField)

        assertEquals(0, counting.reads)
    }

    @Test
    fun aPreferenceChangeRebuildsFromThePreferences() {
        manager.loadSettings(context, Locale.US, textField)
        val counting = CountingPreferences(prefs())
        setManagerPrefs(counting)
        SettingsManager::class.java.getDeclaredField("rebuildPending").apply { isAccessible = true }.setBoolean(manager, true)

        manager.loadSettings(context, Locale.US, passwordField)

        assertTrue(counting.reads > 100)
    }

    @Test
    fun anotherKeyboardLocaleRebuildsFromThePreferences() {
        manager.loadSettings(context, Locale.US, textField)
        val counting = CountingPreferences(prefs())
        setManagerPrefs(counting)

        manager.loadSettings(context, Locale.FRANCE, textField)

        assertTrue(counting.reads > 100)
        assertEquals(Locale.FRANCE.language, built().locale.language)
    }

    @Test
    fun theSnapshotKeyFollowsTheConfiguration() {
        val built = Configuration(context.resources.configuration)
        assertTrue(SettingsManager.isSnapshotFor(Locale.US, built, Locale.US, Configuration(built)))
        val rotated = Configuration(built).apply {
            orientation = if (built.orientation == Configuration.ORIENTATION_LANDSCAPE)
                Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        }
        assertTrue(!SettingsManager.isSnapshotFor(Locale.US, built, Locale.US, rotated))
        val keyboardOut = Configuration(built).apply {
            hardKeyboardHidden = if (built.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO)
                Configuration.HARDKEYBOARDHIDDEN_YES else Configuration.HARDKEYBOARDHIDDEN_NO
        }
        assertTrue(!SettingsManager.isSnapshotFor(Locale.US, built, Locale.US, keyboardOut))
        assertTrue(!SettingsManager.isSnapshotFor(Locale.US, built, Locale.GERMANY, Configuration(built)))
        assertTrue(!SettingsManager.isSnapshotFor(Locale.US, null, Locale.US, built))
    }

    @Test
    fun spacingAndPunctuationIsParsedOncePerConfiguration() {
        val first = SpacingAndPunctuation.forResources(context.resources)
        assertSame(first, SpacingAndPunctuation.forResources(context.resources))
        val french = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.FRANCE) }).resources
        assertNotSame(first, SpacingAndPunctuation.forResources(french))
        assertSame(first, SpacingAndPunctuation.forResources(context.resources))
    }

    /** Per-field cost on the host JVM, before (full build) and after (overlay). */
    @Test
    fun microBenchmark() {
        val p = prefs()
        val base = full(p, textField)
        repeat(50) { full(p, textField); base.forEditor(context, passwordField) }
        val rounds = 500
        var t = System.nanoTime()
        repeat(rounds) { full(p, passwordField) }
        val fullUs = (System.nanoTime() - t) / 1000.0 / rounds
        t = System.nanoTime()
        repeat(rounds) { base.forEditor(context, passwordField) }
        val overlayUs = (System.nanoTime() - t) / 1000.0 / rounds
        println(String.format(Locale.ROOT, "SettingsValues per field focus: full build = %.1f us, overlay = %.1f us", fullUs, overlayUs))
        assertTrue(overlayUs < fullUs)

        // The whole SettingsManager.loadSettings, as a field focus runs it, for a keyboard
        // language other than the system's (the RunInLocale configuration context included).
        val pending = SettingsManager::class.java.getDeclaredField("rebuildPending").apply { isAccessible = true }
        manager.loadSettings(context, Locale.GERMANY, textField)
        t = System.nanoTime()
        repeat(rounds) { pending.setBoolean(manager, true); manager.loadSettings(context, Locale.GERMANY, passwordField) }
        val loadFullUs = (System.nanoTime() - t) / 1000.0 / rounds
        t = System.nanoTime()
        repeat(rounds) { manager.loadSettings(context, Locale.GERMANY, passwordField) }
        val loadOverlayUs = (System.nanoTime() - t) / 1000.0 / rounds
        println(String.format(Locale.ROOT, "SettingsManager.loadSettings per field focus (de_DE keyboard): before = %.1f us, after = %.1f us", loadFullUs, loadOverlayUs))
    }

    /** Counts every read; writes and listeners go to the real file. */
    private class CountingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var reads = 0
        override fun getAll(): MutableMap<String, *> { reads++; return delegate.all }
        override fun getString(key: String?, defValue: String?): String? { reads++; return delegate.getString(key, defValue) }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? { reads++; return delegate.getStringSet(key, defValues) }
        override fun getInt(key: String?, defValue: Int): Int { reads++; return delegate.getInt(key, defValue) }
        override fun getLong(key: String?, defValue: Long): Long { reads++; return delegate.getLong(key, defValue) }
        override fun getFloat(key: String?, defValue: Float): Float { reads++; return delegate.getFloat(key, defValue) }
        override fun getBoolean(key: String?, defValue: Boolean): Boolean { reads++; return delegate.getBoolean(key, defValue) }
        override fun contains(key: String?): Boolean { reads++; return delegate.contains(key) }
    }
}
