package dev.bbkb.ime.core.device.config.builder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The one place a screen can ask to see raw hardware key events, wherever in the app they surface.
 *
 * <p>A settings screen cannot simply read the keyboard itself. A hardware key press on a PKB
 * handset can arrive at any of three places, and which one depends on state the screen does not
 * control:
 *
 * <ul>
 *   <li>the <b>accessibility service</b> ({@code KeyInterceptorService}) sees every key first, and
 *       for a board key — Sym, Emoji, Mic — it <em>consumes</em> the event, so that key reaches
 *       nothing else at all. Those are precisely the keys worth capturing;</li>
 *   <li>the <b>IME key path</b> ({@code KeyEventProcessor.onKeyDownInternal}) sees it when an
 *       editor is bound;</li>
 *   <li>the <b>foreground activity</b> sees whatever is left, which is how the builder screen
 *       gets ordinary keys when the accessibility service is not enabled.</li>
 * </ul>
 *
 * <p>So each of those calls {@link #offer}, and while a listener is registered the event is
 * consumed there rather than acted on: during capture a Sym press must not open the symbol board
 * over the settings screen, and an Alt press must not leave sticky Alt behind.
 *
 * <p>Static, because the three producers are a service, an IME and an activity with no shared
 * object between them, and single-listener, because only one capture screen can be in front.
 * Registration is scoped to that screen's composition; {@link #unregister} on dispose is what
 * turns the hooks back into two integer comparisons.
 */
public final class HardwareKeyCaptureBus {

    /** Receives raw hardware key events while a capture screen is open. */
    public interface Listener {
        /**
         * @param scanCode    the raw hardware scancode, as the kernel reported it
         * @param keyCode     the Android keycode the ROM's keylayout attached to it
         * @param deviceId    the {@code InputDevice} id the event came from
         * @param repeatCount the event's repeat count (0 = initial press)
         * @param down        true for {@code ACTION_DOWN}, false for {@code ACTION_UP}
         * @param eventTime   event timestamp in ms
         * @return true if the event was used and must not travel any further
         */
        boolean onHardwareKey(int scanCode, int keyCode, int deviceId, int repeatCount,
                              boolean down, long eventTime);
    }

    @Nullable private static volatile Listener sListener;

    private HardwareKeyCaptureBus() {} // No instantiation

    /** Installs the capture listener, replacing any previous one. */
    public static void register(@NonNull Listener listener) {
        sListener = listener;
    }

    /**
     * Removes {@code listener} if it is still the installed one. Passing the listener (rather than
     * clearing unconditionally) means a screen being disposed after its replacement has already
     * registered cannot switch capture off underneath it.
     */
    public static void unregister(@NonNull Listener listener) {
        if (sListener == listener) {
            sListener = null;
        }
    }

    /**
     * True while a capture screen is open. This is the whole cost of the hooks when it is not:
     * one volatile read.
     */
    public static boolean isCapturing() {
        return sListener != null;
    }

    /**
     * Offers one raw hardware key event to the capture screen.
     *
     * @return true if capture consumed it, in which case the caller must not process it further
     */
    public static boolean offer(int scanCode, int keyCode, int deviceId, int repeatCount,
                                boolean down, long eventTime) {
        final Listener listener = sListener;
        if (listener == null) {
            return false;
        }
        return listener.onHardwareKey(scanCode, keyCode, deviceId, repeatCount, down, eventTime);
    }
}
