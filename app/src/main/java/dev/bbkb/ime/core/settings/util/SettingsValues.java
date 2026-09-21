package dev.bbkb.ime.core.settings.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import androidx.core.content.ContextCompat;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.locale.LocaleUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import dev.bbkb.ime.BuildConfig;

/**
 * Immutable data class containing all keyboard settings and configuration values.
 * Loaded from SharedPreferences and Resources, providing type-safe access to settings.
 * Contains ~200+ settings covering input behavior, UI preferences, gesture parameters,
 * language options, and feature flags. Thread-safe and designed for caching.
 */



public final class SettingsValues {

    private static final String TAG = "SettingsValues";

    public final int shiftKeyLongPressTimeout;

    public final boolean showUiToAcceptTypedWord;

    public final EditorCapabilities editorCapabilities;

    public final int keypressVibrationDuration;

    public final float keypressSoundVolume;

    public final int keyPreviewPopupDismissDelay;

    public final boolean isPredictionsEnabled;

    public final boolean isOnKeyPredictionsEnabled;

    public final boolean isEmojiPredictionsEnabled;

    public final boolean isDynamicLearningEnabled;

    public final boolean isLanguageQuickSwitchEnabled;

    public final boolean isSpacebarLanguageSwitchingEnabled;

    public final String currencySymbol;

    public final boolean isVoiceInputEnabled;

    public final boolean isSlideboardEnabled;

    public final int slideboardKeyLongpressTimeout;

    public final int slideboardNumericLocation;

    public final int slideboardQuickPhrasesLocation;

    public final String quickPhrase1;

    public final String quickPhrase2;

    public final String quickPhrase3;

    public final String quickPhrase4;

    public final String quickPhrase5;

    public final boolean slideboardStillBoardsEnabled;

    public final List<String> customSlideboardSymbols;

    public final SpacingAndPunctuation spacingAndPunctuation;

    public final int fastVerticalSwipeMinY;

    public final int fastVerticalSwipeMinVelocity;

    public final int slowVerticalSwipeMinY;

    public final int slowVerticalSwipeMinVelocity;

    public final int swipeGestureTimeout;

    public final int flowModeVerticalSwipeMinY;

    public final int flowModeVerticalSwipeMinVelocity;

    public final float horizontalCursorTapRegionHeightScale;

    public final float horizontalCursorTapRegionWidthScale;

    public final float verticalCursorTapRegionHeightScale;

    public final float verticalCursorTapRegionWidthScale;

    public final int horizontalScrollDistanceForCursorMove;

    public final int verticalScrollDistanceForCursorMove;

    public final int singleLineVerticalScrollForCursorMove;

    public final int maxCursorMoveSpeedMultiplier;

    public final int velocityForMaxCursorMoveSpeed;

    public final boolean allowHorizontalCursorBeyondField;

    public final boolean allowBatchedCursorMove;

    public final int horizontalScrollForAccentsChange;

    public final int maxAccentsChangeSpeedMultiplier;

    public final float inLetterMaxSwipeToWordDistance;

    public final boolean isUimEnabled;

    public final boolean flickCommitAnimationEnabled;

    public final int autoCorrectionMode;

    public final boolean isAutoCorrectionEnabledPerUserSettings;

    public final int[] additionalFeaturesSettings = new int[0];

    public final int textHighlightColorForAddToDictionary;

    public final int textHighlightColorForMultiTap;

    public final boolean isInternal;

    public final boolean hasCustomKeyPreviewAnimationParams;

    // REMOVED: areTutorialsEnabled - Tutorial system eliminated

    // REMOVED: ignoreTutorialDelayThresholds - Tutorial system eliminated

    public final int keyPreviewShowUpDuration;

    public final int keyPreviewDismissDuration;

    public final float keyPreviewShowUpStartScaleX;

    public final float keyPreviewShowUpStartScaleY;

    public final float keyPreviewDismissEndScaleX;

    public final float keyPreviewDismissEndScaleY;

    public final int ckbGestureSuppressionTimeout;

    public final int vkbGestureSuppressionTimeout;

    public final int accentSelectionSuppressionTimeout;

    public final int doubleTapSuppressionTimeout;

    public final boolean applySwipeSuppressionToEnd;

    public final double horizontalSwipeTanTheta;

    public final double verticalSwipeTanTheta;

    public final int fastHorizontalSwipeMinX;

    public final int fastHorizontalSwipeMinVelocity;

    public final int slowHorizontalSwipeMinX;

    public final int slowHorizontalSwipeMinVelocity;

    public final int delayToUpdateOldSuggestionsMs;

    public final int uimFocusMoveDelay;

    public final boolean isKoreanDoubleConsonantResolutionEnabled;

    public final int koreanDoubleConsonantResolutionDelayMs;

    public final int showOnKeyPressMode;

    public final int controlMode;

    public final boolean isVkbControlModeEnabled;

    public final int cangjieMode;

    public final int shakeActionX;

    public final int shakeActionY;

    public final int shakeActionZ;

    public final int shakeActionFallback;

    public final int shakeOnAxisTriggerCount;

    public final int shakeFallbackTriggerCount;

    public final int shakeAccelerationThreshold;

    public final long shakeSlopTime;

    public final long shakeResetTime;

    public final boolean unifyLearnedWords;

    public final Boolean overrideDeviceMetaState;

    public final boolean shiftDoubleTapLock;

    public final boolean altDoubleTapLock;

    public final boolean showPkbModifierStatusIcon;

    public final boolean voiceInputUseInputLanguage;

    public final boolean voiceInputPreferOffline;

    public final String voiceInputLanguageList;

    public final boolean isEmojiDynamicSearchEnabled;

    public final String altSymShortcutAction;

    /** Multifunction key action id ("" = use the device config's default-action). */
    public final String multifunctionKeyAction;

