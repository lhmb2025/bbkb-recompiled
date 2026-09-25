package dev.bbkb.ime.core.device.interceptor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.builder.HardwareKeyCaptureBus;
import dev.bbkb.ime.core.device.config.model.KeyRole;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver;
import dev.bbkb.ime.core.keyevent.ResolvedKey;
import dev.bbkb.ime.BuildConfig;

/**
 * AccessibilityService that intercepts raw hardware key events before the system
 * transforms them into synthetic events.
 * 
 * <p>This service is specifically designed for third-party PKB devices (Minimal Phone,
 * Titan Pocket, etc.) where the system swallows special key events (Emoji, SYM, MIC)
 * and replaces them with synthetic ALT_LEFT events before they reach the IME.
 * 
 * <p>This service can be disabled via settings without affecting other keyboard functionality.
 * 
 * <h3>Architecture (Optimized for Low Latency):</h3>
 * <ul>
 *   <li>Intercepts key events via {@link #onKeyEvent(KeyEvent)}</li>
 *   <li>Directly invokes static callback registered by BlackBerryIME (no broadcast overhead)</li>
 * </ul>
 * 
 * @see KeyInterceptorManager
 */
public class KeyInterceptorService extends AccessibilityService {

    private static final String TAG = "KeyInterceptorService";

    /** Raw hardware scancodes for special keys on Minimal Phone / Titan Pocket */
    private static final int SCANCODE_SYM = 249;      // 0xF9
    private static final int SCANCODE_EMOJI = 250;    // 0xFA
    private static final int SCANCODE_MIC = 251;      // 0xFB
    
    /**
     * Fallback scancodes (from the .kl file if it were working).
     *
     * <p>The emoji and mic values are the two pseudo-keycodes {@link ResolvedKey} names, which is
     * the one place they are defined: the Minimal Phone's ROM hands 666 / 667 through as both the
     * scancode and the key code, so the same pair of numbers identifies those keys everywhere in
     * the key path. Phase 1g folded the duplicate literals that used to stand here onto it.
     */
    private static final int SCANCODE_SYM_KL = 63;
    private static final int SCANCODE_EMOJI_KL = ResolvedKey.PSEUDO_KEYCODE_EMOJI;
    private static final int SCANCODE_MIC_KL = ResolvedKey.PSEUDO_KEYCODE_VOICE;

    /** Track if service is currently enabled and connected */
    private static volatile boolean sIsServiceConnected = false;
    
    /** Direct callback for special key handling (minimal latency) */
    private static volatile KeyEventCallback sCallback = null;
    
    /** Callback for all key event preprocessing */
    private static volatile AllKeysCallback sAllKeysCallback = null;
    
    /** Whether to preprocess all key events (not just special keys) */
    private static volatile boolean sPreprocessAllKeys = false;
    
    /** Whether to use the unified key mapping pipeline (Phase 2 feature flag) */
    private static volatile boolean sUseUnifiedKeyMapping = false;

    /** {@link #sConsumedBoardKeyScanCode} when no board-key DOWN is outstanding. */
    private static final int NO_CONSUMED_SCANCODE = Integer.MIN_VALUE;

    /**
     * Scancode of the board key whose ACTION_DOWN this service consumed, so the matching
     * ACTION_UP can be consumed too. Board keys are pressed one at a time, so one slot is
     * enough. Without this the system would see the release of a press it never saw — on the
     * MP01 that release is a {@code KEYCODE_ALT_RIGHT} up, i.e. exactly the event that leaves
     * the framework's Alt state stuck.
     */
    private static volatile int sConsumedBoardKeyScanCode = NO_CONSUMED_SCANCODE;

    /**
     * Callback interface for direct key event handling (low latency).
     */
    public interface KeyEventCallback {
        /**
         * Called when a special key is intercepted (legacy path, no mapping data).
         * @param keyType The type of special key (SYM, EMOJI, MIC)
         * @param metaState The meta state at time of key press
         * @return true if the key was handled
         */
        boolean onSpecialKeyPressed(SpecialKeyType keyType, int metaState);
        
        /**
         * Called when a special key is intercepted via unified key mapping (Phase 2+).
         * Carries the resolved ScancodeMapping for alt char and board ID resolution.
         * Default implementation delegates to the legacy method.
         * @param keyType The type of special key (SYM, EMOJI, MIC)
         * @param metaState The meta state at time of key press
         * @param mapping The resolved scancode mapping from XML config
         * @return true if the key was handled
         */
        default boolean onSpecialKeyPressed(SpecialKeyType keyType, int metaState, ScancodeMapping mapping) {
            return onSpecialKeyPressed(keyType, metaState);
        }
    }
    
