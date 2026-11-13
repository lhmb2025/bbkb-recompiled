package dev.bbkb.ime.core.device.interceptor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.builder.HardwareKeyCaptureBus;
import dev.bbkb.ime.core.device.config.model.KeyRole;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.keyevent.ResolvedKey;

/**
 * Accessibility service that sees raw hardware keys before the system rewrites them, for PKB
 * devices (Minimal Phone, Titan Pocket) whose ROM swallows Sym / Emoji / Mic or turns them into
 * synthetic ALT_LEFT. Calls static callbacks registered by the IME directly, for low latency.
 * Optional: turning it off leaves the rest of the keyboard working.
 *
 * @see KeyInterceptorManager
 */
public class KeyInterceptorService extends AccessibilityService {
    /** Minimal Phone raw scancodes. */
    private static final int SCANCODE_SYM = 249;      // 0xF9
    private static final int SCANCODE_EMOJI = 250;    // 0xFA
    private static final int SCANCODE_MIC = 251;      // 0xFB

    /** Fallbacks. The Minimal Phone ROM passes 666 / 667 as both scancode and key code ({@link ResolvedKey}). */
    private static final int SCANCODE_SYM_KL = 63;
    private static final int SCANCODE_EMOJI_KL = ResolvedKey.PSEUDO_KEYCODE_EMOJI;
    private static final int SCANCODE_MIC_KL = ResolvedKey.PSEUDO_KEYCODE_VOICE;

    private static volatile boolean sIsServiceConnected = false;

    private static volatile KeyEventCallback sCallback = null;

    private static volatile AllKeysCallback sAllKeysCallback = null;

    private static volatile boolean sPreprocessAllKeys = false;

    private static final int NO_CONSUMED_SCANCODE = Integer.MIN_VALUE;

    /**
     * Board key whose DOWN was consumed, so its UP is consumed too (one slot: one key at a time).
     * Otherwise the system sees an unmatched release; on the MP01 that is an ALT_RIGHT up, which
     * leaves the framework's Alt state stuck.
     */
    private static volatile int sConsumedBoardKeyScanCode = NO_CONSUMED_SCANCODE;

    public interface KeyEventCallback {
        /**
         * A special key the built-in scancode table recognised (no profile entry).
         * @return true if the key was handled
         */
        boolean onSpecialKeyPressed(SpecialKeyType keyType, int metaState);

        /**
         * A special key the device profile names; {@code mapping} carries its Alt character and board id.
         * @return true if the key was handled
         */
        default boolean onSpecialKeyPressed(SpecialKeyType keyType, int metaState, ScancodeMapping mapping) {
            return onSpecialKeyPressed(keyType, metaState);
        }
    }

    public enum SpecialKeyType {
        SYM,
        EMOJI,
        MIC
    }

    public interface AllKeysCallback {
        /** @return true to consume the key */
        boolean onKeyEvent(KeyEvent event);
    }

    public static void setCallback(KeyEventCallback callback) {
        sCallback = callback;
        // A press consumed for the outgoing callback must not swallow an unrelated key-up.
        sConsumedBoardKeyScanCode = NO_CONSUMED_SCANCODE;
    }

    public static void setAllKeysCallback(AllKeysCallback callback) {
        sAllKeysCallback = callback;
    }

    public static void setPreprocessAllKeysEnabled(boolean enabled) {
        sPreprocessAllKeys = enabled;
    }

    public static boolean isPreprocessAllKeysEnabled() {
        return sPreprocessAllKeys;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.eventTypes = 0;
            setServiceInfo(info);
        }