    public final boolean isEmojiSearchReplaceText;

    public final boolean isCkbGestureInputEnabled;

    private final boolean isAutoCorrectEnabled;

    public final long doubleSpacePeriodTimeoutMs;

    public final Locale locale;

    public final boolean hasHardwareKeyboard;

    public final int displayOrientation;

    public final boolean isAutoCapsEnabled;

    public final boolean isVibrationEnabled;

    public final boolean isSoundEnabled;

    public final boolean isKeyPreviewPopupEnabled;

    public final boolean showsVoiceInputKey;

    public final boolean includesOtherImesInLanguageSwitch;

    public final boolean showsLanguageSwitchKey;

    public final boolean useContactsDicts;

    public final boolean usePersonalizedDicts;

    public final boolean useDoubleSpacePeriod;

    public final boolean blockPotentiallyOffensiveWords;

    public final boolean isBigramPredictionEnabled;

    public final boolean isVkbGestureInputEnabled;

    public final boolean isVkbSwipeGesturesEnabled;




    public final boolean isVkbSwipeDownEnabled;


    public final boolean shouldShowLxxButton;

    public final boolean isSlidingKeyInputPreviewEnabled;

    public final int keyLongpressTimeoutMs;

    public final String keyboardHeightMode;

    /**
     * ASCII-only letter-or-digit test. Deliberately narrower than
     * {@link Character#isLetterOrDigit(int)} - callers use it to decide whether a code point is
     * part of a plain latin word. (Renaming it to {@code isAsciiLetterOrDigit} needs the
     * RecorrectionController call site, which is outside this change.)
     */
    public boolean isLetterOrDigit(int i) {
        return (i >= 'A' && i <= 'Z') || (i >= 'a' && i <= 'z') || (i >= '0' && i <= '9');
    }

