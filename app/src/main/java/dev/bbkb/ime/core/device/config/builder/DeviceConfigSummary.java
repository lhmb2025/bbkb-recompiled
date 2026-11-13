package dev.bbkb.ime.core.device.config.builder;

import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.shared.Logger;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * What a device config claims ({@code <match>}, device type, keys), for the import confirmation
 * dialog. Reads the XML itself because {@code DeviceMatchCriteria} keeps its rules private.
 */
public final class DeviceConfigSummary {
    /** Display name from the root element, or null. */
    @Nullable public final String name;
    @Nullable public final String deviceName;
    @Nullable public final String buildDevice;
    @Nullable public final String brand;
    @Nullable public final String vendorId;
    @Nullable public final String productId;
    /** {@code PKB}, {@code CKB} or null. */
    @Nullable public final String deviceType;
    @Nullable public final String keypadLayout;
    /** Roles of the {@code <key>} elements, in document order. */
    @NonNull public final List<String> keyRoles;
    /** The built-in profile the first {@code <device>} builds on ({@code extends}), or null. */
    @Nullable public final String basedOn;

    private DeviceConfigSummary(@Nullable String name, @Nullable String deviceName,
                                @Nullable String buildDevice, @Nullable String brand,
                                @Nullable String vendorId, @Nullable String productId,
                                @Nullable String deviceType, @Nullable String keypadLayout,
                                @NonNull List<String> keyRoles, @Nullable String basedOn) {
        this.name = name;
        this.deviceName = deviceName;
        this.buildDevice = buildDevice;
        this.brand = brand;
        this.vendorId = vendorId;
        this.productId = productId;
        this.deviceType = deviceType;
        this.keypadLayout = keypadLayout;
        this.keyRoles = keyRoles;
        this.basedOn = basedOn;
    }

    public int keyCount() {
        return keyRoles.size();
    }

    /** True when the config names nothing to match on, so it would never apply. */
    public boolean matchesNothing() {
        // A profile that builds on a built-in one and writes no rules of its own uses that one's.
        return isBlank(basedOn) && writesNoMatchRules();
    }

    private boolean writesNoMatchRules() {
        return isBlank(deviceName) && isBlank(buildDevice) && isBlank(brand)
                && isBlank(vendorId) && isBlank(productId);
    }

    /**
     * Whether {@code <match>} can apply to {@code facts}. Checks only name, build device and brand;
     * vendor/product ids count as "cannot tell" (true). A warning aid, not the matcher.
     */
    public boolean couldMatch(@NonNull DeviceFacts facts) {
        // Inherited rules are not read here; the profile it builds on decides, so no warning.
        if (!isBlank(basedOn) && writesNoMatchRules()) return true;
        if (!isBlank(deviceName)) {
            boolean seen = false;
            for (DeviceFacts.Keyboard keyboard : facts.keyboards) {
                if (keyboard.name.equals(deviceName)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) return false;
        }
        if (!isBlank(buildDevice) && !buildDevice.equalsIgnoreCase(facts.buildDevice)) {
            return false;
        }
        if (!isBlank(brand) && !brand.equalsIgnoreCase(facts.buildBrand)) {
            return false;
        }
        return true;
    }

    /** Reads a summary from a config file, or null if it cannot be read at all. */
    @Nullable
    public static DeviceConfigSummary of(@NonNull File file) {
        try (InputStream is = new FileInputStream(file)) {
            return of(is);
        } catch (Exception e) {
            Logger.error("DeviceConfigSummary", "Could not read " + file.getName()
                    + ": " + e.getMessage());
            return null;
        }
    }

    /** Reads a summary from a config stream, describing only the first {@code <device>}. */
    @Nullable
    public static DeviceConfigSummary of(@NonNull InputStream is) {
        String name = null;
        String deviceName = null;
        String buildDevice = null;
        String brand = null;
        String vendorId = null;
        String productId = null;
        String deviceType = null;
        String keypadLayout = null;
        String basedOn = null;
        final List<String> roles = new ArrayList<>();

        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(is, "UTF-8");

            int depthOfDevice = -1;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    final String tag = parser.getName();
                    if ("device-input-config".equals(tag) || "device-mappings".equals(tag)) {
                        name = parser.getAttributeValue(null, "name");
                    } else if ("device".equals(tag)) {
                        if (depthOfDevice >= 0) break;  // second <device>: stop at the first
                        depthOfDevice = parser.getDepth();
                        basedOn = parser.getAttributeValue(null, "extends");
                    } else if (depthOfDevice >= 0) {
                        switch (tag) {
                            case "device-name":
                                deviceName = matchValue(parser);
                                break;
                            case "build-device":
                                buildDevice = matchValue(parser);
                                break;
                            case "brand":
                                brand = matchValue(parser);
                                break;
                            case "vendor-id":
                                vendorId = matchValue(parser);
                                break;
                            case "product-id":
                                productId = matchValue(parser);
                                break;
                            case "device-type":
                                deviceType = parser.nextText().trim();
                                break;
                            case "keypad-layout":
                                keypadLayout = parser.nextText().trim();
                                break;
                            case "key": {
                                final String role = parser.getAttributeValue(null, "role");
                                if (role != null) roles.add(role);
                                break;
                            }
                            default:
                                break;
                        }
                    }
                }
                event = parser.next();
            }
        } catch (Exception e) {
            Logger.error("DeviceConfigSummary", "Could not summarise config: " + e.getMessage());
            return null;
        }
        return new DeviceConfigSummary(name, deviceName, buildDevice, brand, vendorId, productId,
                deviceType, keypadLayout, roles, basedOn);
    }

    /** A {@code <match>} child's value: the exact value, or {@code "regex: "} plus the pattern. */
    @Nullable
    private static String matchValue(@NonNull XmlPullParser parser) {
        final String exact = parser.getAttributeValue(null, "exact");
        if (exact != null) return exact;
        final String regex = parser.getAttributeValue(null, "regex");
        return regex != null ? "regex: " + regex : null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
