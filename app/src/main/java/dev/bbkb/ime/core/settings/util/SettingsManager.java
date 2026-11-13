package dev.bbkb.ime.core.settings.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.provider.Settings;
import android.util.Log;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.keyevent.AltSymShortcutHandler;
import dev.bbkb.ime.core.keyevent.MultifunctionKeyHandler;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;
import dev.bbkb.ime.core.AudioAndHapticFeedbackManager;
import dev.bbkb.ime.core.shared.RunInLocale;
import dev.bbkb.ime.core.shared.StartupTiming;
import dev.bbkb.ime.core.device.detection.RomFeatureChecker;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.device.ResourceConfigManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import dev.bbkb.ime.BuildConfig;

/** Loads and caches {@link SettingsValues}, listens for preference changes, and reads single settings. */



public final class SettingsManager implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "SettingsManager";

    @android.annotation.SuppressLint("StaticFieldLeak") // Holds only the application context
    private static final SettingsManager instance;

    private static final String DEFAULT_INT_VALUE_STRING;

    private Context context;

    private Resources resources;

    private SharedPreferences sharedPreferences;

    private SettingsValues settingsValues;

    private DeviceProfile deviceProfile;

    private ArrayList<OnSettingsChangeListener> listeners;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * A preference changed since {@link #settingsValues} was built. A burst of changes (restore,
     * reset) posts one rebuild; a main-thread read before it runs rebuilds on the spot, so a
     * write followed by a read still sees the write.
     */
    private volatile boolean rebuildPending;

    private Handler mainHandler;

    /**
     * The locale and configuration {@link #settingsValues} was built for; a load for the same pair
     * with no preference change only redoes the per-editor fields.
     */
    private Locale snapshotLocale;

    private Configuration snapshotConfiguration;

    /** The change listener is registered; without it every load re-reads the preferences. */
    private volatile boolean listeningForChanges;

    private final Runnable coalescedRebuild = this::runCoalescedRebuild;

    
    public interface OnSettingsChangeListener {
        /** @param settingsValues the rebuilt settings */
        void onSettingsValuesChanged(SettingsValues settingsValues);
    }

    /** @param value a resource id */
    public static boolean isResourceNonZero(int value, Resources resources) {
        return value != 0;
    }

    static {
        instance = new SettingsManager();
        DEFAULT_INT_VALUE_STRING = Integer.toString(-1);
    }

    public static SettingsManager getInstance() {
        return instance;
    }

    public static void initialize(Context context) {
        instance.initializeInternal(context);
    }

    private SettingsManager() {
    }

    private void initializeInternal(Context context) {
        final long token = StartupTiming.begin();
        this.context = context.getApplicationContext();
        this.resources = context.getResources();
        this.listeners = new ArrayList<>();
        this.sharedPreferences = PrefsManager.INSTANCE.getPrefs(context);
        this.sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        this.listeningForChanges = true;
        // A new preferences instance: nothing built from the old one is reused.
        this.snapshotConfiguration = null;
        // contains() blocks until the prefs file loads and this runs first in onCreate, so migrate
        // off the main thread; nothing on the critical path reads the legacy keys.
        new Thread(this::migrateLegacyPreferences, "PrefsMigrate").start();
        StartupTiming.end("settings.initializeInternal", token);
    }

    /** Whether settings built for the first locale/configuration still hold for the second; any configuration diff counts. */
    static boolean isSnapshotFor(Locale builtLocale, Configuration builtConfiguration,
            Locale locale, Configuration configuration) {
        return builtConfiguration != null
                && java.util.Objects.equals(builtLocale, locale)
                && builtConfiguration.diff(configuration) == 0;
    }

    public void unregisteredListener() {
        this.listeningForChanges = false;
        this.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    /** Keys written during typing that no {@link SettingsValues} field reads, so they skip the rebuild. */
    private static final java.util.Set<String> KEYS_NOT_IN_SETTINGS_VALUES =
            new java.util.HashSet<>(Arrays.asList("emoji_recent_keys", "last_shown_emoji_category_id", "emoji_tone_memory"));

    /** Key prefixes no {@link SettingsValues} field reads: the update checker's background state. */
    private static final String[] KEY_PREFIXES_NOT_IN_SETTINGS_VALUES = {"pref_update_"};

    static boolean isReadBySettingsValues(String key) {
        if (key == null) {
            return true; // clear(): everything changed
        }
        if (KEYS_NOT_IN_SETTINGS_VALUES.contains(key)) {
            return false;
        }
        for (String prefix : KEY_PREFIXES_NOT_IN_SETTINGS_VALUES) {
            if (key.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /** @param key the changed key, null after clear() */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (!isReadBySettingsValues(key) || this.settingsValues == null) {
            return;
        }
        this.rebuildPending = true;
        final Handler handler = mainHandler();
        handler.removeCallbacks(this.coalescedRebuild);
        handler.post(this.coalescedRebuild);
    }

    private synchronized Handler mainHandler() {
        if (this.mainHandler == null) {
            this.mainHandler = new Handler(Looper.getMainLooper());
        }
        return this.mainHandler;
    }

    /** The posted half of a burst: rebuilds unless a read already did, then tells the listeners once. */
    private void runCoalescedRebuild() {
        this.lock.lock();
        try {
            if (this.settingsValues == null) {
                return;
            }
            reload();
            Iterator<OnSettingsChangeListener> it = this.listeners.iterator();
            while (it.hasNext()) {
                it.next().onSettingsValuesChanged(this.settingsValues);
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** Rebuilds now if a preference changed; for off-main readers that must see their own write. */
    public void reload() {
        if (!this.rebuildPending) {
            return;
        }
        this.lock.lock();
        try {
            final SettingsValues current = this.settingsValues;
            if (this.rebuildPending && current != null) {
                loadSettings(this.context, current.locale, current.editorCapabilities);
            }
        } finally {
            this.lock.unlock();
        }
    }

    private void reloadIfStaleOnMainThread() {
        if (this.rebuildPending && Looper.myLooper() == Looper.getMainLooper()) {
            reload();
        }
    }

    /** @param onSettingsChangeListener the listener to add */
    public void addOnSettingsChangeListener(OnSettingsChangeListener onSettingsChangeListener) {
        this.lock.lock();
        try {
            this.listeners.add(onSettingsChangeListener);
        } finally {
            this.lock.unlock();
        }
    }

    /** @param onSettingsChangeListener the listener to remove */
    public void removeOnSettingsChangeListener(OnSettingsChangeListener onSettingsChangeListener) {
        this.lock.lock();
        try {
            this.listeners.remove(onSettingsChangeListener);
        } finally {
            this.lock.unlock();
        }
    }

    /** @param editorCapabilities the focused editor's capabilities */
    public void loadSettings(final Context context, Locale locale, final EditorCapabilities editorCapabilities) {
        final long token = StartupTiming.begin();
        this.lock.lock();
        this.context = context.getApplicationContext();
        try {
            // Cleared before the build, so a write made while building marks it stale again.
            final boolean preferencesChanged = this.rebuildPending;
            this.rebuildPending = false;
            final SharedPreferences sharedPreferences = this.sharedPreferences;
            final Configuration configuration = this.context.getResources().getConfiguration();
            final SettingsValues previous = this.settingsValues;
            if (previous != null && !preferencesChanged && this.listeningForChanges
                    && isSnapshotFor(this.snapshotLocale, this.snapshotConfiguration, locale, configuration)) {
                // A new field with nothing else changed: only the per-editor fields are redone.
                this.settingsValues = previous.forEditor(this.context, editorCapabilities);
            } else {
                this.settingsValues = new RunInLocale<SettingsValues>() {
                    @Override
                    public SettingsValues job(Resources resources) {
                        return new SettingsValues(context, sharedPreferences, resources, editorCapabilities);
                    }
                }.runInLocale(this.context, locale);
                this.snapshotLocale = locale;
                this.snapshotConfiguration = new Configuration(configuration);
            }
            this.deviceProfile = DeviceProfile.fromSettings(context, this.settingsValues);
        } finally {
            this.lock.unlock();
            // The log's occurrence counter shows how often this ~191 ms unit runs before first frame.
            StartupTiming.end("settings.loadSettings", token);
        }
    }

    /**
     * One-shot preference migrations. Never run them from the {@link SettingsValues} constructor:
     * a write there re-enters {@link #loadSettings} through the change listener.
     */
    private void migrateLegacyPreferences() {
        final SharedPreferences prefs = this.sharedPreferences;
        if (prefs.contains("pref_slideboard")) {
            prefs.edit().remove("pref_quick_phrase_one").remove("pref_quick_phrase_two").remove("pref_quick_phrase_three").remove("pref_quick_phrase_four").remove("pref_quick_phrase_1").remove("pref_quick_phrase_2").remove("pref_quick_phrase_3").remove("pref_quick_phrase_4").remove("pref_slideboard").remove("pref_slideboard_numeric_location").remove("pref_slideboard_quick_phrases_location").apply();
        }
        if (prefs.contains("show_suggestions_setting")) {
            prefs.edit()
                    .putBoolean("show_predictions", !"0".equals(prefs.getString("show_suggestions_setting", null)))
                    .remove("show_suggestions_setting")
                    .apply();
        }
        if (prefs.contains("voice_mode")) {
            final String voiceModeMain = this.resources.getString(R.string.voice_mode_main);
            prefs.edit()
                    .putBoolean("pref_voice_input_key", voiceModeMain.equals(prefs.getString("voice_mode", voiceModeMain)))
                    .remove("voice_mode")
                    .apply();
        }
        // Retired multifunction-key / Alt+Sym action ids (voice_input, cursor_mode, emoji_picker,
        // ctrl_mode) are rewritten to their replacements so the settings rows show a real option.
        final String multifunctionAction = prefs.getString(MultifunctionKeyHandler.PREF_KEY, null);
        final String upgradedMultifunction = MultifunctionKeyHandler.upgradeLegacyAction(multifunctionAction);
        if (multifunctionAction != null && !multifunctionAction.equals(upgradedMultifunction)) {
            prefs.edit().putString(MultifunctionKeyHandler.PREF_KEY, upgradedMultifunction).apply();
        }
        final String altSymAction = prefs.getString(AltSymShortcutHandler.PREF_KEY, null);
        final String upgradedAltSym = AltSymShortcutHandler.upgradeLegacyAction(altSymAction);
        if (altSymAction != null && !altSymAction.equals(upgradedAltSym)) {
            prefs.edit().putString(AltSymShortcutHandler.PREF_KEY, upgradedAltSym).apply();
        }
    }

    public SettingsValues getSettingsValues() {
        reloadIfStaleOnMainThread();
        return this.settingsValues;
    }

    /** The current device profile, or null before the first load. */
    public DeviceProfile getDeviceProfile() {
        reloadIfStaleOnMainThread();
        return this.deviceProfile;
    }

    public boolean isPkbDevice() {
        return deviceProfile != null && deviceProfile.isPkbDevice();
    }

    public boolean isVkbDevice() {
        return deviceProfile == null || deviceProfile.isVkbDevice();
    }

    public static final float HEIGHT_SCALE_EXTRA_COMPACT = 0.75f;

    private static float getKeyboardHeightScale() {
        SettingsValues sv = getInstance().getSettingsValues();
        if (sv == null) return 1.0f;
        String mode = sv.keyboardHeightMode;
        if ("expanded".equals(mode)) return 1.15f;
        if ("compact".equals(mode)) return 0.85f;
        if ("extra_compact".equals(mode)) return HEIGHT_SCALE_EXTRA_COMPACT;
        return 1.0f;
    }

    /**
     * The height scale for the keyboard's base height, the same in both orientations: landscape
     * has its own base in res/values-land.
     */
    public static float getEffectiveKeyboardHeightScale() {
        return getKeyboardHeightScale();
    }

    public static boolean isSoundEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("sound_on", resources.getBoolean(R.bool.config_default_sound_enabled));
    }

    public static boolean isVibrationEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return AudioAndHapticFeedbackManager.getInstance().hasVibrator() && sharedPreferences.getBoolean("vibrate_on", resources.getBoolean(R.bool.config_default_vibration_enabled));
    }

    public static boolean isBlockOffensiveEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("pref_key_block_potentially_offensive", resources.getBoolean(R.bool.config_block_potentially_offensive));
    }

    public static boolean isVkbTypeBySwipingEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("type_by_swiping_vkb", resources.getBoolean(R.bool.config_vkb_type_by_swiping_enabled_by_build_config));
    }

    public static boolean isCbkTypeBySwipingEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("type_by_swiping_ckb", resources.getBoolean(R.bool.config_ckb_type_by_swiping_enabled_by_build_config));
    }

    public static boolean isVkbSwipeGesturesEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("swipe_gesture_vkb", resources.getBoolean(R.bool.config_vkb_swipe_gestures_enabled_by_build_config));
    }


    private static boolean isKeyPreviewEnabled(Resources resources) {
        return resources.getBoolean(R.bool.config_enable_show_key_preview_popup_option);
    }

    public static boolean readKeyPreviewPopupEnabled(SharedPreferences sharedPreferences, Resources resources) throws Resources.NotFoundException {
        boolean z = resources.getBoolean(R.bool.config_default_key_preview_popup);
        return !isKeyPreviewEnabled(resources) ? z : sharedPreferences.getBoolean("popup_on", z);
    }

    public static int getKeyPreviewDismissDelay(SharedPreferences sharedPreferences, Resources resources) {
        return Integer.parseInt(sharedPreferences.getString("pref_key_preview_popup_dismiss_delay", Integer.toString(resources.getInteger(R.integer.config_key_preview_linger_timeout))));
    }


    public static String getCustomInputStyles(SharedPreferences sharedPreferences, Resources resources) {
        String strM5503a = SubtypeFactory.createPrefSubtypes(resources.getStringArray(R.array.predefined_subtypes));
        if (DeviceProfile.current().hasPhysicalKeyboard()) {
            String strM5503a2 = SubtypeFactory.createPrefSubtypes(resources.getStringArray(R.array.predefined_builtin_keyboard_subtypes));
            StringBuilder sb = new StringBuilder(strM5503a);
            if (!strM5503a.isEmpty()) {
                sb.append(';');
            }
            sb.append(strM5503a2);
            strM5503a = sb.toString();
        }
        return sharedPreferences.getString("custom_input_styles", strM5503a);
    }

    /** @param mode the Cangjie mode to store */
    public static void setCangjieMode(SharedPreferences sharedPreferences, int mode) {
        sharedPreferences.edit().putInt("cangjie_mode", mode).apply();
    }

    public static int getCangjieMode(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("cangjie_mode", 1);
    }

    public static float getKeypressSoundVolume(SharedPreferences sharedPreferences, Resources resources) {
        // Two stored forms: a float 0..1 fraction (what SoundPool.play takes) or an int 10..100
        // percent from KeyPressFeedbackScreen; getFloat throws on the int. -1 means the default.
        float f;
        try {
            f = sharedPreferences.getFloat("pref_keypress_sound_volume", -1.0f);
        } catch (ClassCastException e) {
            int percent = sharedPreferences.getInt("pref_keypress_sound_volume", -1);
            f = percent < 0 ? -1.0f : percent / 100.0f;
        }
        return f != -1.0f ? f : getDefaultSoundVolume(resources);
    }

    private static float getDefaultSoundVolume(Resources resources) {
        return resources.getFraction(R.fraction.config_default_keypress_sound_volume, 1, 1);
    }

    /**
     * @param key the preference key
     * @param defaultResId the integer resource id of the default, used when the stored value is -1 or absent
     */
    public static int getIntPref(SharedPreferences sharedPreferences, Resources resources, String key, int defaultResId) {
        int i2 = sharedPreferences.getInt(key, -1);
        return i2 != -1 ? i2 : getIntResource(resources, defaultResId);
    }

    /** @param resId an integer resource id */
    private static int getIntResource(Resources resources, int resId) {
        return resources.getInteger(resId);
    }

    public static int getVibrationDuration(SharedPreferences sharedPreferences, Resources resources) {
        int i = sharedPreferences.getInt("pref_vibration_duration_settings", -1);
        return i != -1 ? i : getDefaultVibrationDuration(resources);
    }

    private static int getDefaultVibrationDuration(Resources resources) {
        return Integer.parseInt(DEFAULT_INT_VALUE_STRING);
    }

    /**
     * @param key the preference key
     * @param defaultValue the default when the stored value is -1 or absent
     */
    public static int getIntPrefOrDefault(SharedPreferences sharedPreferences, String key, int defaultValue) {
        int i2 = sharedPreferences.getInt(key, -1);
        return i2 != -1 ? i2 : defaultValue;
    }

    public static boolean isFullscreenModeEnabled(Resources resources) {
        return resources.getBoolean(R.bool.config_use_fullscreen_mode);
    }

    public static boolean hasHardwareKeyboard(Configuration configuration) {
        return (configuration.keyboard == 1 || configuration.hardKeyboardHidden == 2) ? false : true;
    }

    public static boolean isInternalBuild(Context context, SharedPreferences sharedPreferences) {
        final boolean defaultValue = BuildConfig.DEBUG
                && Settings.Global.getInt(context.getContentResolver(), "development_settings_enabled", 0) != 0;
        return sharedPreferences.getBoolean("pref_key_is_internal", defaultValue);
    }

    /** @param recentKeys the serialized recent-emoji list */
    public static void setEmojiRecentKeys(SharedPreferences sharedPreferences, String recentKeys) {
        sharedPreferences.edit().putString("emoji_recent_keys", recentKeys).apply();
    }

    public static String getEmojiRecentKeys(SharedPreferences sharedPreferences) {
        return sharedPreferences.getString("emoji_recent_keys", "");
    }

    /** @param categoryId the emoji category id */
    public static void setLastShownEmojiCategory(SharedPreferences sharedPreferences, int categoryId) {
        sharedPreferences.edit().putInt("last_shown_emoji_category_id", categoryId).apply();
    }

    /** @param defaultCategoryId the category id to return when none is stored */
    public static int getLastShownEmojiCategory(SharedPreferences sharedPreferences, int defaultCategoryId) {
        return sharedPreferences.getInt("last_shown_emoji_category_id", defaultCategoryId);
    }

    public static int getControlMode(SharedPreferences sharedPreferences, Resources resources) {
        return Integer.parseInt(sharedPreferences.getString("control_mode", "2"));
    }

    public static boolean isVkbControlModeEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("vkb_control_mode_enabled", false);
    }

    public static boolean isLanguageQuickSwitchEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("pref_language_quick_switch_key", false);
    }

    public static boolean isSpacebarLanguageSwitchingEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("pref_spacebar_language_switching", true);
    }

    public static boolean isDynamicLearningEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("dynamic_learning", resources.getBoolean(R.bool.config_default_dynamic_learning_enabled));
    }

    public static boolean isSlideboardActive(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("slideboard_active", resources.getBoolean(R.bool.config_default_slideboard));
    }

    public static String getCurrencySymbol(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getString("pref_currency_key", "");
    }

    public static int getHorizontalSwipeTheta(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_horizontal_swipe_theta", resources.getInteger(R.integer.config_default_horizontal_swipe_theta));
    }

    public static int getVerticalSwipeTheta(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_vertical_swipe_theta", resources.getInteger(R.integer.config_default_vertical_swipe_theta));
    }

    public static int getFastHorizontalSwipeMinX(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_fast_horizontal_swipe_min_x", resources.getInteger(R.integer.config_default_fast_horizontal_swipe_min_x));
    }

    public static int getFastHorizontalSwipeMinVelocity(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_fast_horizontal_swipe_min_velocity", resources.getInteger(R.integer.config_default_fast_horizontal_swipe_min_velocity));
    }

    public static int getSlowHorizontalSwipeMinX(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_slow_horizontal_swipe_min_x", resources.getInteger(R.integer.config_default_slow_horizontal_swipe_min_x));
    }

    public static int getSlowHorizontalSwipeMinVelocity(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_slow_horizontal_swipe_min_velocity", resources.getInteger(R.integer.config_default_slow_horizontal_swipe_min_velocity));
    }

    public static int getFastVerticalSwipeMinY(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_fast_vertical_swipe_min_y", resources.getInteger(R.integer.config_default_fast_vertical_swipe_min_y));
    }

    public static int getFastVerticalSwipeMinVelocity(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_fast_vertical_swipe_min_velocity", resources.getInteger(R.integer.config_default_fast_vertical_swipe_min_velocity));
    }

    public static int getSlowVerticalSwipeMinY(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_slow_vertical_swipe_min_y", resources.getInteger(R.integer.config_default_slow_vertical_swipe_min_y));
    }

    public static int getSlowVerticalSwipeMinVelocity(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_slow_vertical_swipe_min_velocity", resources.getInteger(R.integer.config_default_slow_vertical_swipe_min_velocity));
    }

    public static int getFlowModeVerticalSwipeMinY(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_flow_mode_vertical_swipe_min_y", resources.getInteger(R.integer.config_default_flow_mode_vertical_swipe_min_y));
    }

    public static int getFlowModeVerticalSwipeMinVelocity(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_flow_mode_vertical_swipe_min_velocity", resources.getInteger(R.integer.config_default_flow_mode_vertical_swipe_min_velocity));
    }

    public static int getSwipeGestureTimeout(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_swipe_gesture_timeout", resources.getInteger(R.integer.config_default_swipe_gesture_timeout));
    }

    public static float getInLetterMaxSwipeToWordDistance(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_in_letter_max_swipe_to_word_distance", resources.getInteger(R.integer.config_default_in_letter_max_swipe_to_word_distance));
    }

    /**
     * @param key the preference key
     * @param defaultResId the integer resource id of the default
     */
    public static int getIntPrefWithResourceDefault(SharedPreferences sharedPreferences, Resources resources, String key, int defaultResId) {
        int i2 = sharedPreferences.getInt(key, -1);
        return i2 != -1 ? i2 : resources.getInteger(defaultResId);
    }

    /**
     * @param key the preference key
     * @param defaultResId the fraction resource id of the default
     */
    public static float getFloatPrefWithResourceDefault(SharedPreferences sharedPreferences, Resources resources, String key, int defaultResId) {
        float f;
        try {
            f = sharedPreferences.getFloat(key, -1.0f);
        } catch (ClassCastException e) {
            // Some cursor tap-region scales may be stored as ints, on which getFloat throws: drop
            // the bad value and use the resource default.
            sharedPreferences.edit().remove(key).apply();
            f = -1.0f;
        }
        return f != -1.0f ? f : ResourceConfigManager.getFractionValue(resources, defaultResId);
    }

    public static int getScrollHorizontalDistanceForCursorMove(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_scroll_horizontal_distance_for_cursor_move", resources.getInteger(R.integer.config_default_horizontal_scroll_distance_for_cursor_move));
    }

    public static int getScrollVerticalDistanceForCursorMove(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_scroll_vertical_distance_for_cursor_move", resources.getInteger(R.integer.config_default_vertical_scroll_distance_for_cursor_move));
    }

    public static int getScrollSingleLineVerticalDistanceForCursorMove(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_scroll_single_line_vertical_distance_for_cursor_move", resources.getInteger(R.integer.config_default_single_line_vertical_scroll_distance_for_cursor_move));
    }

    public static int getScrollCursorMoveMaxSpeedMultiplier(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_scroll_cursor_move_max_speed_multiplier", resources.getInteger(R.integer.config_default_max_cursor_move_speed_multiplier));
    }

    public static int getScrollHorizontalDistanceForAccentsChange(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_scroll_horizontal_distance_for_accents_change", resources.getInteger(R.integer.config_default_horizontal_scroll_distance_for_accents_change));
    }

    public static int getMaxScrollAccentSpeedMultiplier(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_scroll_accents_change_max_speed_multiplier", resources.getInteger(R.integer.config_default_max_accents_move_speed_multiplier));
    }

    public static int getCursorMoveVelocityForMaxSpeedMultiplier(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_key_cursor_move_velocity_for_max_speed_multiplier", resources.getInteger(R.integer.config_default_velocity_for_max_cursor_move_speed));
    }

    public static boolean isAllowHorizontalCursorBeyondField(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("pref_key_allow_horizontal_cursor_beyond_field", resources.getBoolean(R.bool.config_default_horizontal_cursor_move_can_change_fields));
    }

    public static boolean isAllowBatchedCursorMove(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("pref_key_allow_batched_cursor_move", resources.getBoolean(R.bool.config_default_allow_batched_cursor_move));
    }

    public static boolean isSwipeSuppressionTimeoutAppliedToEnd(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("pref_key_swipe_suppression_timeout_applies_to_end", resources.getBoolean(R.bool.config_default_apply_swipe_suppression_timeout_to_swipe_end));
    }

    /**
     * @param key the preference key
     * @param defaultResId the string resource id of the default
     */
    public static int getStringAsInt(SharedPreferences sharedPreferences, Resources resources, String key, int defaultResId) {
        String string = sharedPreferences.getString(key, resources.getString(defaultResId));
        try {
            return Integer.parseInt(string);
        } catch (NumberFormatException unused) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Setting for " + key + " of \"" + string + "\" cannot be converted to int.");
            return 0;
        }
    }

    /**
     * @param key the preference key
     * @param defaultResId the string resource id of the default
     */
    public static String getStringPref(SharedPreferences sharedPreferences, Resources resources, String key, int defaultResId) {
        return sharedPreferences.getString(key, resources.getString(defaultResId));
    }

    public static boolean isKoreanDoubleConsonantResolutionEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("pref_korean_double_consonant_resolution", false);
    }

    /** Also read by DebugSettingsScreen. */
    public static final int DEFAULT_KOREAN_DOUBLE_CONSONANT_RESOLUTION_DELAY_MS = 350;

    public static int getKoreanDoubleConsonantResolutionDelay(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("pref_korean_double_consonant_resolution_delay", DEFAULT_KOREAN_DOUBLE_CONSONANT_RESOLUTION_DELAY_MS);
    }

    public static void enablePersonalizedDicts(SharedPreferences sharedPreferences) {
        SharedPreferences.Editor editorEdit = sharedPreferences.edit();
        editorEdit.putBoolean("pref_key_use_personalized_dicts", true);
        editorEdit.apply();
    }

    public static void enableContactsDict(SharedPreferences sharedPreferences) {
        SharedPreferences.Editor editorEdit = sharedPreferences.edit();
        editorEdit.putBoolean("pref_key_use_contacts_dict", true);
        editorEdit.putBoolean("pref_spellcheck_use_contacts", true);
        editorEdit.apply();
    }

    public static boolean hasAskedForContactsPermission(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("pref_key_has_asked_contacts_dict_permission", false);
    }

    /** @param hasAsked true once the contacts permission has been asked for */
    public static void setHasAskedContactsDictPermission(SharedPreferences sharedPreferences, boolean hasAsked) {
        SharedPreferences.Editor editorEdit = sharedPreferences.edit();
        editorEdit.putBoolean("pref_key_has_asked_contacts_dict_permission", hasAsked);
        editorEdit.apply();
    }

    public static boolean isUnifyLearnedWordsEnabled(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("unify_learned_words", true);
    }

    public static boolean isUimEnabled(Context context) {
        SharedPreferences prefs = PrefsManager.INSTANCE.getPrefs(context);
        // On by its setting, or while the "Show or hide the input menu" key has asked for it.
        return SettingsValues.isInputMenuRequestedByKey() || prefs.getBoolean("pref_uim_enabled", true);
    }

    /** "Enable auto-correction", the switch over the two strength choices. */
    public static final String PREF_AUTO_CORRECTION_ENABLED = "auto_correction_enabled";

    public static boolean isAutoCorrectionSwitchedOn(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean(PREF_AUTO_CORRECTION_ENABLED, true);
    }

    /**
     * Whether the bar above the physical keys has suggestions or the input menu to show. Read
     * from the preferences, for the listener that reacts before SettingsValues reloads.
     */
    public static boolean pkbBarHasContent(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("show_predictions", resources.getBoolean(R.bool.config_default_show_predictions))
                || sharedPreferences.getBoolean("pref_uim_enabled", resources.getBoolean(R.bool.config_default_uim_enabled));
    }

    public static boolean isLanguageQuickSwitchEnabled() {
        SettingsValues c0804dM5050c = getInstance().getSettingsValues();
        if (c0804dM5050c != null) {
            return c0804dM5050c.isLanguageQuickSwitchEnabled && RichInputMethodManager.getInstance().hasMultipleEnabledSubtypesInThisIme(false);
        }
        return false;
    }

    public static boolean isVoiceInputEnabled(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("voice_input_enabled", true);
    }

    /**
     * Shows suggestions in normal text fields even when the editor declares "no suggestions"
     * (Google Keep's note editor does). Off by default.
     */
    public static boolean isForceSuggestionsEnabled(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("pref_force_suggestions", false);
    }

    public static boolean isVoiceInputUseInputLanguage(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("voice_input_use_input_language", true);
    }

    public static boolean isVoiceInputPreferOffline(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("voice_input_prefer_offline", true);
    }

    /** "Show words as you speak": partial results appear as composing text until the final one. */
    public static boolean isVoiceInputShowPartialResults(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("voice_input_show_partial_results", true);
    }

    /** A BCP-47 tag, the form {@code RecognizerIntent.EXTRA_LANGUAGE} takes and the picker stores. */
    public static final String DEFAULT_VOICE_INPUT_LANGUAGE = "en-US";

    public static String getVoiceInputLanguageList(SharedPreferences sharedPreferences) {
        // Normalise any underscore locale string ("en_US") to the tag form the recognizer takes.
        String language = sharedPreferences.getString("voice_input_language_list", DEFAULT_VOICE_INPUT_LANGUAGE);
        return language == null ? DEFAULT_VOICE_INPUT_LANGUAGE : language.replace('_', '-');
    }

    /**
     * "Stop listening when you pause" (default on). Off keeps the session going until the mic is
     * tapped or a long silence; see VoiceRecognitionManager's continuous mode.
     */
    public static boolean isVoiceInputStopOnPause(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("voice_input_stop_on_pause", true);
    }

    public static boolean isVoiceInputAutoStart(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("voice_input_auto_start", true);
    }

    /**
     * "Speech recognizer": empty for the default service, {@code "ondevice"}, or a flattened
     * service component ({@code VoiceRecognizerChoice} resolves it). Read from the preferences
     * because the voice screens need it outside the keyboard too.
     */
    public static String getVoiceInputRecognizer(SharedPreferences sharedPreferences) {
        final String recognizer = sharedPreferences.getString("voice_input_recognizer", "");
        return recognizer == null ? "" : recognizer;
    }

    /**
     * Stores the list joined by U+0378, an unassigned code point.
     *
     * @param key the preference key
     */
    public static void setStringListPref(SharedPreferences sharedPreferences, String key, List<String> list) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            sb.append("\u0378");
        }
        sharedPreferences.edit().putString(key, sb.toString()).apply();
    }

    /** @param key the preference key */
    public static List<String> getStringListPref(SharedPreferences sharedPreferences, String key) {
        String savedString = sharedPreferences.getString(key, "");
        if (savedString.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.asList(savedString.split("\u0378"));
    }



    public static boolean isVkbSwipeDownEnabled(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("swipe_down_vkb", true);
    }

    public static boolean isEmojiDynamicSearchEnabled(SharedPreferences sharedPreferences) {
        // Falls back to the legacy "pref_emoji_search_mode": only "dynamic" maps to enabled.
        if (sharedPreferences.contains("pref_emoji_dynamic_search")) {
            return sharedPreferences.getBoolean("pref_emoji_dynamic_search", false);
        }
        return "dynamic".equals(sharedPreferences.getString("pref_emoji_search_mode", "none"));
    }

    public static boolean isEmojiSearchReplaceTextEnabled(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("pref_emoji_search_replace_text", true);
    }

    /**
     * Physical keyboard "Enable emoji search": keys type into the emoji board's search field. Off,
     * they type into the editor and a text key closes the board. Not the on-screen switch.
     */
    public static boolean isPkbEmojiSearchEnabled(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("pref_pkb_emoji_search", true);
    }
}
