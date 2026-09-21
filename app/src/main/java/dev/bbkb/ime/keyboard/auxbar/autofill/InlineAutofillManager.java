package dev.bbkb.ime.keyboard.auxbar.autofill;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.text.InputType;
import android.util.Log;
import android.util.Size;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.widget.inline.InlinePresentationSpec;
import android.widget.Toast;

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
    private static final String PREF_DEBUG_AUTOFILL = "pref_debug_force_autofill_bar";
    private static final String PREF_DEBUG_AUTOFILL_LOGGING = "pref_debug_autofill_logging";
    /** User setting: offer inline autofill chips at all. Default on. */
    public static final String PREF_INLINE_AUTOFILL_ENABLED = "pref_inline_autofill_enabled";

    /**
     * Enum representing detected autofill field types for better context awareness.
     */
    public enum AutofillFieldType {
        UNKNOWN,
        PASSWORD,
        USERNAME,
        EMAIL,
        PHONE,
        CREDIT_CARD,
        ADDRESS,
        OTP,           // One-Time Password / verification code
        NAME,
        DATE
    }

    private static InlineAutofillManager instance = null;

    private final Context context;
    private List<InlineSuggestion> currentSuggestions;
    private boolean isShowing;
    private boolean isEnabled;
    private int suggestionHeight;
    private int suggestionWidth;

    // Current field type detection
    private AutofillFieldType currentFieldType = AutofillFieldType.UNKNOWN;
    private EditorInfo currentEditorInfo;

    // Unified aux bar system; the sole display path
    private AuxBarManager auxBarManager;
    
    // Theme observer for updating styles
    private kotlin.jvm.functions.Function0<kotlin.Unit> themeObserver;

    /**
     * {@code pref_debug_autofill_logging}, cached. This used to be a SharedPreferences read per
     * log line - createInlineSuggestionsRequest alone emits eight per field focus and
     * onInputStarted six per editor change. Refreshed on each input start.
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
        logDebug("InlineAutofillManager constructor called");
        logDebug("  - isSupported: " + isSupported());
        logDebug("  - Build.VERSION.SDK_INT: " + Build.VERSION.SDK_INT);
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[INIT] InlineAutofillManager instance created"
                + " | API=" + Build.VERSION.SDK_INT
                + " | isSupported=" + isSupported());
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
     * Force refresh of the style bundle on next request.
     * Call this when theme changes and you want the autofill bar to update.
     */
    public void invalidateStyle() {
        styleNeedsRefresh = true;
        cachedStyleBundle = null;
        logDebug("Style invalidated, will refresh on next request");
    }
    
    /**
     * Check if style needs refresh (theme changed since last request).
     */
    public boolean needsStyleRefresh() {
        return styleNeedsRefresh;
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
     * Check if force show autofill bar is enabled (debug mode)
     */
    public boolean isForceShowEnabled() {
        return PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(PREF_DEBUG_AUTOFILL, false);
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
     * Log always (important state changes)
     */
    private void logAlways(String message) {
        if (BuildConfig.DEBUG) Log.d(TAG, message);
    }

    /**
     * Set the AuxBarManager to use for displaying autofill suggestions.
     */
    public void setAuxBarManager(AuxBarManager manager) {
        this.auxBarManager = manager;
        logDebug("setAuxBarManager: " + (manager != null));
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
     *     against {@code onStartInput}, so the field type is detected here rather than relying on
     *     whatever {@link #onInputStarted} last recorded.
     */
    @Nullable
    public InlineSuggestionsRequest createInlineSuggestionsRequest(int maxWidth, int maxHeight,
            @Nullable EditorInfo editorInfo) {
        if (editorInfo != null) {
            this.currentEditorInfo = editorInfo;
            this.currentFieldType = detectFieldType(editorInfo);
        }
        logDebug("createInlineSuggestionsRequest() called");
        logDebug("  - maxWidth: " + maxWidth + ", maxHeight: " + maxHeight);
        logDebug("  - isSupported: " + isSupported() + ", isEnabled: " + isEnabled);
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[REQUEST] createInlineSuggestionsRequest()"
                + " | API=" + Build.VERSION.SDK_INT
                + " | isSupported=" + isSupported()
                + " | isEnabled=" + isEnabled
                + " | maxWidth=" + maxWidth
                + " | maxHeight=" + maxHeight
                + " | auxBarManager=" + (auxBarManager != null ? "SET" : "NULL"));
        }
        
        if (!isSupported()) {
            logAlways("createInlineSuggestionsRequest: NOT SUPPORTED (API " + Build.VERSION.SDK_INT + " < 30)");
            if (BuildConfig.DEBUG) Log.d(DIAG, "[REQUEST] BLOCKED: API level " + Build.VERSION.SDK_INT + " < 30 (Android 11)");
            return null;
        }
        
        if (!isEnabled) {
            logAlways("createInlineSuggestionsRequest: DISABLED by user");
            if (BuildConfig.DEBUG) Log.d(DIAG, "[REQUEST] BLOCKED: isEnabled=false (user disabled autofill)");
            return null;
        }

        this.suggestionWidth = maxWidth / MAX_SUGGESTION_COUNT;
        this.suggestionHeight = maxHeight;
        logDebug("  - suggestionWidth: " + suggestionWidth + ", suggestionHeight: " + suggestionHeight);

        try {
            List<InlinePresentationSpec> specs = new ArrayList<>();
            
            Size minSize = new Size(100, 40);
            Size maxSize = new Size(suggestionWidth, suggestionHeight);
            logDebug("  - minSize: " + minSize + ", maxSize: " + maxSize);

            // Create styled bundle using colors from KeyboardColorManager
            Bundle styleBundle = createThemedStyleBundle();
            logDebug("  - styleBundle created successfully");

            InlinePresentationSpec spec = new InlinePresentationSpec.Builder(minSize, maxSize)
                    .setStyle(styleBundle)
                    .build();
            specs.add(spec);
            logDebug("  - InlinePresentationSpec created");

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
            logAlways("createInlineSuggestionsRequest: SUCCESS, maxCount=" + MAX_SUGGESTION_COUNT);
            if (BuildConfig.DEBUG) {
            Log.d(DIAG, "[REQUEST] createInlineSuggestionsRequest SUCCESS"
                    + " | maxCount=" + MAX_SUGGESTION_COUNT
                    + " | specCount=" + specs.size()
                    + " | suggestionWidth=" + suggestionWidth
                    + " | suggestionHeight=" + suggestionHeight);
            }
            return request;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to create InlineSuggestionsRequest", e);
            if (BuildConfig.DEBUG) Log.e(DIAG, "[REQUEST] EXCEPTION creating InlineSuggestionsRequest: " + e.getMessage());
            return null;
        }
    }

    /**
     * Handles the InlineSuggestionsResponse from the autofill service.
     * Called from BlackBerryIME.onInlineSuggestionsResponse().
     */
    public boolean handleResponse(@NonNull InlineSuggestionsResponse response) {
        List<InlineSuggestion> suggestions = response.getInlineSuggestions();
        
        logAlways("handleResponse: Received " + suggestions.size() + " inline suggestions");
        logDebug("  - isShowing: " + isShowing);
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[RESPONSE] handleResponse() called"
                + " | suggestionCount=" + suggestions.size()
                + " | auxBarManager=" + (auxBarManager != null ? "SET" : "NULL")
                + " | isShowing=" + isShowing
                + " | suggestionWidth=" + suggestionWidth
                + " | suggestionHeight=" + suggestionHeight
                + " | fieldType=" + currentFieldType
                + " | pkg=" + (currentEditorInfo != null ? currentEditorInfo.packageName : "null"));
        }

        currentSuggestions.clear();
        currentSuggestions.addAll(suggestions);

        if (suggestions.isEmpty()) {
            logDebug("  - No suggestions, hiding bar");
            if (BuildConfig.DEBUG) Log.d(DIAG, "[RESPONSE] Empty suggestion list - calling hide()");
            hide();
            return false;
        }

        if (auxBarManager != null) {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[RESPONSE] Routing to auxBarManager.showAutofillBar()");
            auxBarManager.showAutofillBar(suggestions, suggestionWidth, suggestionHeight);
            isShowing = true;
            logAlways("handleResponse: showing " + suggestions.size() + " suggestions via AuxBarManager");
        } else {
            logAlways("handleResponse: WARNING - auxBarManager is null, nothing to display suggestions on!");
            if (BuildConfig.DEBUG) Log.d(DIAG, "[RESPONSE] FAILURE: auxBarManager=NULL - nothing to display suggestions on");
        }

        return true;
    }

    /**
     * Called when input starts on a new text field.
     * Performs enhanced field type detection for better autofill context.
     */
    public void onInputStarted(EditorInfo editorInfo, boolean restarting) {
        refreshDebugLoggingEnabled();
        refreshEnabledFromPrefs();
        logDebug("onInputStarted() called");
        logDebug("  - restarting: " + restarting);
        
        this.currentEditorInfo = editorInfo;
        this.currentFieldType = detectFieldType(editorInfo);
        
        if (editorInfo != null) {
            logDebug("  - inputType: 0x" + Integer.toHexString(editorInfo.inputType));
            logDebug("  - packageName: " + editorInfo.packageName);
            logDebug("  - fieldId: " + editorInfo.fieldId);
            logDebug("  - hintText: " + editorInfo.hintText);
            logDebug("  - detectedFieldType: " + currentFieldType);
            
            logAlways("onInputStarted: fieldType=" + currentFieldType + 
                    ", inputType=0x" + Integer.toHexString(editorInfo.inputType) +
                    ", package=" + editorInfo.packageName);
            if (BuildConfig.DEBUG) {
            Log.d(DIAG, "[SESSION] onInputStarted()"
                    + " | restarting=" + restarting
                    + " | pkg=" + editorInfo.packageName
                    + " | fieldType=" + currentFieldType
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
        
        // If debug force show is enabled, show a test message
        if (isForceShowEnabled()) {
            logAlways("DEBUG: Force show autofill bar is ENABLED");
            Toast.makeText(context, "Autofill Debug: " + currentFieldType + " field on " + 
                    (editorInfo != null ? editorInfo.packageName : "unknown"), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Detect the field type based on EditorInfo for enhanced autofill context.
     * Checks inputType, hint text, and autofill hints (Android 8+).
     */
    private AutofillFieldType detectFieldType(EditorInfo editorInfo) {
        if (editorInfo == null) {
            return AutofillFieldType.UNKNOWN;
        }
        
        int inputType = editorInfo.inputType;
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        
        // Check for password fields
        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
                return AutofillFieldType.PASSWORD;
            }
            
            // Check for email
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
                return AutofillFieldType.EMAIL;
            }
            
            // Check for person name
            if (variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME) {
                return AutofillFieldType.NAME;
            }
            
            // Check for postal address
            if (variation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS) {
                return AutofillFieldType.ADDRESS;
            }
        }
        
        // Check for phone number
        if (inputClass == InputType.TYPE_CLASS_PHONE) {
            return AutofillFieldType.PHONE;
        }
        
        // Check for date/time
        if (inputClass == InputType.TYPE_CLASS_DATETIME) {
            return AutofillFieldType.DATE;
        }
        
        // Check hint text for additional clues
        CharSequence hintText = editorInfo.hintText;
        if (hintText != null) {
            String hint = hintText.toString().toLowerCase();
            
            if (hint.contains("password") || hint.contains("pin")) {
                return AutofillFieldType.PASSWORD;
            }
            if (hint.contains("username") || hint.contains("user name") || hint.contains("login")) {
                return AutofillFieldType.USERNAME;
            }
            if (hint.contains("email") || hint.contains("e-mail")) {
                return AutofillFieldType.EMAIL;
            }
            if (hint.contains("phone") || hint.contains("mobile") || hint.contains("tel")) {
                return AutofillFieldType.PHONE;
            }
            if (hint.contains("card") || hint.contains("credit") || hint.contains("cvv") || 
                hint.contains("cvc") || hint.contains("expir")) {
                return AutofillFieldType.CREDIT_CARD;
            }
            if (hint.contains("address") || hint.contains("street") || hint.contains("city") ||
                hint.contains("zip") || hint.contains("postal")) {
                return AutofillFieldType.ADDRESS;
            }
            if (hint.contains("otp") || hint.contains("code") || hint.contains("verification") ||
                hint.contains("one-time") || hint.contains("one time") || hint.contains("sms")) {
                return AutofillFieldType.OTP;
            }
            if (hint.contains("name") || hint.contains("first") || hint.contains("last")) {
                return AutofillFieldType.NAME;
            }
        }
        
        // Check contentMimeTypes for additional hints (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            String[] contentMimeTypes = editorInfo.contentMimeTypes;
            if (contentMimeTypes != null) {
                for (String mimeType : contentMimeTypes) {
                    // Some apps set mime types that hint at field purpose
                    if (mimeType != null && mimeType.toLowerCase().contains("otp")) {
                        return AutofillFieldType.OTP;
                    }
                }
            }
        }
        
        return AutofillFieldType.UNKNOWN;
    }
    
    /**
     * Get the detected field type for the current input field.
     */
    public AutofillFieldType getCurrentFieldType() {
        return currentFieldType;
    }
    
    /**
     * Check if the current field is an OTP/verification code field.
     * Useful for special handling of SMS OTP suggestions.
     */
    public boolean isOtpField() {
        return currentFieldType == AutofillFieldType.OTP;
    }
    
    /**
     * Check if the current field is a credential field (password or username).
     */
    public boolean isCredentialField() {
        return currentFieldType == AutofillFieldType.PASSWORD || 
               currentFieldType == AutofillFieldType.USERNAME;
    }

    /**
     * Called when input ends on the current text field.
     */
    public void onInputFinished() {
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[SESSION] onInputFinished()"
                + " | hadSuggestions=" + !currentSuggestions.isEmpty()
                + " | wasShowing=" + isShowing
                + " | fieldType=" + currentFieldType);
        }
        hide();
        currentSuggestions.clear();
        currentFieldType = AutofillFieldType.UNKNOWN;
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
        logAlways("Autofill suggestion selected - bar hidden");
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
                if (BuildConfig.DEBUG) Log.d(DIAG, "[VISIBILITY] hide() via auxBarManager");
            }
            isShowing = false;
            logAlways("Autofill bar hidden");
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
        logAlways("Autofill bar re-shown from cache (" + currentSuggestions.size() + " suggestions)");
    }

    @Override
    public boolean isShowing() {
        return isShowing;
    }

    @Override
    public boolean isEnabled() {
        return isEnabled && isSupported();
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

    public boolean hasSuggestions() {
        return !currentSuggestions.isEmpty();
    }

    public int getSuggestionCount() {
        return currentSuggestions.size();
    }
}