        sIsServiceConnected = true;
    }

    @Override
    public void onDestroy() {
        // Leave the callbacks: they belong to the IME, which clears them in releaseResources().
        // Toggling the service off and on recreates it while the IME keeps running.
        sIsServiceConnected = false;
        sConsumedBoardKeyScanCode = NO_CONSUMED_SCANCODE;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Key events only.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int scanCode = event.getScanCode();
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        int metaState = event.getMetaState();

        // Must run first: board keys are visible only here, and while the profile builder's
        // capture screen is open a Sym press must record its scancode, not open the symbol board.
        if (HardwareKeyCaptureBus.isCapturing()
                && HardwareKeyCaptureBus.offer(scanCode, keyCode, event.getDeviceId(),
                        event.getRepeatCount(), action == KeyEvent.ACTION_DOWN,
                        event.getEventTime())) {
            return true;
        }

        // Must run before the Alt blocks: the MP01 ROM sends Sym as scancode 249 +
        // KEYCODE_ALT_RIGHT. The profile (or scancode table) decides what a key is, not the .kl keycode.
        ScancodeMapping mapping =
                DeviceInputResolver.keyRole(scanCode, keyCode, event.getDeviceId());
        if (mapping != null && mapping.role == KeyRole.MULTIFUNCTION) {
            // Never consumed here (nor checked against the scancode table): the IME key pipeline
            // dispatches their configured action, possibly a held Ctrl.
            return false;
        }
        SpecialKeyType boardKeyType = classifyBoardKey(mapping, scanCode, keyCode, event.getDeviceId());
        if (boardKeyType != null) {
            return handleBoardKey(boardKeyType, mapping, action, scanCode, metaState);
        }

        // Consume hardware Alt so the system never tracks it: a special key consumed while Alt is
        // held would otherwise leave stale Alt on later keys. The IME tracks Alt via the callback,
        // so only do this while a live IME has registered it, or Alt dies system-wide.
        if ((keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT)
                && event.getDeviceId() != -1) {
            AllKeysCallback allKeysCallback = sAllKeysCallback;
            if (allKeysCallback == null) {
                return false;
            }
            allKeysCallback.onKeyEvent(event);
            return true;
        }

        // Alt+key chord: tell the IME so it cancels the Alt long-press (lock) timer; not consumed.
        if (action == KeyEvent.ACTION_DOWN
                && (metaState & KeyEvent.META_ALT_MASK) != 0
                && !KeyEvent.isModifierKey(keyCode)
                && event.getDeviceId() != -1) {
            AllKeysCallback allKeysCallback = sAllKeysCallback;
            if (allKeysCallback != null) {
                allKeysCallback.onKeyEvent(event);
            }
        }

        if (action != KeyEvent.ACTION_DOWN) {
            return false;
        }

        if (sPreprocessAllKeys) {
            AllKeysCallback allKeysCallback = sAllKeysCallback;
            if (allKeysCallback != null) {
                boolean handled = allKeysCallback.onKeyEvent(event);
                if (handled) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * The profile's role decides; the built-in table (Minimal Phone 249/250/251) answers only for
     * keys the profile does not name. On a Titan those scancodes are Func1/Func2/Fn with other roles.
     *
     * @param mapping the profile's entry for this key, or null
     * @return the board key type, or null for an ordinary key
     */
    private SpecialKeyType classifyBoardKey(ScancodeMapping mapping, int scanCode, int keyCode,
            int deviceId) {
        if (mapping != null && mapping.role != null) {
            return keyRoleToSpecialKeyType(mapping.role);
        }
        return identifyKeyType(scanCode, keyCode, deviceId);
    }

    /** Returns the callback's answer; false lets the key reach the IME's ordinary onKeyDown path. */
    private boolean handleBoardKey(SpecialKeyType keyType, ScancodeMapping mapping, int action,
            int scanCode, int metaState) {
        if (action != KeyEvent.ACTION_DOWN) {
            if (sConsumedBoardKeyScanCode == scanCode) {
                sConsumedBoardKeyScanCode = NO_CONSUMED_SCANCODE;
                return true;
            }
            return false;
        }

        KeyEventCallback callback = sCallback;
        if (callback == null) {
            return false;
        }
        boolean handled = (mapping != null)
                ? callback.onSpecialKeyPressed(keyType, metaState, mapping)
                : callback.onSpecialKeyPressed(keyType, metaState);
        if (handled) {
            sConsumedBoardKeyScanCode = scanCode;
        }
        return handled;
    }

    /** Null for any non-BOARD_* role. */
    private static SpecialKeyType keyRoleToSpecialKeyType(KeyRole role) {
        if (role == null) return null;
        switch (role) {
            case BOARD_SYM:   return SpecialKeyType.SYM;
            case BOARD_EMOJI: return SpecialKeyType.EMOJI;
            case BOARD_VOICE: return SpecialKeyType.MIC;
            default:          return null;
        }
    }

    private SpecialKeyType identifyKeyType(int scanCode, int keyCode, int deviceId) {
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

        if (keyCode == KeyEvent.KEYCODE_SYM || keyCode == KeyEvent.KEYCODE_F5) {
            return SpecialKeyType.SYM;
        }

        // A ROM-synthesised ALT_LEFT with no scancode is the swallowed Sym key.
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT && deviceId == -1 && scanCode == 0) {
            return SpecialKeyType.SYM;
        }

        return null;
    }

    public static boolean isServiceConnected() {
        return sIsServiceConnected;
    }

    /** True while "Enable special key support" is in force; its lifetime differs from the all-keys callback's. */
    @VisibleForTesting
    public static boolean hasSpecialKeyCallback() {
        return sCallback != null;
    }

    /** Registered for every PKB device whatever the preferences; gates the Alt-consuming block. */
    @VisibleForTesting
    public static boolean hasAllKeysCallback() {
        return sAllKeysCallback != null;
    }
}