    /**
     * Types of special keys that can be intercepted.
     */
    public enum SpecialKeyType {
        SYM,
        EMOJI,
        MIC
    }
    
    /**
     * Callback interface for all key event preprocessing.
     */
    public interface AllKeysCallback {
        /**
         * Called when any key is pressed (when preprocess mode is enabled).
         * @param event The key event
         * @return true if the key was handled and should be consumed
         */
        boolean onKeyEvent(KeyEvent event);
    }
    
    /**
     * Register a callback for direct special key handling.
     * This bypasses the broadcast mechanism for minimal latency.
     */
    public static void setCallback(KeyEventCallback callback) {
        sCallback = callback;
        // A press consumed by the outgoing callback must not make the new one (or none at all)
        // swallow an unrelated key-up.
        sConsumedBoardKeyScanCode = NO_CONSUMED_SCANCODE;
    }
    
    /**
     * Register a callback for all key event preprocessing.
     * This is used when preprocess-all-keys mode is enabled.
     */
    public static void setAllKeysCallback(AllKeysCallback callback) {
        sAllKeysCallback = callback;
    }
    
    /**
     * Enable or disable preprocessing of all key events.
     */
    public static void setPreprocessAllKeysEnabled(boolean enabled) {
        sPreprocessAllKeys = enabled;
    }
    
    /**
     * Check if preprocess-all-keys mode is currently enabled.
     */
    public static boolean isPreprocessAllKeysEnabled() {
        return sPreprocessAllKeys;
    }
    
    /**
     * Enable or disable unified key mapping pipeline.
     */
    public static void setUnifiedKeyMappingEnabled(boolean enabled) {
        sUseUnifiedKeyMapping = enabled;
    }
    
    /**
     * Check if unified key mapping pipeline is enabled.
     */
    public static boolean isUnifiedKeyMappingEnabled() {
        return sUseUnifiedKeyMapping;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
        // Configure the service to listen to key events
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.eventTypes = 0; // Only key events, not accessibility events
            setServiceInfo(info);
        }
        
