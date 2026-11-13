package dev.bbkb.ime.core.settings

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The stored preferences with the active device profile's `forced-value` settings laid over the
 * typed reads, so Settings and the keyboard cannot disagree. Nothing is written: [getAll],
 * [contains] and [edit] are the stored file, so backups never carry a forced value.
 *
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

    // Listeners must be handed these prefs, not the stored file. The stored prefs hold listeners
    // weakly, so each relay lives here as long as its listener, which it holds weakly in turn.
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
