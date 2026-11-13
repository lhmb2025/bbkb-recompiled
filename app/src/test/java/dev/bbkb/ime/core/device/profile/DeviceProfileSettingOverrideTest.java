package dev.bbkb.ime.core.device.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pins the {@link DeviceProfile} setting-override contract. The three questions are distinct:
 * <em>hidden</em> removes the control from the UI, <em>read-only</em> greys it out, and a
 * <em>forced value</em> implies read-only without being flagged as such.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class DeviceProfileSettingOverrideTest {

    private static final String HIDDEN_KEY = "pref_hidden_thing";
    private static final String READ_ONLY_KEY = "pref_read_only_thing";
    private static final String FORCED_BOOL_KEY = "pref_forced_bool";
    private static final String FORCED_INT_KEY = "pref_forced_int";
    private static final String FORCED_STRING_KEY = "pref_forced_string";
    private static final String UNMAPPED_KEY = "pref_nobody_mentions_this";

    @Before
    public void setUp() {
        // A null context builds a profile with no prefs read and no mapping resolved.
        DeviceProfile.initialize(null);

        DeviceInputMapping mapping = new DeviceInputMapping();
        mapping.deviceName = "test_keypad";
        mapping.settingsOverrides.add(hidden(HIDDEN_KEY));
        mapping.settingsOverrides.add(new DeviceSettingOverride(
                READ_ONLY_KEY, DeviceSettingOverride.SettingType.BOOLEAN, null, true));
        mapping.settingsOverrides.add(new DeviceSettingOverride(
                FORCED_BOOL_KEY, DeviceSettingOverride.SettingType.BOOLEAN, true, false));
        mapping.settingsOverrides.add(new DeviceSettingOverride(
                FORCED_INT_KEY, DeviceSettingOverride.SettingType.INTEGER, 42, false));
        mapping.settingsOverrides.add(new DeviceSettingOverride(
                FORCED_STRING_KEY, DeviceSettingOverride.SettingType.STRING, "forced", false));

        DeviceProfile.current().setDeviceMapping(mapping);
    }

    @After
    public void tearDown() {
        DeviceProfile.initialize(null);
    }

    private static DeviceSettingOverride hidden(String key) {
        DeviceSettingOverride override = new DeviceSettingOverride(
                key, DeviceSettingOverride.SettingType.BOOLEAN, null, false);
        override.hidden = true;
        return override;
    }

    @Test
    public void hiddenSettingIsHiddenButNotReadOnly() {
        // ManagedPreferences drops hidden controls entirely.
        assertTrue(DeviceProfile.current().isSettingHidden(HIDDEN_KEY));
        assertFalse("hidden alone must not imply read-only",
                DeviceProfile.current().isSettingReadOnly(HIDDEN_KEY));
    }

    @Test
    public void unmappedKeysAreNeitherHiddenNorReadOnly() {
        assertFalse(DeviceProfile.current().isSettingHidden(UNMAPPED_KEY));
        assertFalse(DeviceProfile.current().isSettingReadOnly(UNMAPPED_KEY));
        assertFalse(DeviceProfile.current().hasSettingOverride(UNMAPPED_KEY));
        assertNull(DeviceProfile.current().getSettingOverride(UNMAPPED_KEY));
    }

    @Test
    public void withNoDeviceMappingNothingIsOverridden() {
        DeviceProfile.initialize(null);
        assertFalse(DeviceProfile.current().isSettingHidden(HIDDEN_KEY));
        assertFalse(DeviceProfile.current().isSettingReadOnly(READ_ONLY_KEY));
        assertNull(DeviceProfile.current().getSettingOverride(HIDDEN_KEY));
    }

    @Test
    public void explicitReadOnlyFlagIsHonoured() {
        assertTrue(DeviceProfile.current().isSettingReadOnly(READ_ONLY_KEY));
        assertFalse(DeviceProfile.current().isSettingHidden(READ_ONLY_KEY));
    }

    @Test
    public void aForcedValueImpliesReadOnlyWithoutTheFlag() {
        // The override has readOnly=false; the forced value is what makes it unchangeable.
        assertFalse(DeviceProfile.current().getSettingOverride(FORCED_BOOL_KEY).readOnly);
        assertTrue(DeviceProfile.current().isSettingReadOnly(FORCED_BOOL_KEY));
    }

    @Test
    public void forcedValuesAreReturnedForTheMatchingType() {
        assertTrue(DeviceProfile.current().getForcedBooleanValue(FORCED_BOOL_KEY, false));
        assertEquals(42, DeviceProfile.current().getForcedIntegerValue(FORCED_INT_KEY, 7));
        assertEquals("forced", DeviceProfile.current().getForcedStringValue(FORCED_STRING_KEY, "x"));
    }

    @Test
    public void forcedValuesOfTheWrongTypeFallBackToTheCallersDefault() {
        // No coercion: each getter checks the declared SettingType first.
        assertFalse(DeviceProfile.current().getForcedBooleanValue(FORCED_INT_KEY, false));
        assertEquals(7, DeviceProfile.current().getForcedIntegerValue(FORCED_BOOL_KEY, 7));
        assertEquals("x", DeviceProfile.current().getForcedStringValue(FORCED_INT_KEY, "x"));
    }

    @Test
    public void anOverrideWithNoForcedValueFallsBackToTheCallersDefault() {
        assertTrue(DeviceProfile.current().getForcedBooleanValue(READ_ONLY_KEY, true));
        assertFalse(DeviceProfile.current().getForcedBooleanValue(READ_ONLY_KEY, false));
    }
}
