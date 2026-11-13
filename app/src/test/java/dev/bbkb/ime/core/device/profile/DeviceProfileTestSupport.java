package dev.bbkb.ime.core.device.profile;

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;

/**
 * Test-only reach-through to the package-private mapping setter. {@code installForTest} covers the
 * device shape, but the resolved {@link DeviceInputMapping} (read by {@code hasCustomAltMappings()}
 * and {@code usesMetaSymHandling()}) cannot be resolved on a JVM. Lives in test sources so nothing
 * in {@code app/src/main} can call it.
 */
public final class DeviceProfileTestSupport {

    private DeviceProfileTestSupport() {}

    /** Attach a resolved device mapping to the current profile. */
    public static void installMapping(DeviceInputMapping mapping) {
        DeviceProfile.current().setDeviceMapping(mapping);
    }
}
