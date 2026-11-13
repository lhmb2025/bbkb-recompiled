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

/** Android 11+ inline autofill chips, shown on the aux bar and registered as UIM board {@code -37}. */
@RequiresApi(api = Build.VERSION_CODES.R)
public final class InlineAutofillManager implements UnifiedInputBoardComponent {

    private static final String TAG = "InlineAutofillManager";
    private static final String DIAG = "INLINE_AUTOFILL_DEBUG";
    /** UIM component keycode; the one component UnifiedInputBoardManager's sweeps spare by name. */
    public static final int KEY_CODE_AUTOFILL = -37;
    private static final int MAX_SUGGESTION_COUNT = 3;
    private static final String PREF_DEBUG_AUTOFILL_LOGGING = "pref_debug_autofill_logging";
    /** Default on. */
    public static final String PREF_INLINE_AUTOFILL_ENABLED = "pref_inline_autofill_enabled";

    private static InlineAutofillManager instance = null;

    private final Context context;
    private List<InlineSuggestion> currentSuggestions;
    private boolean isShowing;
    private boolean isEnabled;
    private int suggestionHeight;
    private int suggestionWidth;

    // For the debug log only.
    private EditorInfo currentEditorInfo;

    private AuxBarManager auxBarManager;

    private kotlin.jvm.functions.Function0<kotlin.Unit> themeObserver;

    /** {@code pref_debug_autofill_logging}, cached and refreshed on each input start. */
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

        registerThemeObserver();
    }

    /** A theme change invalidates the cached style so the next request uses the new colors. */
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

    private void unregisterThemeObserver() {
        if (themeObserver != null && KeyboardColorManager.INSTANCE.isInitialized()) {
            KeyboardColorManager.INSTANCE.removeObserver(themeObserver);
            themeObserver = null;
            logDebug("Unregistered theme observer");
        }
    }

    /** The chip style from the keyboard palette, cached until the theme changes. */
    private Bundle createThemedStyleBundle() {
        if (cachedStyleBundle != null && !styleNeedsRefresh) {
            logDebug("Using cached style bundle");
            return cachedStyleBundle;
        }
        
        try {
            UiVersions.StylesBuilder stylesBuilder = UiVersions.newStylesBuilder();

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
                backgroundColor = 0xFF2D2D2D;
                textColor = 0xFFFFFFFF;
                hintColor = 0xFFAAAAAA;
                logDebug("KeyboardColorManager not initialized, using fallback colors");
            }

            ViewStyle chipStyle = new ViewStyle.Builder()
                    .setBackgroundColor(backgroundColor)
                    .build();

            TextViewStyle titleStyle = new TextViewStyle.Builder()
                    .setTextColor(textColor)
                    .setTextSize(14.0f)
                    .build();

            TextViewStyle subtitleStyle = new TextViewStyle.Builder()
                    .setTextColor(hintColor)
                    .setTextSize(12.0f)
                    .build();

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
            return UiVersions.newStylesBuilder()
                    .addStyle(InlineSuggestionUi.newStyleBuilder().build())
                    .build();
        }
    }

    private boolean isDebugLoggingEnabled() {
        return this.debugLoggingEnabled;
    }

    /** Called on input start, not per log line. */
    private void refreshDebugLoggingEnabled() {
        this.debugLoggingEnabled = BuildConfig.DEBUG && PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(PREF_DEBUG_AUTOFILL_LOGGING, false);
    }

    /** Re-read at every input start, so the setting applies without an IME restart. */
    private void refreshEnabledFromPrefs() {
        setEnabled(PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(PREF_INLINE_AUTOFILL_ENABLED, true));
    }

    private void logDebug(String message) {
        if (!BuildConfig.DEBUG || !this.debugLoggingEnabled) {
            return;
        }
        Log.d(TAG, message);
    }

    public void setAuxBarManager(AuxBarManager manager) {
        this.auxBarManager = manager;
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[INIT] setAuxBarManager called"
                + " | manager=" + (manager != null ? manager.getClass().getSimpleName() : "NULL"));
        }
    }

    /** What to return from onCreateInlineSuggestionsRequest(): the chips the keyboard can show. */
    @Nullable
    public InlineSuggestionsRequest createInlineSuggestionsRequest(int maxWidth, int maxHeight) {
        return createInlineSuggestionsRequest(maxWidth, maxHeight, null);
    }

    /**
     * @param editorInfo the current field, recorded for the debug log (the request is not ordered
     *     against {@code onStartInput})
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

            Bundle styleBundle = createThemedStyleBundle();

            InlinePresentationSpec spec = new InlinePresentationSpec.Builder(minSize, maxSize)
                    .setStyle(styleBundle)
                    .build();
            specs.add(spec);

            InlineSuggestionsRequest.Builder builder =
                    new InlineSuggestionsRequest.Builder(specs)
                            .setMaxSuggestionCount(MAX_SUGGESTION_COUNT);
            // Android 12+ tooltip above the chips: same style so it does not arrive unstyled.
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

    /** InlineContentView commits the fill itself; this only hides the bar and resets state. */
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
        // The singleton outlives every input view; drop the bar so it does not pin the old one.
        auxBarManager = null;
        currentEditorInfo = null;
    }

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
     * Re-show the last response's chips: they can be dismissed while still valid, and
     * {@code requestBoard(-37)} routes here.
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