    public SettingsValues(Context context, SharedPreferences sharedPreferences, Resources resources, EditorCapabilities c0696l) {
        float touchKeypadResolution = DeviceProfile.current().getTouchKeypadResolution();
        float fM5549d = touchKeypadResolution > 1.0f ? touchKeypadResolution : resources.getDisplayMetrics().density;
        if (BuildConfig.DEBUG) Log.i(TAG, "Using density of " + fM5549d);
        this.locale = LocaleUtils.getConfigurationLocale(resources);
        this.delayToUpdateOldSuggestionsMs = resources.getInteger(R.integer.config_delay_in_msec_to_update_old_suggestions);
        this.spacingAndPunctuation = new SpacingAndPunctuation(resources);
        if (c0696l == null) {
            this.editorCapabilities = new EditorCapabilities(null, false, context.getPackageName(), this.locale, false);
        } else {
            this.editorCapabilities = c0696l;
        }
        this.isAutoCapsEnabled = sharedPreferences.getBoolean("auto_cap", true);
        this.isVibrationEnabled = SettingsManager.isVibrationEnabled(sharedPreferences, resources);
        this.isSoundEnabled = SettingsManager.isSoundEnabled(sharedPreferences, resources);
        this.isKeyPreviewPopupEnabled = SettingsManager.readKeyPreviewPopupEnabled(sharedPreferences, resources);
        this.isSlidingKeyInputPreviewEnabled = sharedPreferences.getBoolean("pref_sliding_key_input_preview", true);
        this.showsVoiceInputKey = loadVoiceInputKeySetting(sharedPreferences, resources) && this.editorCapabilities.isMicrophoneAllowed && SubtypeManager.getInstance().isShortcutImeEnabled();
        this.includesOtherImesInLanguageSwitch = sharedPreferences.getBoolean("pref_include_other_imes_in_language_switch_list", false);
        this.showsLanguageSwitchKey = SettingsManager.isLanguageSwitchKeyVisible(sharedPreferences);
        this.useContactsDicts = sharedPreferences.getBoolean("pref_key_use_contacts_dict", true);
        this.usePersonalizedDicts = sharedPreferences.getBoolean("pref_key_use_personalized_dicts", true);
        this.useDoubleSpacePeriod = sharedPreferences.getBoolean("pref_key_use_double_space_period", true) && c0696l.normalVariation;
        this.blockPotentiallyOffensiveWords = SettingsManager.isBlockOffensiveEnabled(sharedPreferences, resources);
        this.isBigramPredictionEnabled = loadBigramPredictionsSetting(sharedPreferences, resources);
        this.doubleSpacePeriodTimeoutMs = resources.getInteger(R.integer.config_double_space_period_timeout);
        this.hasHardwareKeyboard = SettingsManager.hasHardwareKeyboard(resources.getConfiguration());
        // Metrics logging removed - was non-functional dead code
        this.showUiToAcceptTypedWord = sharedPreferences.getBoolean("pref_show_ui_to_accept_typed_word", true);
        this.shiftKeyLongPressTimeout = resources.getInteger(R.integer.config_longpress_shift_key_timeout);
        this.keyLongpressTimeoutMs = SettingsManager.getIntPref(sharedPreferences, resources, "pref_key_longpress_timeout", R.integer.config_default_longpress_key_timeout);
        // REMOVED: Tutorial settings initialization - Tutorial system eliminated
        this.keypressVibrationDuration = SettingsManager.getVibrationDuration(sharedPreferences, resources);
        this.keypressSoundVolume = SettingsManager.getKeypressSoundVolume(sharedPreferences, resources);
        this.keyPreviewPopupDismissDelay = SettingsManager.getKeyPreviewDismissDelay(sharedPreferences, resources);
        this.autoCorrectionMode = loadAutoCorrectionMode(context, sharedPreferences, resources);
        this.isVkbGestureInputEnabled = SettingsManager.isVkbTypeBySwipingEnabled(sharedPreferences, resources);
        this.isCkbGestureInputEnabled = SettingsManager.isCbkTypeBySwipingEnabled(sharedPreferences, resources);
        this.isVkbSwipeDownEnabled = SettingsManager.isVkbSwipeDownEnabled(sharedPreferences);
        this.isVkbSwipeGesturesEnabled = SettingsManager.isVkbSwipeGesturesEnabled(sharedPreferences, resources);
        this.isAutoCorrectEnabled = SettingsManager.isResourceNonZero(this.autoCorrectionMode, resources);
        this.isAutoCorrectionEnabledPerUserSettings = this.isAutoCorrectEnabled && !this.editorCapabilities.noAutoCorrect;
        this.isPredictionsEnabled = loadPredictionsEnabledSetting(sharedPreferences, resources);
        this.shouldShowLxxButton = shouldShowLxxButton(context);
        this.isOnKeyPredictionsEnabled = loadOnKeyPredictionsSetting(sharedPreferences, resources);
        this.isEmojiPredictionsEnabled = loadEmojiPredictionsSetting(sharedPreferences, resources);
        this.textHighlightColorForAddToDictionary = ContextCompat.getColor(context, R.color.text_decorator_add_to_dictionary_indicator_text_highlight_color);
        this.textHighlightColorForMultiTap = ContextCompat.getColor(context, R.color.multitap_highlight_color);
        this.isInternal = SettingsManager.isInternalBuild(context, sharedPreferences);
        this.hasCustomKeyPreviewAnimationParams = sharedPreferences.getBoolean("pref_has_custom_key_preview_animation_params", false);
        this.keyPreviewShowUpDuration = SettingsManager.getIntPrefOrDefault(sharedPreferences, "pref_key_preview_show_up_duration", resources.getInteger(R.integer.config_key_preview_show_up_duration));
        this.keyPreviewDismissDuration = SettingsManager.getIntPrefOrDefault(sharedPreferences, "pref_key_preview_dismiss_duration", resources.getInteger(R.integer.config_key_preview_dismiss_duration));
        this.keyPreviewShowUpStartScaleX = SettingsManager.getFloatPrefWithResourceDefault(sharedPreferences, resources, "pref_key_preview_show_up_start_x_scale", R.fraction.config_key_preview_show_up_start_scale);
        this.keyPreviewShowUpStartScaleY = SettingsManager.getFloatPrefWithResourceDefault(sharedPreferences, resources, "pref_key_preview_show_up_start_y_scale", R.fraction.config_key_preview_show_up_start_scale);
        this.keyPreviewDismissEndScaleX = SettingsManager.getFloatPrefWithResourceDefault(sharedPreferences, resources, "pref_key_preview_dismiss_end_x_scale", R.fraction.config_key_preview_dismiss_end_scale);
        this.keyPreviewDismissEndScaleY = SettingsManager.getFloatPrefWithResourceDefault(sharedPreferences, resources, "pref_key_preview_dismiss_end_y_scale", R.fraction.config_key_preview_dismiss_end_scale);
        this.displayOrientation = resources.getConfiguration().orientation;
        this.controlMode = SettingsManager.getControlMode(sharedPreferences, resources);
        this.isVkbControlModeEnabled = SettingsManager.isVkbControlModeEnabled(sharedPreferences, resources);
        this.isDynamicLearningEnabled = SettingsManager.isDynamicLearningEnabled(sharedPreferences, resources);
        this.isLanguageQuickSwitchEnabled = SettingsManager.isLanguageQuickSwitchEnabled(sharedPreferences, resources);
        this.isSpacebarLanguageSwitchingEnabled = SettingsManager.isSpacebarLanguageSwitchingEnabled(sharedPreferences, resources);
        this.currencySymbol = SettingsManager.getCurrencySymbol(sharedPreferences, resources);
        this.isVoiceInputEnabled = SettingsManager.isVoiceInputEnabled(sharedPreferences);
        this.ckbGestureSuppressionTimeout = SettingsManager.getIntPref(sharedPreferences, resources, "pref_CKB_gesture_suppression_timeout", R.integer.config_default_CKB_swipe_gesture_suppression_timeout);
        this.vkbGestureSuppressionTimeout = SettingsManager.getIntPref(sharedPreferences, resources, "pref_VKB_gesture_suppression_timeout", R.integer.config_default_VKB_swipe_gesture_suppression_timeout);
        this.accentSelectionSuppressionTimeout = SettingsManager.getIntPref(sharedPreferences, resources, "pref_accent_selection_suppression_timeout", R.integer.config_default_accent_selection_suppression_timeout);
        this.doubleTapSuppressionTimeout = SettingsManager.getIntPref(sharedPreferences, resources, "pref_doubletap_suppression_timeout", R.integer.config_default_doubletap_suppression_timeout);
        this.applySwipeSuppressionToEnd = SettingsManager.isSwipeSuppressionTimeoutAppliedToEnd(sharedPreferences, resources);
        this.horizontalSwipeTanTheta = Math.tan(Math.toRadians(SettingsManager.getHorizontalSwipeTheta(sharedPreferences, resources) / 2.0d));
        this.verticalSwipeTanTheta = Math.tan(Math.toRadians(SettingsManager.getVerticalSwipeTheta(sharedPreferences, resources) / 2.0d));
        this.fastHorizontalSwipeMinX = Math.round(SettingsManager.getFastHorizontalSwipeMinX(sharedPreferences, resources) * fM5549d);
        this.fastHorizontalSwipeMinVelocity = Math.round(SettingsManager.getFastHorizontalSwipeMinVelocity(sharedPreferences, resources) * fM5549d);
        this.fastVerticalSwipeMinY = Math.round(SettingsManager.getFastVerticalSwipeMinY(sharedPreferences, resources) * fM5549d);
        this.fastVerticalSwipeMinVelocity = Math.round(SettingsManager.getFastVerticalSwipeMinVelocity(sharedPreferences, resources) * fM5549d);
        this.flowModeVerticalSwipeMinY = Math.round(SettingsManager.getFlowModeVerticalSwipeMinY(sharedPreferences, resources) * fM5549d);
        this.flowModeVerticalSwipeMinVelocity = Math.round(SettingsManager.getFlowModeVerticalSwipeMinVelocity(sharedPreferences, resources) * fM5549d);
        this.slowHorizontalSwipeMinX = Math.round(SettingsManager.getSlowHorizontalSwipeMinX(sharedPreferences, resources) * fM5549d);
        this.slowHorizontalSwipeMinVelocity = Math.round(SettingsManager.getSlowHorizontalSwipeMinVelocity(sharedPreferences, resources) * fM5549d);
        this.slowVerticalSwipeMinY = Math.round(SettingsManager.getSlowVerticalSwipeMinY(sharedPreferences, resources) * fM5549d);
        this.slowVerticalSwipeMinVelocity = Math.round(SettingsManager.getSlowVerticalSwipeMinVelocity(sharedPreferences, resources) * fM5549d);
        this.swipeGestureTimeout = SettingsManager.getSwipeGestureTimeout(sharedPreferences, resources);
        this.horizontalCursorTapRegionHeightScale = SettingsManager.getFloatPrefWithResourceDefault(sharedPreferences, resources, "pref_key_horizontal_cursor_tap_region_height_scale", R.fraction.config_default_horizontal_cursor_tap_region_height_scale);
        this.horizontalCursorTapRegionWidthScale = SettingsManager.getFloatPrefWithResourceDefault(sharedPreferences, resources, "pref_key_horizontal_cursor_tap_region_width_scale", R.fraction.config_default_horizontal_cursor_tap_region_width_scale);
        this.verticalCursorTapRegionHeightScale = SettingsManager.getFloatPrefWithResourceDefault(sharedPreferences, resources, "pref_key_vertical_cursor_tap_region_height_scale", R.fraction.config_default_vertical_cursor_tap_region_height_scale);
        this.verticalCursorTapRegionWidthScale = SettingsManager.getFloatPrefWithResourceDefault(sharedPreferences, resources, "pref_key_vertical_cursor_tap_region_width_scale", R.fraction.config_default_vertical_cursor_tap_region_width_scale);
        this.horizontalScrollDistanceForCursorMove = Math.round(SettingsManager.getScrollHorizontalDistanceForCursorMove(sharedPreferences, resources) * fM5549d);
        this.verticalScrollDistanceForCursorMove = Math.round(SettingsManager.getScrollVerticalDistanceForCursorMove(sharedPreferences, resources) * fM5549d);
        this.singleLineVerticalScrollForCursorMove = Math.round(SettingsManager.getScrollSingleLineVerticalDistanceForCursorMove(sharedPreferences, resources) * fM5549d);
        this.maxCursorMoveSpeedMultiplier = SettingsManager.getScrollCursorMoveMaxSpeedMultiplier(sharedPreferences, resources);
        this.velocityForMaxCursorMoveSpeed = Math.round(SettingsManager.getCursorMoveVelocityForMaxSpeedMultiplier(sharedPreferences, resources) * fM5549d);
        this.allowHorizontalCursorBeyondField = SettingsManager.isAllowHorizontalCursorBeyondField(sharedPreferences, resources);
        this.allowBatchedCursorMove = SettingsManager.isAllowBatchedCursorMove(sharedPreferences, resources);
        this.horizontalScrollForAccentsChange = Math.round(SettingsManager.getScrollHorizontalDistanceForAccentsChange(sharedPreferences, resources) * fM5549d);
        this.maxAccentsChangeSpeedMultiplier = SettingsManager.getMaxScrollAccentSpeedMultiplier(sharedPreferences, resources);
        this.inLetterMaxSwipeToWordDistance = Math.round(SettingsManager.getInLetterMaxSwipeToWordDistance(sharedPreferences, resources) * fM5549d);
        this.showOnKeyPressMode = SettingsManager.getStringAsInt(sharedPreferences, resources, "pref_show_on_keypress_mode", R.string.config_default_show_on_keypress_mode);
        this.isUimEnabled = sharedPreferences.getBoolean("pref_uim_enabled", resources.getBoolean(R.bool.config_default_uim_enabled));
        this.flickCommitAnimationEnabled = sharedPreferences.getBoolean("pref_flick_commit_animation", resources.getBoolean(R.bool.config_default_flick_commit_animation));
        this.uimFocusMoveDelay = SettingsManager.getIntPrefWithResourceDefault(sharedPreferences, resources, "pref_uim_focus_move_delay", R.integer.config_uim_default_focus_move_delay);
        this.cangjieMode = SettingsManager.getCangjieMode(sharedPreferences, resources);
        this.isKoreanDoubleConsonantResolutionEnabled = SettingsManager.isKoreanDoubleConsonantResolutionEnabled(sharedPreferences, resources);
        this.koreanDoubleConsonantResolutionDelayMs = SettingsManager.getKoreanDoubleConsonantResolutionDelay(sharedPreferences, resources);
        this.shakeActionX = SettingsManager.getStringAsInt(sharedPreferences, resources, "shake_x_action", R.string.config_default_shake_action_x);
        this.shakeActionY = SettingsManager.getStringAsInt(sharedPreferences, resources, "shake_y_action", R.string.config_default_shake_action_y);
        this.shakeActionZ = SettingsManager.getStringAsInt(sharedPreferences, resources, "shake_z_action", R.string.config_default_shake_action_z);
        this.shakeActionFallback = SettingsManager.getStringAsInt(sharedPreferences, resources, "shake_fallback_action", R.string.config_default_shake_action_fallback);
        this.shakeOnAxisTriggerCount = SettingsManager.getIntPrefWithResourceDefault(sharedPreferences, resources, "shake_on_axis_trigger_count", R.integer.config_default_shake_trigger_count);
        this.shakeFallbackTriggerCount = SettingsManager.getIntPrefWithResourceDefault(sharedPreferences, resources, "shake_fallback_trigger_count", R.integer.config_default_shake_trigger_count);
        this.shakeAccelerationThreshold = SettingsManager.getIntPrefWithResourceDefault(sharedPreferences, resources, "shake_acceleration_threshold", R.integer.config_default_shake_acceleration_threshold);
        this.shakeSlopTime = SettingsManager.getIntPrefWithResourceDefault(sharedPreferences, resources, "shake_slop_time", R.integer.config_default_shake_slop_time);
        this.shakeResetTime = SettingsManager.getIntPrefWithResourceDefault(sharedPreferences, resources, "shake_reset_time", R.integer.config_default_shake_reset_time);
        this.unifyLearnedWords = SettingsManager.isUnifyLearnedWordsEnabled(sharedPreferences);
        this.overrideDeviceMetaState = sharedPreferences.getBoolean(context.getString(R.string.pref_override_device_meta_state_key), true);
        this.shiftDoubleTapLock = sharedPreferences.getBoolean(context.getString(R.string.pref_shift_double_tap_lock_key), true);
        this.altDoubleTapLock = sharedPreferences.getBoolean(context.getString(R.string.pref_alt_double_tap_lock_key), true);
        this.showPkbModifierStatusIcon = sharedPreferences.getBoolean(context.getString(R.string.pref_show_pkb_modifier_status_icon_key), true);
        this.voiceInputUseInputLanguage = SettingsManager.isVoiceInputUseInputLanguage(sharedPreferences);
        this.voiceInputPreferOffline = SettingsManager.isVoiceInputPreferOffline(sharedPreferences);
        this.voiceInputLanguageList = SettingsManager.getVoiceInputLanguageList(sharedPreferences);
        this.isEmojiDynamicSearchEnabled = SettingsManager.isEmojiDynamicSearchEnabled(sharedPreferences);
        this.altSymShortcutAction = sharedPreferences.getString("pref_alt_sym_shortcut_action", "disabled");
        this.multifunctionKeyAction = sharedPreferences.getString("pref_multifunction_key_action", "");
        this.isEmojiSearchReplaceText = SettingsManager.isEmojiSearchReplaceTextEnabled(sharedPreferences);
        this.keyboardHeightMode = sharedPreferences.getString("pref_keyboard_height_mode", "regular");
        this.isSlideboardEnabled = SettingsManager.isSlideboardActive(sharedPreferences, resources);
        this.slideboardKeyLongpressTimeout = SettingsManager.getIntPref(sharedPreferences, resources, "pref_slideboard_key_longpress_timeout", R.integer.config_default_slideboard_longpress_key_timeout);
        this.slideboardNumericLocation = SettingsManager.getStringAsInt(sharedPreferences, resources, "slideboard_numeric_location", R.string.config_default_numeric_location);
        this.slideboardQuickPhrasesLocation = SettingsManager.getStringAsInt(sharedPreferences, resources, "slideboard_quick_phrases_location", R.string.config_default_quick_phrases_location);
        this.quickPhrase1 = SettingsManager.getStringPref(sharedPreferences, resources, "quick_phrase_1", R.string.pref_quick_phrase_1_default);
        this.quickPhrase2 = SettingsManager.getStringPref(sharedPreferences, resources, "quick_phrase_2", R.string.pref_quick_phrase_2_default);
        this.quickPhrase3 = SettingsManager.getStringPref(sharedPreferences, resources, "quick_phrase_3", R.string.pref_quick_phrase_3_default);
        this.quickPhrase4 = SettingsManager.getStringPref(sharedPreferences, resources, "quick_phrase_4", R.string.pref_quick_phrase_4_default);
        this.quickPhrase5 = SettingsManager.getStringPref(sharedPreferences, resources, "quick_phrase_5", R.string.pref_quick_phrase_5_default);
        this.slideboardStillBoardsEnabled = sharedPreferences.getBoolean("pref_slideboard_still_boards", true);
        this.customSlideboardSymbols = SettingsManager.getStringListPref(sharedPreferences, "custom_slideboard_symbols");
    }

