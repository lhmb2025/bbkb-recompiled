package dev.bbkb.ime.core.device.profile;

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;

/**
 * Package-private reach-through for tests that live outside {@code core.device.profile}.
 *
 * <p>{@link DeviceProfile#installForTest(DeviceCapabilities)} covers the device <em>shape</em>,
 * but the resolved {@link DeviceInputMapping} — which is what {@code hasCustomAltMappings()} and
 * therefore {@code usesMetaSymHandling()} read — is set through a package-private setter, and on
 * a JVM there are no {@code InputDevice}s for {@code DeviceProfile.initialize(context)} to
 * resolve one from. Tests of the key pipeline need both halves to reproduce a Minimal Phone.
 *
 * <p>Deliberately a test-source class in the production package rather than a widened production
 * API: nothing in {@code app/src/main} can call it.
 */
public final class DeviceProfileTestSupport {

    private DeviceProfileTestSupport() {}

    /** Attach a resolved device mapping to the current profile. */
    public static void installMapping(DeviceInputMapping mapping) {
        DeviceProfile.current().setDeviceMapping(mapping);
    }
}
