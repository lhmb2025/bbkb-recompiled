package dev.bbkb.ime.core.settings.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.provider.Settings;
import android.util.Log;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
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

/**
 * Singleton manager for keyboard settings and preferences.
 * Loads and caches SettingsValues, manages SharedPreferences listeners,
 * and provides static utility methods for accessing individual settings.
 * Thread-safe using ReentrantLock for concurrent access.
 */



public final class SettingsManager implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "SettingsManager";

    @android.annotation.SuppressLint("StaticFieldLeak") // Uses applicationContext (lines 113, 167), safe for static
    private static final SettingsManager instance;

    private static final String DEFAULT_INT_VALUE_STRING;

    private Context context;

    private Resources resources;

    private SharedPreferences sharedPreferences;

    private SettingsValues settingsValues;

    /* Device profile for centralized device capability detection */
    private DeviceProfile deviceProfile;

    private ArrayList<OnSettingsChangeListener> listeners;

    private final ReentrantLock lock = new ReentrantLock();

    
    public interface OnSettingsChangeListener {
        void onSettingsValuesChanged(SettingsValues c0804d);
    }

    public static boolean isResourceNonZero(int i, Resources resources) {
        return i != 0;
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
        // Use applicationContext to prevent memory leaks in static singleton
        this.context = context.getApplicationContext();
        this.resources = context.getResources();
        this.listeners = new ArrayList<>();
        this.sharedPreferences = PrefsManager.INSTANCE.getPrefs(context);
        this.sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        // First-load: contains() blocks until the prefs file finishes its async load,
        // and this runs first thing in onCreate — do the migration (and the blocking
        // wait behind it) off the main thread. The removed keys are legacy-only, so
        // nothing on the critical path observes them.
        new Thread(this::migrateLegacyPreferences, "PrefsMigrate").start();
        StartupTiming.end("settings.initializeInternal", token);
    }

    public void unregisteredListener() {
        this.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    /**
     * Keys written during normal typing that no {@link SettingsValues} field reads. Rebuilding
     * the whole settings object for them (~150 preference reads, a new SpacingAndPunctuation, a
     * RunInLocale configuration swap, a DeviceProfile rebuild, plus a notification to every
     * listener) happened on the main thread on every emoji insertion and every emoji category
     * swipe.
     */
    private static final java.util.Set<String> KEYS_NOT_IN_SETTINGS_VALUES =
            new java.util.HashSet<>(Arrays.asList("emoji_recent_keys", "last_shown_emoji_category_id"));

    @Override // android.content.SharedPreferences.OnSharedPreferenceChangeListener
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String str) {
        if (str != null && KEYS_NOT_IN_SETTINGS_VALUES.contains(str)) {
            return;
        }
        this.lock.lock();
        try {
            if (this.settingsValues == null) {
                return;
            }
            loadSettings(this.context, this.settingsValues.locale, this.settingsValues.editorCapabilities);
            Iterator<OnSettingsChangeListener> it = this.listeners.iterator();
            while (it.hasNext()) {
                it.next().onSettingsValuesChanged(getSettingsValues());
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void addOnSettingsChangeListener(OnSettingsChangeListener aVar) {
        this.lock.lock();
        try {
            this.listeners.add(aVar);
        } finally {
            this.lock.unlock();
        }
    }

    public void removeOnSettingsChangeListener(OnSettingsChangeListener aVar) {
        this.lock.lock();
        try {
            this.listeners.remove(aVar);
        } finally {
            this.lock.unlock();
        }
    }

    public void loadSettings(final Context context, Locale locale, final EditorCapabilities c0696l) {
        final long token = StartupTiming.begin();
        this.lock.lock();
        // Use applicationContext to prevent memory leaks in static singleton
        this.context = context.getApplicationContext();
        try {
            final SharedPreferences sharedPreferences = this.sharedPreferences;
            this.settingsValues = new RunInLocale<SettingsValues>() {
                @Override
                public SettingsValues job(Resources resources) {
                    return new SettingsValues(context, sharedPreferences, resources, c0696l);
                }
            }.runInLocale(this.context, locale);
            // Create/update device profile based on current configuration
            this.deviceProfile = DeviceProfile.fromSettings(context, this.settingsValues);
        } finally {
            this.lock.unlock();
            // The occurrence counter in the log line is the point: this is the ~191 ms unit the
            // tracker records, and how many times it runs before the first frame is the question.
            StartupTiming.end("settings.loadSettings", token);
        }
    }

    /**
     * One-shot preference migrations. These used to run from inside the {@link SettingsValues}
     * constructor and from {@link #isLanguageSwitchKeyVisible}: writing preferences there
     * dispatched {@link #onSharedPreferenceChanged} synchronously on the calling thread, which
     * re-entered {@link #loadSettings} and notified every listener with the inner value first.
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
        if (prefs.contains("pref_suppress_language_switch_key")) {
            prefs.edit()
                    .putBoolean("pref_show_language_switch_key", !prefs.getBoolean("pref_suppress_language_switch_key", false))
                    .remove("pref_suppress_language_switch_key")
                    .apply();
        }
    }

    public SettingsValues getSettingsValues() {
        return this.settingsValues;
    }

    /**
     * Get the current device profile.
     * DeviceProfile centralizes device capability detection (PKB vs VKB, touch keypad, etc.)
     * 
     * @return The current DeviceProfile, or null if not yet initialized
     */
    public DeviceProfile getDeviceProfile() {
        return this.deviceProfile;
    }

    /**
     * @return true if this is a physical keyboard device
     */
    public boolean isPkbDevice() {
        return deviceProfile != null && deviceProfile.isPkbDevice();
    }

    /**
     * @return true if this is a virtual keyboard (touchscreen-only) device
     */
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
     * The height scale actually applied, given the current orientation. Landscape always
     * renders extra-compact regardless of the user's height mode -- deliberate and not a
     * user-facing setting. This replaces the deleted values-land sizing table (see
     * docs/landscape-dimensions.md) with a single factor through the height manager.
     */
    public static float getEffectiveKeyboardHeightScale(Resources resources) {
        if (resources.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return HEIGHT_SCALE_EXTRA_COMPACT;
        }
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

    public static boolean isLanguageSwitchKeyVisible(SharedPreferences sharedPreferences) {
        // The "pref_suppress_language_switch_key" migration runs in migrateLegacyPreferences().
        return sharedPreferences.getBoolean("pref_show_language_switch_key", true);
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

    public static void setCangjieMode(SharedPreferences sharedPreferences, int i) {
        sharedPreferences.edit().putInt("cangjie_mode", i).apply();
    }

    public static int getCangjieMode(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getInt("cangjie_mode", 1);
    }

    public static float getKeypressSoundVolume(SharedPreferences sharedPreferences, Resources resources) {
        // Two on-disk forms exist. The original APK's seek bar wrote putFloat as a 0..1 fraction,
        // which is what SoundPool.play takes. The Compose KeyPressFeedbackScreen writes putInt as a
        // 10..100 percent. SharedPreferences is typed, so a bare getFloat threw ClassCastException
        // on the int form out of every SettingsValues rebuild. Read whichever is stored; -1 in
        // either type (or no entry) means the resource default.
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

    public static int getIntPref(SharedPreferences sharedPreferences, Resources resources, String str, int i) {
        int i2 = sharedPreferences.getInt(str, -1);
        return i2 != -1 ? i2 : getIntResource(resources, i);
    }

    private static int getIntResource(Resources resources, int i) {
        return resources.getInteger(i);
    }

    public static int getVibrationDuration(SharedPreferences sharedPreferences, Resources resources) {
        int i = sharedPreferences.getInt("pref_vibration_duration_settings", -1);
        return i != -1 ? i : getDefaultVibrationDuration(resources);
    }

    private static int getDefaultVibrationDuration(Resources resources) {
        return Integer.parseInt(DEFAULT_INT_VALUE_STRING);
    }

    public static int getIntPrefOrDefault(SharedPreferences sharedPreferences, String str, int i) {
        int i2 = sharedPreferences.getInt(str, -1);
        return i2 != -1 ? i2 : i;
    }

    public static boolean isFullscreenModeEnabled(Resources resources) {
        return resources.getBoolean(R.bool.config_use_fullscreen_mode);
    }

    public static boolean hasHardwareKeyboard(Configuration configuration) {
        return (configuration.keyboard == 1 || configuration.hardKeyboardHidden == 2) ? false : true;
    }

    public static boolean isInternalBuild(Context context, SharedPreferences sharedPreferences) {
        // Was: (isDiagnosticsEnabled() || "alpha".equals("production")) && a Settings.Global probe.
        // Both operands of the || were constant false (the second compares two string literals),
        // so the whole condition, and the content-resolver query behind it, were dead.
        final boolean defaultValue = BuildConfig.DEBUG
                && Settings.Global.getInt(context.getContentResolver(), "development_settings_enabled", 0) != 0;
        return sharedPreferences.getBoolean("pref_key_is_internal", defaultValue);
    }

    public static void setEmojiRecentKeys(SharedPreferences sharedPreferences, String str) {
        sharedPreferences.edit().putString("emoji_recent_keys", str).apply();
    }

    public static String getEmojiRecentKeys(SharedPreferences sharedPreferences) {
        return sharedPreferences.getString("emoji_recent_keys", "");
    }

    public static void setLastShownEmojiCategory(SharedPreferences sharedPreferences, int i) {
        sharedPreferences.edit().putInt("last_shown_emoji_category_id", i).apply();
    }

    public static int getLastShownEmojiCategory(SharedPreferences sharedPreferences, int i) {
        return sharedPreferences.getInt("last_shown_emoji_category_id", i);
    }

    public static int getControlMode(SharedPreferences sharedPreferences, Resources resources) {
        return Integer.parseInt(sharedPreferences.getString("control_mode", "0"));
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

    public static int getIntPrefWithResourceDefault(SharedPreferences sharedPreferences, Resources resources, String str, int i) {
        int i2 = sharedPreferences.getInt(str, -1);
        return i2 != -1 ? i2 : resources.getInteger(i);
    }

    public static float getFloatPrefWithResourceDefault(SharedPreferences sharedPreferences, Resources resources, String str, int i) {
        float f;
        try {
            f = sharedPreferences.getFloat(str, -1.0f);
        } catch (ClassCastException e) {
            // An older Advanced Gesture Parameters screen wrote the cursor tap-region scales with
            // putInt. SharedPreferences is typed, so getFloat throws on such an entry and every
            // SettingsValues construction after the user touched one of those sliders died here.
            // Drop the bad value and fall back to the resource default.
            sharedPreferences.edit().remove(str).apply();
            f = -1.0f;
        }
        return f != -1.0f ? f : ResourceConfigManager.getFractionValue(resources, i);
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

    public static int getStringAsInt(SharedPreferences sharedPreferences, Resources resources, String str, int i) {
        String string = sharedPreferences.getString(str, resources.getString(i));
        try {
            return Integer.parseInt(string);
        } catch (NumberFormatException unused) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Setting for " + str + " of \"" + string + "\" cannot be converted to int.");
            return 0;
        }
    }

    public static String getStringPref(SharedPreferences sharedPreferences, Resources resources, String str, int i) {
        return sharedPreferences.getString(str, resources.getString(i));
    }

    public static boolean isKoreanDoubleConsonantResolutionEnabled(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("pref_korean_double_consonant_resolution", false);
    }

    /**
     * The original APK's literal. It had an {@code R.integer} for this too, but that resource did not
     * survive into this tree; DebugSettingsScreen reads this constant rather than a second literal.
     */
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

    public static void setHasAskedContactsDictPermission(SharedPreferences sharedPreferences, boolean z) {
        SharedPreferences.Editor editorEdit = sharedPreferences.edit();
        editorEdit.putBoolean("pref_key_has_asked_contacts_dict_permission", z);
        editorEdit.apply();
    }

    public static boolean isUnifyLearnedWordsEnabled(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("unify_learned_words", true);
    }

    public static boolean isUimEnabled(Context context) {
        SharedPreferences prefs = PrefsManager.INSTANCE.getPrefs(context);
        return prefs.getBoolean("pref_uim_enabled", true);
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
     * Bug #3 escape hatch: when enabled, the keyboard overrides an editor's "no suggestions"
     * declaration and shows the suggestion strip anyway for normal text fields. Some apps
     * (e.g. Google Keep's note editor) declare their field in a way that suppresses
     * suggestions; this lets the user force them on. Defaults to off so default behavior
     * (honoring the editor) is unchanged.
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

    /**
     * A BCP-47 language tag, the form {@code RecognizerIntent.EXTRA_LANGUAGE} documents and the form
     * the voice language picker stores (the recognizer's own supported-language codes). Both voice
     * settings screens read this constant.
     */
    public static final String DEFAULT_VOICE_INPUT_LANGUAGE = "en-US";

    public static String getVoiceInputLanguageList(SharedPreferences sharedPreferences) {
        // The original default was "en_US" (a Java locale string) while everything stored is a
        // hyphenated tag, so the default and a stored value were not comparable. Normalise to the
        // tag form so an underscore value, whoever wrote it, still reaches the recognizer correctly.
        String language = sharedPreferences.getString("voice_input_language_list", DEFAULT_VOICE_INPUT_LANGUAGE);
        return language == null ? DEFAULT_VOICE_INPUT_LANGUAGE : language.replace('_', '-');
    }

    public static boolean isVoiceInputAutoStart(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("voice_input_auto_start", true);
    }

    // REMOVED: Tutorial-related methods - Tutorial system eliminated
    // - getOnBoardNotification()
    // - setOnBoardNotification()
    // - hasSeenOnBoardTutorial()
    // - setOnBoardTutorialSeen()

    public static void setStringListPref(SharedPreferences sharedPreferences, String str, List<String> list) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            sb.append("\u0378");
        }
        sharedPreferences.edit().putString(str, sb.toString()).apply();
    }

    public static List<String> getStringListPref(SharedPreferences sharedPreferences, String str) {
        String savedString = sharedPreferences.getString(str, "");
        if (savedString.isEmpty()) {
            return new ArrayList<>();  // Return empty list for empty string
        }
        return Arrays.asList(savedString.split("\u0378"));
    }



    public static boolean isVkbSwipeDownEnabled(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("swipe_down_vkb", true);
    }

    public static boolean isEmojiDynamicSearchEnabled(SharedPreferences sharedPreferences) {
        // Migrated from the legacy "pref_emoji_search_mode" multi-option setting
        // (none / search_bar / dynamic). Only the old "dynamic" value maps to enabled;
        // "search_bar" and "none" map to disabled.
        if (sharedPreferences.contains("pref_emoji_dynamic_search")) {
            return sharedPreferences.getBoolean("pref_emoji_dynamic_search", false);
        }
        return "dynamic".equals(sharedPreferences.getString("pref_emoji_search_mode", "none"));
    }

    public static boolean isEmojiSearchReplaceTextEnabled(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean("pref_emoji_search_replace_text", true);
    }
}