    public boolean shouldShowMoreKeys() {
        return this.editorCapabilities.hasAppSpecifiedCompletions;
    }

    public boolean shouldShowPredictionsInCandidateStrip() {
        return this.isPredictionsEnabled && (getKeyCodeHandler().shouldShowSuggestions() || getKeyCodeHandler().shouldShowChineseSuggestions() || getKeyCodeHandler().shouldShowJapaneseSuggestions());
    }

    private boolean isGestureInputEnabledForDevice(Context context) {
        if (LocaleUtils.isCurrentSubtypeChinese()) {
            return false;
        }
        if (DeviceProfile.current().isHardwareKeyboardActive()) {
            if (DeviceProfile.current().isVkbDevice()) {
                return this.isVkbGestureInputEnabled || this.isCkbGestureInputEnabled;
            }
            return this.isCkbGestureInputEnabled;
        }
        return this.isVkbGestureInputEnabled;
    }

    private boolean shouldShowLxxButton(Context context) {
        return (this.editorCapabilities.shouldShowSuggestions && (this.isAutoCorrectionEnabledPerUserSettings || this.isPredictionsEnabled)) || (isGestureInputEnabledForDevice(context) && this.editorCapabilities.shouldSupportGestureInput) || this.editorCapabilities.shouldShowJapaneseSuggestions || this.editorCapabilities.shouldShowChineseSuggestions;
    }

