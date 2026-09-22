package dev.bbkb.ime.core.device.interceptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.device.profile.DeviceCapabilities;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.ime.HardwareKeyBridge;
import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.harness.ContextDelegatingAnswer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Which of {@link KeyInterceptorService}'s two callbacks depends on
 * {@code pref_key_interceptor_enabled}, and which does not.
 *
 * <p>They used to be registered and cleared together, gated on that one preference — default
 * <em>false</em>. That is wrong for the all-keys callback, which is not a feature at all: it is
 * the compensation for the service's Alt bleed-through block, which consumes hardware Alt and
 * relies on the callback to keep the IME's modifier tracker in sync. On a Minimal Phone MP01 the
 * tracker therefore saw none of the events the service was eating (beta triage #10, 2026-09).
 *
 * <p>The special-key callback <em>is</em> the feature the switch describes ("capture Emoji, SYM
 * and Voice keys"), and stays on the preference, so the switch keeps its user-visible meaning.
 *
 * <p>Asserted on the registrations themselves ({@code hasAllKeysCallback} /
 * {@code hasSpecialKeyCallback}), because the alternative — "was the key consumed?" — conflates
 * the registration with the IME's readiness: the service now honours the callback's answer, and
 * a callback belonging to an IME with no input view showing legitimately declines. The all-keys
 * half is cross-checked against the consumption behaviour it exists for.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class InterceptorCallbackRegistrationTest {

    private static final int SCANCODE_SYM = 249;
    private static final int SCANCODE_PLAIN_ALT = 100;

    private BlackBerryIME ime;
    private HardwareKeyBridge bridge;
    private KeyInterceptorService service;

    @Before
    public void setUp() {
        ime = Mockito.mock(BlackBerryIME.class, new ContextDelegatingAnswer());
        bridge = new HardwareKeyBridge(ime);
        service = new KeyInterceptorService();
        setFeaturePref(false);
        KeyInterceptorService.setCallback(null);
        KeyInterceptorService.setAllKeysCallback(null);
        KeyInterceptorService.setUnifiedKeyMappingEnabled(false);
        KeyInterceptorService.setPreprocessAllKeysEnabled(false);
    }

    @After
    public void tearDown() {
        setFeaturePref(false);
        KeyInterceptorService.setCallback(null);
        KeyInterceptorService.setAllKeysCallback(null);
        KeyInterceptorService.setPreprocessAllKeysEnabled(false);
        DeviceProfile.initialize(null);
    }

    private static void setFeaturePref(boolean enabled) {
        Context context = ApplicationProvider.getApplicationContext();
        PrefsManager.INSTANCE.getPrefs(context).edit()
                .putBoolean(KeyInterceptorManager.PREF_KEY_INTERCEPTOR_ENABLED, enabled)
                .commit();
    }

    private static void installPkbShape() {
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                true, false, false, "qwerty", "4row"));
    }

    private static void installVkbShape() {
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.VKB,
                false, false, false, "qwerty", "none"));
    }

    private static KeyEvent down(int keyCode, int scanCode) {
        return new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                /* deviceId */ 3, scanCode, 0, InputDevice.SOURCE_KEYBOARD);
    }

    /** Is the Alt bleed-through block being compensated, i.e. is the all-keys callback live? */
    private boolean altIsBeingCompensated() {
        boolean registered = KeyInterceptorService.hasAllKeysCallback();
        // Cross-check against the behaviour the registration exists for: with no callback the
        // block deliberately leaves hardware Alt to the system instead of eating it.
        assertEquals("hasAllKeysCallback() disagrees with what the Alt block actually does",
                registered, service.onKeyEvent(down(KeyEvent.KEYCODE_ALT_LEFT, SCANCODE_PLAIN_ALT)));
        return registered;
    }

    /**
     * Is "Enable special key support" in force? Asserted on the registration itself rather than
     * on whether a SYM press is consumed: the service honours the callback's answer, and a
     * callback attached to an IME with no input view showing legitimately declines.
     */
    private boolean symIsBeingCaptured() {
        return KeyInterceptorService.hasSpecialKeyCallback();
    }

    @Test
    public void theFeaturePreferenceStillDefaultsToOff() {
        // The premise of the tests below, and the reason the old coupling bit so hard.
        Context context = ApplicationProvider.getApplicationContext();
        PrefsManager.INSTANCE.getPrefs(context).edit()
                .remove(KeyInterceptorManager.PREF_KEY_INTERCEPTOR_ENABLED).commit();
        assertFalse(KeyInterceptorManager.isFeatureEnabled(context));
    }

    @Test
    public void theAllKeysCallbackIsRegisteredOnAPkbDeviceEvenWithTheFeatureOff() {
        installPkbShape();
        setFeaturePref(false);

        bridge.registerInterceptorCallbacks();

        assertTrue("the Alt-tracking callback is not the 'special key support' feature and must"
                        + " not be gated on it", altIsBeingCompensated());
    }

    @Test
    public void theSpecialKeyCallbackStaysGatedOnTheFeaturePreference() {
        installPkbShape();

        setFeaturePref(false);
        bridge.registerInterceptorCallbacks();
        assertFalse("with 'Enable special key support' off, a board key is not captured",
                symIsBeingCaptured());

        setFeaturePref(true);
        bridge.registerInterceptorCallbacks();
        assertTrue("with it on, a board key is captured", symIsBeingCaptured());
    }

    @Test
    public void turningTheFeatureOffTakesEffectWithoutWaitingForTheNextInputSession() {
        installPkbShape();
        setFeaturePref(true);
        bridge.registerInterceptorCallbacks();
        assertTrue(symIsBeingCaptured());

        KeyInterceptorManager.setFeatureEnabled(ApplicationProvider.getApplicationContext(), false);

        assertFalse("the switch going off should drop the special-key callback immediately",
                symIsBeingCaptured());
        assertTrue("…but not the Alt compensation, which is not part of that feature",
                altIsBeingCompensated());
    }

    @Test
    public void nothingIsRegisteredOnANonPkbDevice() {
        installVkbShape();
        setFeaturePref(true);

        bridge.registerInterceptorCallbacks();

        assertFalse(altIsBeingCompensated());
        assertFalse(symIsBeingCaptured());
    }

    @Test
    public void aDestroyedServiceDoesNotUnregisterALiveIme() {
        // Toggling the accessibility service off and on in system settings destroys and recreates
        // it while the IME keeps running. onDestroy() used to null the callbacks, so the IME went
        // silently unregistered until its next loadSettings().
        installPkbShape();
        setFeaturePref(true);
        bridge.registerInterceptorCallbacks();

        service.onDestroy();

        assertTrue(symIsBeingCaptured());
        assertTrue(altIsBeingCompensated());
    }

    @Test
    public void aDestroyedImeDoesUnregister() {
        installPkbShape();
        setFeaturePref(true);
        bridge.registerInterceptorCallbacks();

        bridge.unregisterInterceptorCallbacks();

        assertFalse("a dead IME must not leave hardware Alt consumed system-wide",
                altIsBeingCompensated());
        assertFalse(symIsBeingCaptured());
    }
}
