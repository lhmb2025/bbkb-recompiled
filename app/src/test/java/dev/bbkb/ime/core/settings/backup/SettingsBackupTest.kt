package dev.bbkb.ime.core.settings.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings backup document: what it writes, what it refuses to read, and what a round trip
 * has to preserve.
 *
 * Robolectric only for a real [SharedPreferences] — everything under test is plain JSON and plain
 * preference writes, which is the whole point of keeping the file pickers and the toasts out of
 * [SettingsBackup].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsBackupTest {

    private lateinit var source: SharedPreferences
    private lateinit var target: SharedPreferences

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        source = context.getSharedPreferences("backup_source", Context.MODE_PRIVATE)
        target = context.getSharedPreferences("backup_target", Context.MODE_PRIVATE)
        source.edit().clear().commit()
        target.edit().clear().commit()
    }

    private fun exportSource(): String =
        SettingsBackup.serialize(source, appVersion = "5.0.0-beta.20", versionCode = 20)

    private fun restoreInto(prefs: SharedPreferences, json: String): Int {
        val backup = SettingsBackup.parse(json).getOrThrow()
        return SettingsBackup.apply(prefs, backup)
    }

    // ── round trip ───────────────────────────────────────────────────────────

    @Test
    fun everyPreferenceTypeSurvivesARoundTrip() {
        source.edit()
            .putBoolean("auto_cap", true)
            .putInt("pref_keypress_sound_volume", 42)
            .putLong("some_long", 9_000_000_000L)
            .putFloat("pref_tap_region", 0.25f)
            .putString("control_mode", "2")
            .putStringSet("enabled_subtypes", setOf("en_US", "fr_FR", "de_DE"))
            .commit()

        val restored = restoreInto(target, exportSource())

        assertEquals(6, restored)
        assertEquals(true, target.getBoolean("auto_cap", false))
        assertEquals(42, target.getInt("pref_keypress_sound_volume", 0))
        assertEquals(9_000_000_000L, target.getLong("some_long", 0L))
        assertEquals(0.25f, target.getFloat("pref_tap_region", 0f), 0.0001f)
        assertEquals("2", target.getString("control_mode", null))
        assertEquals(
            setOf("en_US", "fr_FR", "de_DE"),
            target.getStringSet("enabled_subtypes", emptySet()),
        )
    }

    /**
     * The reason every value carries a `type`: a restore that wrote everything back as a string
     * would pass a round-trip test that only checked `getString`, and then throw
     * `ClassCastException` the next time the keyboard read the key with its own getter. So this
     * asserts the stored *types*, via `all`, not just the values.
     */
    @Test
    fun restoredValuesKeepTheirJavaTypes() {
        source.edit()
            .putBoolean("b", false)
            .putInt("i", 7)
            .putLong("l", 7L)
            .putFloat("f", 7f)
            .putString("s", "7")
            .putStringSet("ss", setOf("7"))
            .commit()

        restoreInto(target, exportSource())

        assertTrue(target.all["b"] is Boolean)
        assertTrue(target.all["i"] is Int)
        assertTrue(target.all["l"] is Long)
        assertTrue(target.all["f"] is Float)
        assertTrue(target.all["s"] is String)
        assertTrue(target.all["ss"] is Set<*>)
    }

    @Test
    fun anEmptyStringSetRoundTripsAsAnEmptySet() {
        source.edit().putStringSet("empty", emptySet()).commit()

        restoreInto(target, exportSource())

        assertEquals(emptySet<String>(), target.getStringSet("empty", null))
    }

    @Test
    fun theSameSettingsSerializeToTheSameBytes() {
        source.edit()
            .putStringSet("subtypes", setOf("c", "a", "b"))
            .putString("z", "z")
            .putString("a", "a")
            .commit()

        val first = SettingsBackup.serialize(source, "v", 1, nowMillis = 0L)
        val second = SettingsBackup.serialize(source, "v", 1, nowMillis = 0L)

        assertEquals(first, second)
    }

    // ── header ───────────────────────────────────────────────────────────────

    @Test
    fun theHeaderNamesTheFormatVersionAndBuild() {
        source.edit().putBoolean("auto_cap", true).commit()

        val root = JSONObject(exportSource())

        assertEquals("bbkb-settings", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals("5.0.0-beta.20", root.getString("app"))
        assertEquals(20, root.getInt("versionCode"))
        // ISO-8601, UTC, to the second.
        assertTrue(
            root.getString("created"),
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(root.getString("created")),
        )
    }

    @Test
    fun parseRejectsAnotherApplicationsJson() {
        val result = SettingsBackup.parse("""{"format": "some-other-app", "version": 1, "settings": {}}""")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SettingsBackup.NotABackupException)
    }

    @Test
    fun parseRejectsAJsonDocumentWithNoHeaderAtAll() {
        assertTrue(SettingsBackup.parse("""{"auto_cap": true}""").isFailure)
    }

    @Test
    fun parseRejectsAVersionThisBuildCannotRead() {
        val result = SettingsBackup.parse("""{"format": "bbkb-settings", "version": 2, "settings": {}}""")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SettingsBackup.NotABackupException)
    }

    @Test
    fun parseRejectsMalformedJson() {
        for (junk in listOf("", "not json at all", """{"format": "bbkb-settings", """, "[1, 2, 3]")) {
            val result = SettingsBackup.parse(junk)
            assertTrue("should have rejected: $junk", result.isFailure)
            assertTrue(junk, result.exceptionOrNull() is SettingsBackup.NotABackupException)
        }
    }

    @Test
    fun parseRejectsABackupWithNoSettingsObject() {
        assertTrue(SettingsBackup.parse("""{"format": "bbkb-settings", "version": 1}""").isFailure)
    }

    @Test
    fun parseAcceptsAMinimalWellFormedBackup() {
        val backup = SettingsBackup.parse(
            """{"format": "bbkb-settings", "version": 1, "settings": {}}"""
        ).getOrThrow()

        assertEquals(1, backup.version)
        assertEquals(0, backup.size)
        assertNull(backup.app)
    }

    // ── denylist ─────────────────────────────────────────────────────────────

    @Test
    fun transientKeysAreLeftOutOfTheExport() {
        source.edit()
            .putBoolean("auto_cap", true)
            .putLong("pref_update_last_check_ms", 1_700_000_000_000L)
            .putInt("pref_update_seen_version_code", 19)
            .putString("pref_distribution_manifest_url", "http://192.168.1.5:8000/manifest.json")
            .putString("pref_distribution_channel_override", "beta")
            .putString("active_device_config_id", "custom:my_phone.xml")
            .putString("last_shown_emoji_category_id", "7")
            .commit()

        val settings = JSONObject(exportSource()).getJSONObject("settings")

        assertEquals(setOf("auto_cap"), settings.keys().asSequence().toSet())
    }

    @Test
    fun theOneUpdateSettingTheUserOwnsIsNotTransient() {
        source.edit()
            .putBoolean("pref_update_background_check", true)
            .putLong("pref_update_last_check_ms", 1L)
            .commit()

        val settings = JSONObject(exportSource()).getJSONObject("settings")

        assertTrue(settings.has("pref_update_background_check"))
        assertFalse(settings.has("pref_update_last_check_ms"))
        assertFalse(SettingsBackup.isDenied("pref_update_background_check"))
        assertTrue(SettingsBackup.isDenied("ckb_key_grid_cache_v2"))
    }

    @Test
    fun transientKeysInAHandEditedBackupAreIgnoredOnImport() {
        target.edit()
            .putString("pref_distribution_manifest_url", "https://real.example/manifest.json")
            .putString("active_device_config_id", "preloaded:device_config_athena")
            .commit()

        val restored = restoreInto(
            target,
            """
            {
              "format": "bbkb-settings",
              "version": 1,
              "settings": {
                "auto_cap": { "type": "boolean", "value": true },
                "pref_update_last_check_ms": { "type": "long", "value": 5 },
                "pref_distribution_manifest_url": { "type": "string", "value": "http://attacker.example/m.json" },
                "active_device_config_id": { "type": "string", "value": "custom:someone_elses.xml" }
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, restored)
        assertEquals(true, target.getBoolean("auto_cap", false))
        assertFalse(target.contains("pref_update_last_check_ms"))
        assertEquals(
            "https://real.example/manifest.json",
            target.getString("pref_distribution_manifest_url", null),
        )
        assertEquals(
            "preloaded:device_config_athena",
            target.getString("active_device_config_id", null),
        )
    }

    // ── apply ────────────────────────────────────────────────────────────────

    @Test
    fun applyReturnsTheNumberOfSettingsWritten() {
        source.edit()
            .putBoolean("a", true)
            .putBoolean("b", false)
            .putString("c", "c")
            .putLong("pref_update_last_check_ms", 1L)
            .commit()

        assertEquals(3, restoreInto(target, exportSource()))
    }

    /** The documented choice: a restore adds and overwrites, it does not clear. */
    @Test
    fun applyLeavesKeysTheBackupDoesNotMention() {
        target.edit()
            .putString("set_on_this_device_only", "keep me")
            .putBoolean("auto_cap", false)
            .commit()
        source.edit().putBoolean("auto_cap", true).commit()

        restoreInto(target, exportSource())

        assertEquals("keep me", target.getString("set_on_this_device_only", null))
        assertEquals(true, target.getBoolean("auto_cap", false))
    }

    @Test
    fun aBackupOfNothingRestoresNothing() {
        assertEquals(0, restoreInto(target, exportSource()))
        assertTrue(target.all.isEmpty())
    }

    @Test
    fun oneUnreadableEntryDoesNotSinkTheWholeRestore() {
        val restored = restoreInto(
            target,
            """
            {
              "format": "bbkb-settings",
              "version": 1,
              "settings": {
                "good": { "type": "boolean", "value": true },
                "unknown_type": { "type": "uuid", "value": "x" },
                "null_value": { "type": "string", "value": null },
                "not_an_entry": "bare string"
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, restored)
        assertEquals(true, target.getBoolean("good", false))
        assertFalse(target.contains("unknown_type"))
        assertFalse(target.contains("null_value"))
        assertFalse(target.contains("not_an_entry"))
    }

    // ── file name ────────────────────────────────────────────────────────────

    @Test
    fun theDefaultFileNameCarriesTheDate() {
        assertTrue(
            Regex("""bbkb-settings-\d{4}-\d{2}-\d{2}\.json""").matches(SettingsBackup.defaultFileName())
        )
    }
}
