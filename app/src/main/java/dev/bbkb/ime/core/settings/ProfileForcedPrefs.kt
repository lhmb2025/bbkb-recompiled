package dev.bbkb.ime.core.settings

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The app's settings as everything should read them: the stored preferences, with the active
 * device profile's `forced-value` settings laid over the top.
 *
 * A profile can force a setting (`<setting key="…" forced-value="…"/>`). That used to change only
 * the switch the Settings screen drew; the keyboard read the stored preference, which can differ
 * (set before the profile applied, or restored from a backup). Answering here, where a setting is
 * read, means the Settings screen and the keyboard cannot disagree, and a newly forced setting
 * needs no code at its readers.
 *
 * Only the typed reads of a forced key change. Nothing is written: [getAll], [contains] and
 * [edit] are the stored file, so a backup never carries a forced value to another phone and the
 * user's own value is back the moment the profile stops forcing it.
 *
 * @param stored the preferences file
 * @param forced the active profile's override for a key, or null; must not block
 */
internal class ProfileForcedPrefs(
    private val stored: SharedPreferences,
    private val forced: (String) -> DeviceSettingOverride?,
) : SharedPreferences {

    private fun override(key: String?): DeviceSettingOverride? =
        if (key == null) null else forced(key)?.takeIf { it.hasForcedValue() }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        override(key)?.forcedBooleanValue ?: stored.getBoolean(key, defValue)

    override fun getInt(key: String?, defValue: Int): Int =
        override(key)?.forcedIntegerValue ?: stored.getInt(key, defValue)

    override fun getString(key: String?, defValue: String?): String? =
        override(key)?.forcedStringValue ?: stored.getString(key, defValue)

    // A profile cannot force these types.
    override fun getLong(key: String?, defValue: Long): Long = stored.getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = stored.getFloat(key, defValue)
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        stored.getStringSet(key, defValues)

    override fun getAll(): MutableMap<String, *> = stored.all
    override fun contains(key: String?): Boolean = stored.contains(key)
    override fun edit(): SharedPreferences.Editor = stored.edit()

    // A listener is handed the preferences that changed and usually reads the new value from
    // them, so it must be handed these, not the stored file underneath.
    //
    // The stored preferences hold their listeners weakly, so each relay is kept alive here for as
    // long as the listener it stands for; it holds that listener weakly in turn, or the map entry
    // would keep itself alive.
    private val relays = WeakHashMap<OnSharedPreferenceChangeListener, Relay>()

    private class Relay(
        private val prefs: SharedPreferences,
        listener: OnSharedPreferenceChangeListener,
    ) : OnSharedPreferenceChangeListener {
        private val listener = WeakReference(listener)
        override fun onSharedPreferenceChanged(ignored: SharedPreferences?, key: String?) {
            listener.get()?.onSharedPreferenceChanged(prefs, key)
        }
    }

    override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        val relay = synchronized(relays) { relays.getOrPut(listener) { Relay(this, listener) } }
        stored.registerOnSharedPreferenceChangeListener(relay)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        val relay = synchronized(relays) { relays.remove(listener) } ?: return
        stored.unregisterOnSharedPreferenceChangeListener(relay)
    }
}