    public boolean isWordSeparator(int i) {
        return this.spacingAndPunctuation.isWordSeparator(i);
    }

    /**
     * ST-7: named for its target. NOTE the polarity: the sole caller,
     * {@code InputLogic.isWordSeparator}, negates this, so a CJK code point listed in the
     * per-script "not separators" table is treated as NOT a word character. That double negative
     * is the decompiled wiring and is preserved deliberately — do not "simplify" it without
     * checking CJK backspace and word-boundary behaviour first.
     */
    public boolean isNotWordSeparatorForLocale(int i, Locale locale) {
        return this.spacingAndPunctuation.isNotWordSeparatorForLocale(i, locale);
    }

    public boolean isWordConnector(int i) {
        return this.spacingAndPunctuation.isWordConnector(i);
    }

    public boolean isWordCodePoint(int i) {
        return Character.isLetterOrDigit(i) || isWordConnector(i) || 8 == Character.getType(i);
    }

    /** True for symbols that are usually PRECEDED by a space ({@code symbolsPrecededBySpace}). */
    public boolean isUsuallyPrecededBySpace(int i) {
        return this.spacingAndPunctuation.isPrecededBySpace(i);
    }

    /** True for the French-only preceded-by-space symbol set; false outside French locales. */
    public boolean isPrecededBySpaceFrench(int i) {
        return this.spacingAndPunctuation.isPrecededBySpaceFrench(i);
    }

