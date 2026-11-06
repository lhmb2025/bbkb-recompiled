package dev.bbkb.ime.keyboard.auxbar.autofill;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.util.Log;
import android.util.Size;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.widget.inline.InlinePresentationSpec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.autofill.inline.UiVersions;
import androidx.autofill.inline.common.TextViewStyle;
import androidx.autofill.inline.common.ViewStyle;
import androidx.autofill.inline.v1.InlineSuggestionUi;

import dev.bbkb.ime.keyboard.auxbar.AuxBarManager;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardComponent;

import java.util.ArrayList;
import java.util.List;
import dev.bbkb.ime.BuildConfig;

/**
 * Manager for Android 11+ Inline Autofill suggestions.
 * Replaces the legacy Password Keeper integration with platform autofill API.
 * Implements UnifiedInputBoardComponent for integration with the unified input board system.
 */
@RequiresApi(api = Build.VERSION_CODES.R)
public final class InlineAutofillManager implements UnifiedInputBoardComponent {

    private static final String TAG = "InlineAutofillManager";
    private static final String DIAG = "INLINE_AUTOFILL_DEBUG";
    /** UIM component keycode; the one component UnifiedInputBoardManager's sweeps spare by name. */
    public static final int KEY_CODE_AUTOFILL = -37;
    private static final int MAX_SUGGESTION_COUNT = 3;
    private static final String PREF_DEBUG_AUTOFILL_LOGGING = "pref_debug_autofill_logging";
    /** User setting: offer inline autofill chips at all. Default on. */
    public static final String PREF_INLINE_AUTOFILL_ENABLED = "pref_inline_autofill_enabled";

    private static InlineAutofillManager instance = null;

    private final Context context;
    private List<InlineSuggestion> currentSuggestions;
    private boolean isShowing;
    private boolean isEnabled;
    private int suggestionHeight;
    private int suggestionWidth;

    // The current field, kept for the debug log only
    private EditorInfo currentEditorInfo;

    // Unified aux bar system; the sole display path
    private AuxBarManager auxBarManager;
    
    // Theme observer for updating styles
    private kotlin.jvm.functions.Function0<kotlin.Unit> themeObserver;

    /**
     * {@code pref_debug_autofill_logging}, cached. This used to be a SharedPreferences read per
     * log line. Refreshed on each input start.
     */
    private boolean debugLoggingEnabled;
    private boolean styleNeedsRefresh = true;
    private Bundle cachedStyleBundle = null;

    public static InlineAutofillManager getInstance(Context context) {
        if (instance == null) {
            instance = new InlineAutofillManager(context.getApplicationContext());
        }
        return instance;
    }

    public static void destroyInstance() {
        if (instance != null) {
            instance.destroy();
            instance = null;
        }
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    private InlineAutofillManager(Context context) {
        this.context = context;
        this.currentSuggestions = new ArrayList<>();
        this.isShowing = false;
        refreshDebugLoggingEnabled();
        refreshEnabledFromPrefs();
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[INIT] InlineAutofillManager instance created"
                + " | API=" + Build.VERSION.SDK_INT);
        }
        
