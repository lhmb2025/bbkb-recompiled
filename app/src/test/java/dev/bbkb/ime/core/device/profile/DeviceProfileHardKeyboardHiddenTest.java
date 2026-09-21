package dev.bbkb.ime.core.device.profile;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * FIX-C item 1: {@link DeviceProfile#isHardwareKeyboardHidden()} has two writers —
 * {@code initialize(Context, Configuration)} from the IME's onCreate and {@code fromSettings} from
 * every {@code SettingsManager.loadSettings}. Its consumer is the swipe gate
 * ({@code SettingsValues.isGestureInputEnabledForDevice}, through isHardwareKeyboardActive()).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class DeviceProfileHardKeyboardHiddenTest {

    private static final int[] KEYBOARDS = {
            Configuration.KEYBOARD_UNDEFINED, Configuration.KEYBOARD_NOKEYS,
            Configuration.KEYBOARD_QWERTY, Configuration.KEYBOARD_12KEY };
    private static final int[] HIDDEN = {
            Configuration.HARDKEYBOARDHIDDEN_UNDEFINED, Configuration.HARDKEYBOARDHIDDEN_NO,
            Configuration.HARDKEYBOARDHIDDEN_YES };

    private MockedStatic<LocaleUtils> localeUtils;

    @Before
    public void setUp() {
        localeUtils = mockStatic(LocaleUtils.class);
        localeUtils.when(LocaleUtils::isCurrentSubtypeChinese).thenReturn(false);
    }

    @After
    public void tearDown() {
        localeUtils.close();
        DeviceProfile.setForceVkbMode(false);
        DeviceProfile.setDebugForceVkbMode(false);
        DeviceProfile.initialize(null);
    }

    /** A KEY2-like handset (also how the emulator's qemu keyboard detects). */
    private static DeviceCapabilities pkbShape() {
        return DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB,
                true, true, true, "qwerty", "4row");
    }

    /** A plain touchscreen phone. */
    private static DeviceCapabilities vkbShape() {
        return DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.VKB,
                false, false, false, "qwerty", "none");
    }

    private static Configuration config(int keyboard, int hardKeyboardHidden) {
        Configuration c = new Configuration();
        c.orientation = Configuration.ORIENTATION_PORTRAIT;
        c.keyboard = keyboard;
        c.hardKeyboardHidden = hardKeyboardHidden;
        return c;
    }

    private static Context contextWith(Configuration live) {
        Context context = mock(Context.class);
        Resources resources = mock(Resources.class);
        when(context.getResources()).thenReturn(resources);
        when(context.getApplicationContext()).thenReturn(context);
        when(resources.getConfiguration()).thenReturn(live);
        return context;
    }

    /** SettingsValues exactly as loadSettings builds it from {@code live}, minus the rest. */
    private static SettingsValues settingsFor(Configuration live, boolean vkbSwipe, boolean ckbSwipe)
            throws Exception {
        SettingsValues sv = mock(SettingsValues.class);
        set(sv, "hasHardwareKeyboard", SettingsManager.hasHardwareKeyboard(live));
        set(sv, "displayOrientation", live.orientation);
        set(sv, "isVkbGestureInputEnabled", vkbSwipe);
        set(sv, "isCkbGestureInputEnabled", ckbSwipe);
        return sv;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = SettingsValues.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** The real swipe gate, evaluated against whatever DeviceProfile.current() holds now. */
    private static boolean swipeGate(SettingsValues sv) throws Exception {
        Method m = SettingsValues.class.getDeclaredMethod("isGestureInputEnabledForDevice", Context.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(sv, (Context) null);
    }

    /** hidden flag as written by the IME's onCreate writer. */
    private static boolean hiddenAfterInitializeWriter(Configuration live) {
        DeviceProfile.current().updateConfiguration(live);
        return DeviceProfile.current().isHardwareKeyboardHidden();
    }

    /** hidden flag as written by the SettingsManager.loadSettings writer. */
    private static boolean hiddenAfterSettingsWriter(Configuration live) throws Exception {
        DeviceProfile.fromSettings(contextWith(live), settingsFor(live, false, false));
        return DeviceProfile.current().isHardwareKeyboardHidden();
    }

    // Both writers now apply one rule (hardKeyboardHidden == YES), for every combination.
    @Test
    public void theTwoWritersAgreeOnEveryConfiguration() throws Exception {
        DeviceProfile.installForTest(pkbShape());
        for (int keyboard : KEYBOARDS) {
            for (int hidden : HIDDEN) {
                Configuration live = config(keyboard, hidden);
                boolean expected = hidden == Configuration.HARDKEYBOARDHIDDEN_YES;
                String at = "keyboard=" + keyboard + " hidden=" + hidden;
                assertEquals(at + " (initialize writer)", expected, hiddenAfterInitializeWriter(live));
                assertEquals(at + " (settings writer)", expected, hiddenAfterSettingsWriter(live));
            }
        }
    }

    // ── the swipe gate on the three real configurations ─────────────────────
    // What the framework actually produces (PhoneWindowManager forces hardKeyboardHidden=YES
    // whenever keyboard=NOKEYS), evaluated after each writer.

    @Test
    public void key2WithKeyboardExposed_swipeFollowsTheCkbPref() throws Exception {
        DeviceProfile.installForTest(pkbShape());
        Configuration live = config(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO);
        assertSwipeAfterBothWriters(live, /*vkb*/ false, /*ckb*/ true, true);
        assertSwipeAfterBothWriters(live, /*vkb*/ true, /*ckb*/ false, false);
    }

    @Test
    public void key2WithBluetoothKeyboard_swipeFollowsTheCkbPref() throws Exception {
        // A second alphabetic keyboard leaves the framework config identical to (a).
        DeviceProfile.installForTest(pkbShape());
        Configuration live = config(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO);
        assertSwipeAfterBothWriters(live, false, true, true);
        assertSwipeAfterBothWriters(live, true, false, false);
    }

    @Test
    public void vkbOnlyPhone_swipeFollowsTheVkbPref() throws Exception {
        DeviceProfile.installForTest(vkbShape());
        Configuration live = config(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES);
        assertSwipeAfterBothWriters(live, true, false, true);
        assertSwipeAfterBothWriters(live, false, true, false);
    }

    @Test
    public void emulatorForcedToVkb_swipeFollowsTheCkbPref() throws Exception {
        // The Mac emulator's qemu keyboard: detected PKB, QWERTY exposed. debug_force_vkb_mode
        // does not change the device shape (see DeviceProfileShapeTest), so the CKB pref gates.
        DeviceProfile.installForTest(pkbShape());
        DeviceProfile.setDebugForceVkbMode(true);
        Configuration live = config(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO);
        assertSwipeAfterBothWriters(live, false, true, true);
        assertSwipeAfterBothWriters(live, true, false, false);
    }

    @Test
    public void key2ShowVkbKey_swipeFollowsEitherPref() throws Exception {
        DeviceProfile.installForTest(pkbShape());
        DeviceProfile.setForceVkbMode(true);
        Configuration live = config(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO);
        assertSwipeAfterBothWriters(live, true, false, true);
        assertSwipeAfterBothWriters(live, false, true, true);
        assertSwipeAfterBothWriters(live, false, false, false);
    }

    private static void assertSwipeAfterBothWriters(Configuration live, boolean vkb, boolean ckb,
            boolean expected) throws Exception {
        hiddenAfterInitializeWriter(live);
        assertEquals("after initialize writer", expected, swipeGate(settingsFor(live, vkb, ckb)));
        hiddenAfterSettingsWriter(live);
        assertEquals("after settings writer", expected, swipeGate(settingsFor(live, vkb, ckb)));
    }
}