    /**
     * True for symbols that are usually FOLLOWED by a space ({@code symbolsFollowedBySpace}).
     * ST-7: this was called {@code isSentenceSeparator}, which is a different predicate that
     * {@link SpacingAndPunctuation#isSentenceSeparator(int)} really does implement.
     */
    public boolean isUsuallyFollowedBySpace(int i) {
        return this.spacingAndPunctuation.isFollowedBySpace(i);
    }

    public boolean isApplicationSpecifiedCompletionsOn() {
        return this.editorCapabilities.shouldInsertSpaces;
    }

    public boolean hasMultipleEnabledInputMethodsOrSubtypes() {
        if (!this.showsLanguageSwitchKey) {
            return false;
        }
        RichInputMethodManager c0910yM5863a = RichInputMethodManager.getInstance();
        if (this.includesOtherImesInLanguageSwitch) {
            return c0910yM5863a.hasMultipleEnabledIMEsOrSubtypes(false);
        }
        return c0910yM5863a.hasMultipleEnabledSubtypesInThisIme(false);
    }

    public boolean shouldInsertSpacesAutomatically(EditorInfo editorInfo) {
        return this.editorCapabilities.isSameInputType(editorInfo);
    }

    /**
     * Was: the edited app targets an SDK older than Jelly Bean (16). minSdk here is 23 and
     * Android refuses to launch apps below targetSdk 23, so this is constant false; the
     * package-info fetch that computed it has been removed.
     */
    public boolean isBeamReaderPackage() {
        return false;
    }

    /**
     * Was: the edited app is broken by re-correction. The predicate behind it
     * ({@code AppWorkaroundsHelper.evaluateIsBrokenByRecorrection}) is a constant-false stub, so
     * this is constant false.
     */
    public boolean isSupportedAndroidApp() {
        return false;
    }

    // NOTE: the "show_suggestions_setting" -> "show_predictions" migration used to live here.
    // Writing prefs from inside this constructor re-entered SettingsManager.loadSettings through
    // the change listener; it now runs once in SettingsManager.migrateLegacyPreferences().
    private static boolean loadPredictionsEnabledSetting(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("show_predictions", resources.getBoolean(R.bool.config_default_show_predictions));
    }

    private static boolean loadOnKeyPredictionsSetting(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("on_key_predictions", resources.getBoolean(R.bool.config_default_on_key_predictions));
    }

    private static boolean loadEmojiPredictionsSetting(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("emoji_predictions", resources.getBoolean(R.bool.config_default_emoji_predictions));
    }

    private static boolean loadBigramPredictionsSetting(SharedPreferences sharedPreferences, Resources resources) {
        return sharedPreferences.getBoolean("next_word_prediction", resources.getBoolean(R.bool.config_default_next_word_prediction));
    }

    private static int loadAutoCorrectionMode(Context context, SharedPreferences sharedPreferences, Resources resources) {
        if (DeviceProfile.current().hasShiftedSymbolKeyboard()) {
            return sharedPreferences.getInt("auto_correction_mode_PKB", resources.getInteger(R.integer.config_default_PKB_auto_correction_mode));
        }
        return sharedPreferences.getInt("auto_correction_mode_VKB", resources.getInteger(R.integer.config_default_VKB_auto_correction_mode));
    }

    // NOTE: the "voice_mode" -> "pref_voice_input_key" migration moved to
    // SettingsManager.migrateLegacyPreferences() - see loadPredictionsEnabledSetting above.
    private static boolean loadVoiceInputKeySetting(SharedPreferences sharedPreferences, Resources resources) throws Resources.NotFoundException {
        return sharedPreferences.getBoolean("pref_voice_input_key", true);
    }

    public boolean isVkbGestureInputEnabledForLocale() {
        return this.isVkbGestureInputEnabled && !LocaleUtils.isCurrentSubtypeChinese();
    }

    public boolean isCkbGestureInputEnabledForLocale() {
        return this.isCkbGestureInputEnabled && !LocaleUtils.isCurrentSubtypeChinese();
    }