        // Register for theme changes
        registerThemeObserver();
    }
    
    /**
     * Register observer for theme changes from KeyboardColorManager.
     * When theme changes, we invalidate the cached style so the next request uses new colors.
     */
    private void registerThemeObserver() {
        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            themeObserver = () -> {
                logDebug("Theme changed, invalidating autofill style cache");
                styleNeedsRefresh = true;
                cachedStyleBundle = null;
                return kotlin.Unit.INSTANCE;
            };
            KeyboardColorManager.INSTANCE.addObserver(themeObserver);
            logDebug("Registered theme observer");
        } else {
            logDebug("KeyboardColorManager not initialized, skipping observer registration");
        }
    }
    
    /**
     * Unregister the theme observer.
     */
    private void unregisterThemeObserver() {
        if (themeObserver != null && KeyboardColorManager.INSTANCE.isInitialized()) {
            KeyboardColorManager.INSTANCE.removeObserver(themeObserver);
            themeObserver = null;
            logDebug("Unregistered theme observer");
        }
    }
    
    /**
     * Create a themed style bundle for inline autofill suggestions.
     * Uses colors from KeyboardColorManager to match the keyboard theme.
     * Caches the bundle and only rebuilds when theme changes.
     */
    private Bundle createThemedStyleBundle() {
        // Return cached bundle if still valid
        if (cachedStyleBundle != null && !styleNeedsRefresh) {
            logDebug("Using cached style bundle");
            return cachedStyleBundle;
        }
        
        try {
            UiVersions.StylesBuilder stylesBuilder = UiVersions.newStylesBuilder();
            
            // Get colors from KeyboardColorManager
            int backgroundColor;
            int textColor;
            int hintColor;
            
            if (KeyboardColorManager.INSTANCE.isInitialized()) {
                backgroundColor = KeyboardColorManager.INSTANCE.getKeyColor();
                textColor = KeyboardColorManager.INSTANCE.getTextColor();
                hintColor = KeyboardColorManager.INSTANCE.getHintColor();
                logDebug("Using KeyboardColorManager colors - bg: " + Integer.toHexString(backgroundColor) +
                        ", text: " + Integer.toHexString(textColor) +
                        ", hint: " + Integer.toHexString(hintColor));
            } else {
                // Fallback to default colors if KeyboardColorManager not initialized
                backgroundColor = 0xFF2D2D2D; // Dark gray
                textColor = 0xFFFFFFFF;       // White
                hintColor = 0xFFAAAAAA;       // Light gray
                logDebug("KeyboardColorManager not initialized, using fallback colors");
            }
            
            // Create chip (container) style with background color
            ViewStyle chipStyle = new ViewStyle.Builder()
                    .setBackgroundColor(backgroundColor)
                    .build();
            
            // Create title text style with primary text color
            TextViewStyle titleStyle = new TextViewStyle.Builder()
                    .setTextColor(textColor)
                    .setTextSize(14.0f)
                    .build();
            
            // Create subtitle text style with hint color
            TextViewStyle subtitleStyle = new TextViewStyle.Builder()
                    .setTextColor(hintColor)
                    .setTextSize(12.0f)
                    .build();
            
            // Build the complete style
            InlineSuggestionUi.Style style = InlineSuggestionUi.newStyleBuilder()
                    .setChipStyle(chipStyle)
                    .setTitleStyle(titleStyle)
                    .setSubtitleStyle(subtitleStyle)
                    .build();
            
            cachedStyleBundle = stylesBuilder
                    .addStyle(style)
                    .build();
            styleNeedsRefresh = false;
            
            logDebug("Created new themed style bundle");
            return cachedStyleBundle;
            
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to create themed style bundle, using default", e);
            // Fallback to default unstyled bundle
            return UiVersions.newStylesBuilder()
                    .addStyle(InlineSuggestionUi.newStyleBuilder().build())
                    .build();
        }
    }
    
    /**
     * Check if debug autofill logging is enabled
     */
    private boolean isDebugLoggingEnabled() {
        return this.debugLoggingEnabled;
    }

    /** Re-read the debug-logging pref; called on input start, not per log line. */
    private void refreshDebugLoggingEnabled() {
        this.debugLoggingEnabled = BuildConfig.DEBUG && PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(PREF_DEBUG_AUTOFILL_LOGGING, false);
    }

    /**
     * Re-read the user setting. {@link #setEnabled} had no caller, so {@code isEnabled} was
     * permanently true and there was no way to turn inline autofill off; the flag is now backed by
     * {@code pref_inline_autofill_enabled} (default on) and re-read at the start of every input
     * session, so the Settings toggle applies without an IME restart.
     */
    private void refreshEnabledFromPrefs() {
        setEnabled(PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(PREF_INLINE_AUTOFILL_ENABLED, true));
    }
    
    /**
     * Log debug message if debug logging is enabled
     */
    private void logDebug(String message) {
        if (!BuildConfig.DEBUG || !this.debugLoggingEnabled) {
            return;
        }
        Log.d(TAG, message);
    }

    /**
     * Set the AuxBarManager to use for displaying autofill suggestions.
     */
    public void setAuxBarManager(AuxBarManager manager) {
        this.auxBarManager = manager;
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[INIT] setAuxBarManager called"
                + " | manager=" + (manager != null ? manager.getClass().getSimpleName() : "NULL"));
        }
    }

    /**
     * Creates an InlineSuggestionsRequest to be returned from onCreateInlineSuggestionsRequest().
     * This tells autofill services what kind of inline suggestions the keyboard can display.
     */
    @Nullable
    public InlineSuggestionsRequest createInlineSuggestionsRequest(int maxWidth, int maxHeight) {
        return createInlineSuggestionsRequest(maxWidth, maxHeight, null);
    }

    /**
     * @param editorInfo the field this request is being built for, straight from
     *     {@code InputMethodService.getCurrentInputEditorInfo()}. The framework delivers
     *     {@code onCreateInlineSuggestionsRequest} on its own schedule, with no ordering guarantee
     *     against {@code onStartInput}, so it is recorded here too (debug log only).
     */
    @Nullable
    public InlineSuggestionsRequest createInlineSuggestionsRequest(int maxWidth, int maxHeight,
            @Nullable EditorInfo editorInfo) {
        if (editorInfo != null) {
            this.currentEditorInfo = editorInfo;
        }
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[REQUEST] createInlineSuggestionsRequest()"
                + " | API=" + Build.VERSION.SDK_INT
                + " | isEnabled=" + isEnabled
                + " | maxWidth=" + maxWidth
                + " | maxHeight=" + maxHeight
                + " | auxBarManager=" + (auxBarManager != null ? "SET" : "NULL"));
        }

        if (!isEnabled) {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[REQUEST] BLOCKED: isEnabled=false (user disabled autofill)");
            return null;
        }

        this.suggestionWidth = maxWidth / MAX_SUGGESTION_COUNT;
        this.suggestionHeight = maxHeight;

        try {
            List<InlinePresentationSpec> specs = new ArrayList<>();
            
            Size minSize = new Size(100, 40);
            Size maxSize = new Size(suggestionWidth, suggestionHeight);

            // Create styled bundle using colors from KeyboardColorManager
            Bundle styleBundle = createThemedStyleBundle();

            InlinePresentationSpec spec = new InlinePresentationSpec.Builder(minSize, maxSize)
                    .setStyle(styleBundle)
                    .build();
            specs.add(spec);

            InlineSuggestionsRequest.Builder builder =
                    new InlineSuggestionsRequest.Builder(specs)
                            .setMaxSuggestionCount(MAX_SUGGESTION_COUNT);
            // Android 12+ lets the service attach a tooltip above the chips ("Suggestions from
            // <service>"). Same themed style as the chips so it does not arrive unstyled.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setInlineTooltipPresentationSpec(
                        new InlinePresentationSpec.Builder(minSize, maxSize)
                                .setStyle(styleBundle)
                                .build());
            }
            InlineSuggestionsRequest request = builder.build();
            if (BuildConfig.DEBUG) {
            Log.d(DIAG, "[REQUEST] createInlineSuggestionsRequest SUCCESS"
                    + " | maxCount=" + MAX_SUGGESTION_COUNT
                    + " | specCount=" + specs.size()
                    + " | suggestionWidth=" + suggestionWidth
                    + " | suggestionHeight=" + suggestionHeight);
            }
            return request;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(DIAG, "[REQUEST] EXCEPTION creating InlineSuggestionsRequest", e);
            return null;
        }
    }

    /**
     * Handles the InlineSuggestionsResponse from the autofill service.
     * Called from BlackBerryIME.onInlineSuggestionsResponse().
     */
    public boolean handleResponse(@NonNull InlineSuggestionsResponse response) {
        List<InlineSuggestion> suggestions = response.getInlineSuggestions();

        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[RESPONSE] handleResponse() called"
                + " | suggestionCount=" + suggestions.size()
                + " | auxBarManager=" + (auxBarManager != null ? "SET" : "NULL")
                + " | isShowing=" + isShowing
                + " | suggestionWidth=" + suggestionWidth
                + " | suggestionHeight=" + suggestionHeight
                + " | pkg=" + (currentEditorInfo != null ? currentEditorInfo.packageName : "null"));
        }

        currentSuggestions.clear();
        currentSuggestions.addAll(suggestions);

        if (suggestions.isEmpty()) {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[RESPONSE] Empty suggestion list - calling hide()");
            hide();
            return false;
        }

        if (auxBarManager != null) {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[RESPONSE] Routing to auxBarManager.showAutofillBar()");
            auxBarManager.showAutofillBar(suggestions, suggestionWidth, suggestionHeight);
            isShowing = true;
        } else {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[RESPONSE] FAILURE: auxBarManager=NULL - nothing to display suggestions on");
        }

        return true;
    }

    /**
     * Called when input starts on a new text field.
     */
    public void onInputStarted(EditorInfo editorInfo, boolean restarting) {
        refreshDebugLoggingEnabled();
        refreshEnabledFromPrefs();

        this.currentEditorInfo = editorInfo;

        if (editorInfo != null) {
            if (BuildConfig.DEBUG) {
            Log.d(DIAG, "[SESSION] onInputStarted()"
                    + " | restarting=" + restarting
                    + " | pkg=" + editorInfo.packageName
                    + " | inputType=0x" + Integer.toHexString(editorInfo.inputType)
                    + " | hintText=" + editorInfo.hintText
                    + " | fieldId=" + editorInfo.fieldId
                    + " | auxBarManager=" + (auxBarManager != null ? "SET" : "NULL")
                    + " | isEnabled=" + isEnabled);
            }
        } else {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[SESSION] onInputStarted() | restarting=" + restarting + " | editorInfo=NULL");
        }
        
        if (!restarting) {
            currentSuggestions.clear();
        }
    }

    /**
     * Called when input ends on the current text field.
     */
    public void onInputFinished() {
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[SESSION] onInputFinished()"
                + " | hadSuggestions=" + !currentSuggestions.isEmpty()
                + " | wasShowing=" + isShowing);
        }
        hide();
        currentSuggestions.clear();
        currentEditorInfo = null;
    }

    /**
     * Called when the user selects an autofill suggestion.
     * The actual autofill commit is handled by InlineContentView internally;
     * this method hides the bar and resets state.
     */
    public void onSelectionMade() {
        if (BuildConfig.DEBUG) Log.d(DIAG, "[INTERACTION] onSelectionMade() - hiding bar and clearing state");
        hide();
        currentSuggestions.clear();
        if (auxBarManager != null) {
            auxBarManager.hideAutofillBar(true);
        }
    }

    private void destroy() {
        hide();
        currentSuggestions.clear();
        unregisterThemeObserver();
        cachedStyleBundle = null;
        // The singleton outlives every input view; without this it pins the previous
        // AuxBarManager -> AuxBarView -> themed Context past each input-view recreation.
        auxBarManager = null;
        currentEditorInfo = null;
    }

    // UnifiedInputBoardComponent implementation

    @Override
    public void onRefresh() {
        // Display is owned by the aux bar; nothing to refresh here.
    }

    @Override
    public void hide() {
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[VISIBILITY] hide() called"
                + " | isShowing=" + isShowing
                + " | auxBarManager=" + (auxBarManager != null ? "SET" : "NULL"));
        }
        if (isShowing) {
            if (auxBarManager != null) {
                auxBarManager.hideAutofillBar(false);
            }
            isShowing = false;
        } else {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[VISIBILITY] hide() no-op: isShowing=false");
        }
    }

    /**
     * Re-display the suggestions from the last response.
     *
     * <p>Arrival is response-driven, but the chips can be dismissed (a board key, a field-type
     * switch) while the response is still valid, and this manager is registered as board
     * {@code -37}, so {@code UnifiedInputBoardManager.requestBoard(-37)} routes here. Without this
     * the chips could not be brought back without re-focusing the field.
     */
    @Override
    public void show() {
        if (isShowing || currentSuggestions.isEmpty() || !isEnabled()) {
            if (BuildConfig.DEBUG) {
                Log.d(DIAG, "[VISIBILITY] show() no-op"
                        + " | isShowing=" + isShowing
                        + " | cached=" + currentSuggestions.size()
                        + " | enabled=" + isEnabled());
            }
            return;
        }
        if (auxBarManager == null) {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[VISIBILITY] show() failed: auxBarManager=NULL");
            return;
        }
        auxBarManager.showAutofillBar(currentSuggestions, suggestionWidth, suggestionHeight);
        isShowing = true;
        if (BuildConfig.DEBUG) {
            Log.d(DIAG, "[VISIBILITY] show() re-shown from cache"
                    + " | cached=" + currentSuggestions.size());
        }
    }

    @Override
    public boolean isShowing() {
        return isShowing;
    }

    @Override
    public boolean isEnabled() {
        return isEnabled;
    }

    @Override
    public int getKeyCode() {
        return KEY_CODE_AUTOFILL;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) {
            hide();
        }
    }
}
