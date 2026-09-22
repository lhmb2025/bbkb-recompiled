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
 * What a device config claims, in a form a confirmation dialog can show.
 *
 * <p>Import is the moment a config file stops being someone else's file and starts deciding how
 * this keyboard reads its hardware, and until now it happened silently: pick a file, get a toast.
 * A config that matches on {@code aw9523b-key} does nothing at all on a KEY2, and a config that
 * matches on {@code Build.DEVICE} alone can claim a handset it was never built for — neither is
 * visible without reading the XML. So the {@code <match>} block, the device type and the key count
 * are read back out of the imported file and shown before the user activates it.
 *
 * <p>This reads the file itself rather than the parsed {@code DeviceInputConfig}, because
 * {@code DeviceMatchCriteria} keeps its rules private (it answers "does this match?", not "what
 * does it match?"), and the whole point here is to show the criteria rather than apply them.
 */
public final class DeviceConfigSummary {

    /** Display name from the root element, or null if the file did not carry one. */
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

    private DeviceConfigSummary(@Nullable String name, @Nullable String deviceName,
                                @Nullable String buildDevice, @Nullable String brand,
                                @Nullable String vendorId, @Nullable String productId,
                                @Nullable String deviceType, @Nullable String keypadLayout,
                                @NonNull List<String> keyRoles) {
        this.name = name;
        this.deviceName = deviceName;
        this.buildDevice = buildDevice;
        this.brand = brand;
        this.vendorId = vendorId;
        this.productId = productId;
        this.deviceType = deviceType;
        this.keypadLayout = keypadLayout;
        this.keyRoles = keyRoles;
    }

    public int keyCount() {
        return keyRoles.size();
    }

    /** True when the config names nothing to match on — it would never apply to any device. */
    public boolean matchesNothing() {
        return isBlank(deviceName) && isBlank(buildDevice) && isBlank(brand)
                && isBlank(vendorId) && isBlank(productId);
    }

    /**
     * True when this config's {@code <match>} can apply to the handset described by {@code facts}.
     * Only the two criteria the builder ever writes are checked; a config matching on vendor or
     * product ids is reported as "cannot tell" (true), since the dialog's job is to warn about the
     * obvious mismatch, not to re-implement the matcher.
     */
    public boolean couldMatch(@NonNull DeviceFacts facts) {
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

    // ── reading ──────────────────────────────────────────────────────────────

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

    /**
     * Reads a summary from a config stream. Only the first {@code <device>} is described: the
     * schema allows several, but every config the app ships or builds carries one, and a dialog
     * that tried to summarise an arbitrary number of them would be a screen, not a dialog.
     */
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
                deviceType, keypadLayout, roles);
    }

    /** A {@code <match>} child's value, exact or regex, in the form the dialog shows it. */
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