    public String dumpSettings() {
        StringBuilder sb = new StringBuilder("Current settings :");
        sb.append("\n   mSpacingAndPunctuations = ");
        sb.append("" + this.spacingAndPunctuation.dumpSettings());
        sb.append("\n   mDelayInMillisecondsToUpdateOldSuggestions = ");
        sb.append("" + this.delayToUpdateOldSuggestionsMs);
        sb.append("\n   mAutoCap = ");
        sb.append("" + this.isAutoCapsEnabled);
        sb.append("\n   mVibrateOn = ");
        sb.append("" + this.isVibrationEnabled);
        sb.append("\n   mSoundOn = ");
        sb.append("" + this.isSoundEnabled);
        sb.append("\n   mKeyPreviewPopupOn = ");
        sb.append("" + this.isKeyPreviewPopupEnabled);
        sb.append("\n   mShowsVoiceInputKey = ");
        sb.append("" + this.showsVoiceInputKey);
        sb.append("\n   mIncludesOtherImesInLanguageSwitchList = ");
        sb.append("" + this.includesOtherImesInLanguageSwitch);
        sb.append("\n   mShowsLanguageSwitchKey = ");
        sb.append("" + this.showsLanguageSwitchKey);
        sb.append("\n   mUseContactsDict = ");
        sb.append("" + this.useContactsDicts);
        sb.append("\n   mUsePersonalizedDicts = ");
        sb.append("" + this.usePersonalizedDicts);
        sb.append("\n   mUseDoubleSpacePeriod = ");
        sb.append("" + this.useDoubleSpacePeriod);
        sb.append("\n   mBlockPotentiallyOffensive = ");
        sb.append("" + this.blockPotentiallyOffensiveWords);
        sb.append("\n   mBigramPredictionEnabled = ");
        sb.append("" + this.isBigramPredictionEnabled);
        sb.append("\n   mVkbGestureInputEnabled = ");
        sb.append("" + this.isVkbGestureInputEnabled);
        sb.append("\n   mCkbGestureInputEnabled = ");
        sb.append("" + this.isCkbGestureInputEnabled);
        sb.append("\n   mSlidingKeyInputPreviewEnabled = ");
        sb.append("" + this.isSlidingKeyInputPreviewEnabled);
        sb.append("\n   mKeyLongpressTimeout = ");
        sb.append("" + this.keyLongpressTimeoutMs);
        sb.append("\n   mShiftKeyLongpressTimeout = ");
        sb.append("" + this.shiftKeyLongPressTimeout);
        sb.append("\n   mLocale = ");
        sb.append("" + this.locale);
        sb.append("\n   mInputAttributes = ");
        sb.append("" + this.editorCapabilities);
        sb.append("\n   mKeypressVibrationDuration = ");
        sb.append("" + this.keypressVibrationDuration);
        sb.append("\n   mKeypressSoundVolume = ");
        sb.append("" + this.keypressSoundVolume);
        sb.append("\n   mKeyPreviewPopupDismissDelay = ");
        sb.append("" + this.keyPreviewPopupDismissDelay);
        sb.append("\n   mAutoCorrectEnabled = ");
        sb.append("" + this.isAutoCorrectEnabled);
        sb.append("\n   mAutoCorrectionEnabledPerUserSettings = ");
        sb.append("" + this.isAutoCorrectionEnabledPerUserSettings);
        sb.append("\n   mPredictionsEnabled = ");
        sb.append("" + this.isPredictionsEnabled);
        sb.append("\n   mOnKeyPredictions = ");
        sb.append("" + this.isOnKeyPredictionsEnabled);
        sb.append("\n  mEmojiPredictions = ");
        sb.append("" + this.isEmojiPredictionsEnabled);
        sb.append("\n   mDisplayOrientation = ");
        sb.append("" + this.displayOrientation);
        sb.append("\n   mAdditionalFeaturesSettingValues = ");
        sb.append("" + Arrays.toString(this.additionalFeaturesSettings));
        sb.append("\n   mTextHighlightColorForAddToDictionaryIndicator = ");
        sb.append("" + this.textHighlightColorForAddToDictionary);
        sb.append("\n   mTextHighlightColorForMultiTapIndicator = ");
        sb.append("" + this.textHighlightColorForMultiTap);
        sb.append("\n   mIsInternal = ");
        sb.append("" + this.isInternal);
        sb.append("\n   mKeyPreviewShowUpDuration = ");
        sb.append("" + this.keyPreviewShowUpDuration);
        sb.append("\n   mKeyPreviewDismissDuration = ");
        sb.append("" + this.keyPreviewDismissDuration);
        sb.append("\n   mKeyPreviewShowUpStartScaleX = ");
        sb.append("" + this.keyPreviewShowUpStartScaleX);
        sb.append("\n   mKeyPreviewShowUpStartScaleY = ");
        sb.append("" + this.keyPreviewShowUpStartScaleY);
        sb.append("\n   mKeyPreviewDismissEndScaleX = ");
        sb.append("" + this.keyPreviewDismissEndScaleX);
        sb.append("\n   mKeyPreviewDismissEndScaleY = ");
        sb.append("" + this.keyPreviewDismissEndScaleY);
        sb.append("\n   mSlideboardEnabled = ");
        sb.append("" + this.isSlideboardEnabled);
        sb.append("\n   mSlideboardKeyLongpressTimeout = ");
        sb.append("" + this.slideboardKeyLongpressTimeout);
        sb.append("\n   mSlideboardStillBoardsEnabled = ");
        sb.append("" + this.slideboardStillBoardsEnabled);
        sb.append("\n   mSlideboardNumericLocation = ");
        sb.append("" + this.slideboardNumericLocation);
        sb.append("\n   mSlideboardQuickPhraseLocation = ");
        sb.append("" + this.slideboardQuickPhrasesLocation);
        sb.append("\n   mQuickPhrase1 = ");
        sb.append("" + this.quickPhrase1);
        sb.append("\n   mQuickPhrase2 = ");
        sb.append("" + this.quickPhrase2);
        sb.append("\n   mQuickPhrase3 = ");
        sb.append("" + this.quickPhrase3);
        sb.append("\n   mQuickPhrase4 = ");
        sb.append("" + this.quickPhrase4);
        sb.append("\n   mQuickPhrase5 = ");
        sb.append("" + this.quickPhrase5);
        if (this.isInternal) {
            sb.append("\n   mHorizontalSwipeTanTheta = ");
            sb.append("" + this.horizontalSwipeTanTheta);
            sb.append("\n   mVerticalSwipeTanTheta = ");
            sb.append("" + this.verticalSwipeTanTheta);
            sb.append("\n   mFastHorizontalSwipeMinX = ");
            sb.append("" + this.fastHorizontalSwipeMinX);
            sb.append("\n   mFastHorizontalSwipeMinVelocity = ");
            sb.append("" + this.fastHorizontalSwipeMinVelocity);
            sb.append("\n   mSlowHorizontalSwipeMinX = ");
            sb.append("" + this.slowHorizontalSwipeMinX);
            sb.append("\n   mSlowHorizontalSwipeMinVelocity = ");
            sb.append("" + this.slowHorizontalSwipeMinVelocity);
            sb.append("\n   mFlowModeVerticalSwipeMinY = ");
            sb.append("" + this.flowModeVerticalSwipeMinY);
            sb.append("\n   mFlowModeVerticalSwipeMinVelocity = ");
            sb.append("" + this.flowModeVerticalSwipeMinVelocity);
            sb.append("\n   mFastVerticalSwipeMinY = ");
            sb.append("" + this.fastVerticalSwipeMinY);
            sb.append("\n   mFastVerticalSwipeMinVelocity = ");
            sb.append("" + this.fastVerticalSwipeMinVelocity);
            sb.append("\n   mSlowVerticalSwipeMinY = ");
            sb.append("" + this.slowVerticalSwipeMinY);
            sb.append("\n   mSlowVerticalSwipeMinVelocity = ");
            sb.append("" + this.slowVerticalSwipeMinVelocity);
            sb.append("\n   mSwipeGestureTimeout = ");
            sb.append("" + this.swipeGestureTimeout);
            sb.append("\n   mCKBGestureSuppressionTimeout = ");
            sb.append("" + this.ckbGestureSuppressionTimeout);
            sb.append("\n   mVKBGestureSuppressionTimeout = ");
            sb.append("" + this.vkbGestureSuppressionTimeout);
            sb.append("\n   mAccentSelectionSuppressionTimeout = ");
            sb.append("" + this.accentSelectionSuppressionTimeout);
            sb.append("\n   mDoubleTapSuppressionTimeout = ");
            sb.append("" + this.doubleTapSuppressionTimeout);
            sb.append("\n   mHorizontalCursorTapRegionHeightScale = ");
            sb.append("" + this.horizontalCursorTapRegionHeightScale);
            sb.append("\n   mHorizontalCursorTapRegionWidthScale = ");
            sb.append("" + this.horizontalCursorTapRegionWidthScale);
            sb.append("\n   mVerticalCursorTapRegionHeightScale = ");
            sb.append("" + this.verticalCursorTapRegionHeightScale);
            sb.append("\n   mVerticalCursorTapRegionWidthScale = ");
            sb.append("" + this.verticalCursorTapRegionWidthScale);
            sb.append("\n   mHorizontalScrollForCursorMove = ");
            sb.append("" + this.horizontalScrollDistanceForCursorMove);
            sb.append("\n   mVerticalScrollForCursorMove = ");
            sb.append("" + this.verticalScrollDistanceForCursorMove);
            sb.append("\n   mSingleLineVerticalScrollForCursorMove = ");
            sb.append("" + this.singleLineVerticalScrollForCursorMove);
            sb.append("\n   mMaxCursorMoveMultiplier = ");
            sb.append("" + this.maxCursorMoveSpeedMultiplier);
            sb.append("\n   mVelocityForMaxCursorMoveSpeed = ");
            sb.append("" + this.velocityForMaxCursorMoveSpeed);
            sb.append("\n   mAllowHorizontalMoveBeyondField = ");
            sb.append("" + this.allowHorizontalCursorBeyondField);
            sb.append("\n   mAllowBatchedCursorMove = ");
            sb.append("" + this.allowBatchedCursorMove);
            sb.append("\n   mHorizontalScrollForAccentsChange = ");
            sb.append("" + this.horizontalScrollForAccentsChange);
            sb.append("\n   mMaxAccentsChangeMultiplier = ");
            sb.append("" + this.maxAccentsChangeSpeedMultiplier);
            sb.append("\n   mInLetterMaxSwipeToWordDistance = ");
            sb.append("" + this.inLetterMaxSwipeToWordDistance);
            sb.append("\n   mShowOnKeypressMode = ");
            sb.append("" + this.showOnKeyPressMode);
            // REMOVED: Tutorial debug dump - Tutorial system eliminated
            sb.append("\n   mConflictResolution = ");
            sb.append("" + this.isKoreanDoubleConsonantResolutionEnabled);
            sb.append("\n   mConflictResolutionDelay = ");
            sb.append("" + this.koreanDoubleConsonantResolutionDelayMs);
            sb.append("\n   mShakeActionX = ");
            sb.append("" + this.shakeActionX);
            sb.append("\n   mShakeActionY = ");
            sb.append("" + this.shakeActionY);
            sb.append("\n   mShakeActionZ = ");
            sb.append("" + this.shakeActionZ);
            sb.append("\n   mShakeActionFallback = ");
            sb.append("" + this.shakeActionFallback);
            sb.append("\n   mShakeOnAxisTriggerCount = ");
            sb.append("" + this.shakeOnAxisTriggerCount);
            sb.append("\n   mShakeFallbackTriggerCount = ");
            sb.append("" + this.shakeFallbackTriggerCount);
            sb.append("\n   mShakeAccelerationThreshold = ");
            sb.append("" + this.shakeAccelerationThreshold);
            sb.append("\n   mShakeSlopTime = ");
            sb.append("" + this.shakeSlopTime);
            sb.append("\n   mShakeResetTime = ");
            sb.append("" + this.shakeResetTime);
        }
        return sb.toString();
    }

    public EditorCapabilities getKeyCodeHandler() {
        return this.editorCapabilities;
    }
}