        // sIsServiceConnected is the only fact anyone reads. KeyInterceptorManager used to
        // also stash `this` in a static field, but that was a write-only static reference to
        // an AccessibilityService (i.e. a Context) whose getter had no callers repo-wide.
        sIsServiceConnected = true;
    }

    @Override
    public void onDestroy() {
        // Only the connected flag belongs to THIS service's lifetime. The callbacks and the two
        // mode flags describe the IME's registration, and the IME clears them itself from
        // BlackBerryIME.releaseResources() (HardwareKeyBridge.unregisterInterceptorCallbacks).
        // Nulling them here meant that toggling the accessibility service off and on in system
        // settings — which destroys and recreates this service while the IME keeps running —
        // left a live IME with no callbacks until its next loadSettings(), i.e. special keys
        // silently stopped working. onServiceConnected() therefore has nothing to re-register:
        // whatever the IME installed is still installed.
        sIsServiceConnected = false;
        sConsumedBoardKeyScanCode = NO_CONSUMED_SCANCODE;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We don't process accessibility events, only key events
    }

    @Override
    public void onInterrupt() {
        // Service interrupted
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int scanCode = event.getScanCode();
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        int metaState = event.getMetaState();

        // === DEVICE-PROFILE CAPTURE — MUST RUN BEFORE EVERYTHING ELSE ===
        //
        // This service is the only place a board key is visible: the classification below
        // consumes Sym, Emoji and Mic, so they reach neither the IME nor the foreground activity.
        // Those are exactly the keys the profile builder exists to identify, so while its capture
        // screen is open the raw event goes there first and is consumed — a Sym press must record
        // its scancode, not open the symbol board over Settings. Costs one volatile read
        // otherwise. See HardwareKeyCaptureBus.
        if (HardwareKeyCaptureBus.isCapturing()
                && HardwareKeyCaptureBus.offer(scanCode, keyCode, event.getDeviceId(),
                        event.getRepeatCount(), action == KeyEvent.ACTION_DOWN,
                        event.getEventTime())) {
            return true;
        }

        // === BOARD KEY CLASSIFICATION — MUST RUN BEFORE THE TWO ALT BLOCKS BELOW ===
        //
        // The Minimal Phone MP01's ROM maps the Sym key's scancode 249 to KEY_RIGHTALT, so a Sym
        // press arrives as scanCode=249 + KEYCODE_ALT_RIGHT. While the Alt-keycode block ran
        // first, every Sym press was consumed and forwarded as a sticky Alt and the symbol board
        // never opened (beta triage #10, 2026-09). What a key IS is decided by the device config
        // (or, for a device with no config, the legacy scancode table) — not by whichever keycode
        // the ROM's .kl file happened to attach to it — so the decision is made here, first.
        ScancodeMapping mapping = sUseUnifiedKeyMapping
                ? ScancodeMappingResolver.getInstance().resolve(scanCode, keyCode)
                : null;
        if (mapping != null && mapping.role == KeyRole.MULTIFUNCTION) {
            // Multifunction keys are never consumed at the accessibility level: their
            // user-configured action (possibly a held Ctrl modifier) is dispatched by
            // the IME key pipeline. Skip the legacy scancode table too.
            return false;
        }
        SpecialKeyType boardKeyType = classifyBoardKey(mapping, scanCode, keyCode, event.getDeviceId());
        if (boardKeyType != null) {
            return handleBoardKey(boardKeyType, mapping, action, scanCode, metaState);
        }

        // ALT BLEED-THROUGH FIX: Consume real hardware Alt DOWN/UP events so the Android
        // system never tracks Alt internally. This prevents the bug where consuming a
        // special key (e.g. MIC) while Alt is held causes the system's stale Alt state
        // to leak into subsequent key events as synthetic Alt-modified characters.
        // The IME still tracks Alt via PhysicalKeyboardStateTracker (updated through the callback).
        // Gated on sAllKeysCallback, which is the compensation: HardwareKeyBridge registers it for
        // the whole life of a PKB IME instance and clears it in releaseResources(), so "callback
        // present" means "a live IME is tracking Alt on the system's behalf". Without the gate
        // this block consumed every hardware Alt press system-wide -- in every app, with nothing
        // left to compensate -- once the IME had gone away.
        //
        // A board key never reaches here: it is classified and returned above, which is what
        // keeps the MP01's Sym key (KEYCODE_ALT_RIGHT with scancode 249) out of this block.
        if ((keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT)
                && event.getDeviceId() != -1) {
            // Forward to AllKeysCallback so the IME's internal state tracker stays updated
            AllKeysCallback allKeysCallback = sAllKeysCallback;
            if (allKeysCallback == null) {
                return false; // Feature off: leave Alt to the system.
            }
            allKeysCallback.onKeyEvent(event);
            return true; // Consume - prevent system from tracking Alt
        }

        // ALT CHORD FIX: When a non-modifier key is pressed while Alt is held, forward the
        // event to the IME so it can cancel the long-press timer. This prevents accidental
        // Alt-lock when typing Alt+key chords.
        // We only care about ACTION_DOWN since that's when we need to cancel the timer.
        if (action == KeyEvent.ACTION_DOWN
                && (metaState & KeyEvent.META_ALT_MASK) != 0
                && !KeyEvent.isModifierKey(keyCode)
                && event.getDeviceId() != -1) {
            AllKeysCallback allKeysCallback = sAllKeysCallback;
            if (allKeysCallback != null) {
                allKeysCallback.onKeyEvent(event);
            }
            // Don't consume - let system transform the key normally
        }

        // Fast path: only process key down events
        if (action != KeyEvent.ACTION_DOWN) {
            return false;
        }

        // If preprocess-all-keys is enabled, forward ALL key events to the IME
        if (sPreprocessAllKeys) {
            AllKeysCallback allKeysCallback = sAllKeysCallback;
            if (allKeysCallback != null) {
                boolean handled = allKeysCallback.onKeyEvent(event);
                if (handled) {
                    return true;
                }
            }
        }
        
        return false; // Let system handle normally
    }

    /**
     * Decide whether this key is a board key (SYM / EMOJI / VOICE), preferring the device
     * config's answer and falling back to the legacy hardcoded scancode table.
     *
     * @param mapping  the resolved mapping, or null when unified mapping is off / no config matched
     * @return the board key type, or null if this is an ordinary key
     */
    private SpecialKeyType classifyBoardKey(ScancodeMapping mapping, int scanCode, int keyCode,
            int deviceId) {
        if (mapping != null && mapping.role != null && mapping.role.isConsumedAtAccessibilityLevel()) {
            SpecialKeyType unifiedKeyType = keyRoleToSpecialKeyType(mapping.role);

            // Phase-0 shadow comparison: the legacy classifier runs only to be diffed
            // against the unified one, so it stays inside the DEBUG guard. It used to be
            // computed on every board key press in release and thrown away.
            if (BuildConfig.DEBUG) {
                SpecialKeyType legacyKeyType = identifyKeyType(scanCode, keyCode, deviceId);
                if (unifiedKeyType != legacyKeyType) {
                    Log.w(TAG, "UNIFIED_MISMATCH: sc=" + scanCode + " kc=" + keyCode
                            + " unified=" + unifiedKeyType + " legacy=" + legacyKeyType
                            + " mapping=" + mapping);
                }
            }

            if (unifiedKeyType != null) {
                return unifiedKeyType;
            }
        }
        // No mapping, or a non-BOARD role: fall back to the legacy hardcoded table.
        return identifyKeyType(scanCode, keyCode, deviceId);
    }

    /**
     * Dispatch a board key to the registered special-key callback.
     *
     * <p>The callback's return value is honoured: it says whether the IME actually did something
     * with the key. Previously this method's predecessor returned {@code true} unconditionally,
     * which meant that with the accessibility service enabled but "Enable special key support"
     * off (no callback registered at all) a SYM press was consumed here and dropped on the floor
     * — the key was dead rather than merely un-enhanced. Returning false lets the key reach the
     * system, and from there the IME's ordinary onKeyDown pipeline, which has its own SYM
     * handling (KeyEventConverter).
     */
    private boolean handleBoardKey(SpecialKeyType keyType, ScancodeMapping mapping, int action,
            int scanCode, int metaState) {
        if (action != KeyEvent.ACTION_DOWN) {
            // Consume the release of a press we consumed, and only that one.
            if (sConsumedBoardKeyScanCode == scanCode) {
                sConsumedBoardKeyScanCode = NO_CONSUMED_SCANCODE;
                return true;
            }
            return false;
        }

        KeyEventCallback callback = sCallback;
        if (callback == null) {
            return false; // Feature off: leave the key to the system.
        }
        boolean handled = (mapping != null)
                ? callback.onSpecialKeyPressed(keyType, metaState, mapping)
                : callback.onSpecialKeyPressed(keyType, metaState);
        if (handled) {
            sConsumedBoardKeyScanCode = scanCode;
        }
        return handled;
    }

    /**
     * Convert a KeyRole (from unified XML mapping) to the legacy SpecialKeyType enum.
     * Only BOARD_* roles map to SpecialKeyType; other roles return null.
     */
    private static SpecialKeyType keyRoleToSpecialKeyType(KeyRole role) {
        if (role == null) return null;
        switch (role) {
            case BOARD_SYM:   return SpecialKeyType.SYM;
            case BOARD_EMOJI: return SpecialKeyType.EMOJI;
            case BOARD_VOICE: return SpecialKeyType.MIC;
            default:          return null;
        }
    }
    
    /**
     * Identify the type of special key based on scancode and keycode.
     * @return The key type, or null if not a special key
     */
    private SpecialKeyType identifyKeyType(int scanCode, int keyCode, int deviceId) {
        // Check raw hardware scancodes first (most reliable, fastest path)
        switch (scanCode) {
            case SCANCODE_SYM:
            case SCANCODE_SYM_KL:
                return SpecialKeyType.SYM;
            case SCANCODE_EMOJI:
            case SCANCODE_EMOJI_KL:
                return SpecialKeyType.EMOJI;
            case SCANCODE_MIC:
            case SCANCODE_MIC_KL:
                return SpecialKeyType.MIC;
        }
        
        // Check keycodes (if system properly maps them)
        if (keyCode == KeyEvent.KEYCODE_SYM || keyCode == KeyEvent.KEYCODE_F5) {
            return SpecialKeyType.SYM;
        }
        
        // Synthetic ALT_LEFT with no scancode - default to SYM
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT && deviceId == -1 && scanCode == 0) {
            return SpecialKeyType.SYM;
        }
        
        return null; // Not a special key
    }

    /**
     * Check if the service is currently connected and operational.
     */
    public static boolean isServiceConnected() {
        return sIsServiceConnected;
    }

    /**
     * Whether an IME has registered the special-key callback — i.e. whether "Enable special key
     * support" is in force. Exposed because the two callbacks have deliberately different
     * lifetimes (see {@code HardwareKeyBridge.registerInterceptorCallbacks}) and that difference
     * is otherwise invisible from outside this class.
     */
    @VisibleForTesting
    public static boolean hasSpecialKeyCallback() {
        return sCallback != null;
    }

    /**
     * Whether an IME has registered the all-keys callback — i.e. whether the Alt bleed-through
     * block has anything to compensate it. Registered for every PKB device regardless of
     * preferences; see {@link #hasSpecialKeyCallback()}.
     */
    @VisibleForTesting
    public static boolean hasAllKeysCallback() {
        return sAllKeysCallback != null;
    }
}
