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
 * §5.4 step 13: {@code DeviceSettingsManager} and {@link DeviceProfile} were two facades over the
 * same resolved {@link DeviceInputMapping}, each with its own copy of the override rules and each
 * missing something the other had — {@code DeviceProfile} had no {@code isSettingHidden}, and
 * {@code DeviceSettingsManager} had none of the layout or alt-table accessors. They are one facade
 * now, and this pins the whole override contract in the surviving one.
 *
 * <p>The three questions are deliberately distinct and were the easiest thing to get wrong while
 * merging: <em>hidden</em> removes the control from the settings UI, <em>read-only</em> greys it
 * out, and a <em>forced value</em> implies read-only without being flagged as such.
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

    // ── the accessor the merge had to carry across ───────────────────────────

    @Test
    public void hiddenSettingIsHiddenButNotReadOnly() {
        // isSettingHidden existed only on DeviceSettingsManager; ManagedPreferences uses it to
        // drop the control entirely, so losing it in the merge would have silently un-hidden
        // every hidden setting on every device config.
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

    // ── read-only, explicit and implied ──────────────────────────────────────

    @Test
    public void explicitReadOnlyFlagIsHonoured() {
        assertTrue(DeviceProfile.current().isSettingReadOnly(READ_ONLY_KEY));
        assertFalse(DeviceProfile.current().isSettingHidden(READ_ONLY_KEY));
    }

    @Test
    public void aForcedValueImpliesReadOnlyWithoutTheFlag() {
        // The override is constructed with readOnly=false; the forced value is what makes it
        // unchangeable. Both facades encoded this rule separately before the merge.
        assertFalse(DeviceProfile.current().getSettingOverride(FORCED_BOOL_KEY).readOnly);
        assertTrue(DeviceProfile.current().isSettingReadOnly(FORCED_BOOL_KEY));
    }

    // ── forced values are type-gated ─────────────────────────────────────────

    @Test
    public void forcedValuesAreReturnedForTheMatchingType() {
        assertTrue(DeviceProfile.current().getForcedBooleanValue(FORCED_BOOL_KEY, false));
        assertEquals(42, DeviceProfile.current().getForcedIntegerValue(FORCED_INT_KEY, 7));
        assertEquals("forced", DeviceProfile.current().getForcedStringValue(FORCED_STRING_KEY, "x"));
    }

    @Test
    public void forcedValuesOfTheWrongTypeFallBackToTheCallersDefault() {
        // Asking for the int override as a boolean must not coerce; each getter checks the
        // declared SettingType first.
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
