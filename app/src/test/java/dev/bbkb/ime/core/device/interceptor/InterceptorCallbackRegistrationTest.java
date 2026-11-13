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
 * {@link KeyInterceptorService}'s special-key callback follows {@code pref_key_interceptor_enabled}
 * (default off); the all-keys callback does not, because it keeps the IME's modifier tracker in
 * sync with the hardware Alt the service consumes. Asserted on the registrations themselves, since
 * a callback for an IME with no input view legitimately declines keys.
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
        // Cross-check: with no callback the block leaves hardware Alt to the system.
        assertEquals("hasAllKeysCallback() disagrees with what the Alt block actually does",
                registered, service.onKeyEvent(down(KeyEvent.KEYCODE_ALT_LEFT, SCANCODE_PLAIN_ALT)));
        return registered;
    }

    /** Is "Enable special key support" in force? Asserted on the registration, not on SYM consumption. */
    private boolean symIsBeingCaptured() {
        return KeyInterceptorService.hasSpecialKeyCallback();
    }

    @Test
    public void theFeaturePreferenceStillDefaultsToOff() {
        // The premise of the tests below.
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
        // Toggling the accessibility service in system settings recreates it while the IME keeps
        // running; the callbacks must survive onDestroy() or the IME is silently unregistered.
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
