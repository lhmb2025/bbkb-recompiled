package dev.bbkb.ime.core.device.config.parser;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.Xml;

import dev.bbkb.ime.core.device.config.model.CkbKeyGridConfig;
import dev.bbkb.ime.core.device.config.model.DeviceInputConfig;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.model.DeviceMatchCriteria;
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride;
import dev.bbkb.ime.core.device.config.model.KeyRole;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector;
import dev.bbkb.ime.core.shared.Logger;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for device input mapping XML configuration.
 *
 * <p>Audit W3-D: the eight hand-written {@code while (ev != END_TAG || !"x".equals(getName()))}
 * loops are now one {@link #children} helper. It keeps the original cursor discipline exactly —
 * advance, dispatch on START_TAG, advance again, terminate on the named END_TAG — so a handler
 * that consumes through its own END_TAG ({@code nextText()}, a nested {@code children}) leaves the
 * parser where the old loops left it. A truncated document throws out to the document-level catch,
 * keeping whatever devices were already parsed, as before -- see {@link #children} for why that
 * throw is explicit rather than left to the parser.
 */
public class DeviceInputMappingParser {

    private static final String TAG = "DeviceInputMappingParser";

    // ── pull-parser scaffolding ──────────────────────────────────────────────

    /** Handler for one START_TAG; {@code name} is the element name. */
    private interface Tag {
        void on(XmlPullParser parser, String name) throws XmlPullParserException, IOException;
    }

    /**
     * Runs {@code body} for every START_TAG inside the currently-open element named {@code end}.
     *
     * <p>The END_DOCUMENT check is load-bearing, not defensive. This loop terminates only on its
     * own END_TAG, and a document that ends with the element still open never produces one. Whether
     * that is an exception or an infinite loop then depends on the pull parser: Android's
     * {@code KXmlParser} keeps returning END_DOCUMENT from {@code next()} rather than throwing, so
     * without this the loop spins at 100% CPU forever. That is reachable from a user-supplied file
     * -- {@code CustomDeviceConfigManager.importCustomConfig} accepts any URI, and the imported
     * config is parsed on IME startup via {@code DeviceProfile.initialize} -- which turns a
     * truncated XML file into a keyboard that never starts and a flat battery.
     *
     * <p>Throwing (rather than returning quietly) is what the callers already expect: each entry
     * point catches and keeps whatever devices were parsed before the malformed tail.
     */
    private static void children(XmlPullParser parser, String end, Tag body)
            throws XmlPullParserException, IOException {
        int eventType = parser.next();
        while (eventType != XmlPullParser.END_TAG || !end.equals(parser.getName())) {
            if (eventType == XmlPullParser.END_DOCUMENT) {
                throw new XmlPullParserException(
                        "Document ended inside <" + end + ">: truncated or malformed config");
            }
            if (eventType == XmlPullParser.START_TAG) {
                body.on(parser, parser.getName());
            }
            eventType = parser.next();
        }
    }

    private static String attr(XmlPullParser parser, String name) {
        return parser.getAttributeValue(null, name);
    }

    /** Element text, trimmed; null when absent or blank. */
    private static String trimmedText(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        final String text = parser.nextText();
        return (text == null || text.trim().isEmpty()) ? null : text.trim();
    }

    private static int intAttr(XmlPullParser parser, String name, int def) {
        final String v = attr(parser, name);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static float floatAttr(XmlPullParser parser, String name, float def) {
        final String v = attr(parser, name);
        if (v == null) return def;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    // ── document entry points ────────────────────────────────────────────────

    /**
     * Parse the device input mapping configuration from XML.
     * @param context Application context
     * @return DeviceInputConfig containing parsed mappings
     */
    public static DeviceInputConfig parseConfig(Context context) {
        return fromResource(context, dev.bbkb.ime.R.xml.device_input_config,
                "device KCM mappings");
    }

    /**
     * Parse device input configuration from an XML resource.
     * Used for loading preloaded configuration files from res/xml/.
     * @param context Application context
     * @param resId Resource ID of the XML file
     * @return DeviceInputConfig containing parsed mappings
     */
    public static DeviceInputConfig parseConfigFromXmlResource(Context context, int resId) {
        return fromResource(context, resId, "device config from XML resource");
    }

    private static DeviceInputConfig fromResource(Context context, int resId, String what) {
        final DeviceInputConfig config = new DeviceInputConfig();
        XmlResourceParser parser = null;
        try {
            parser = context.getResources().getXml(resId);
            parseDevices(parser, config);
        } catch (Exception e) {
            Logger.error(TAG, "Error parsing " + what + ": " + e.getMessage());
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
        return config;
    }

    /**
     * Parse device input configuration from an InputStream.
     * Used for loading custom configuration files.
     * @param is InputStream containing XML configuration
     * @return DeviceInputConfig containing parsed mappings
     */
    public static DeviceInputConfig parseConfigFromStream(InputStream is) {
        final DeviceInputConfig config = new DeviceInputConfig();
        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(is, "UTF-8");
            parseDevices(parser, config);
        } catch (Exception e) {
            Logger.error(TAG, "Error parsing device config from stream: " + e.getMessage());
        }
        return config;
    }

    /** Collects every {@code <device>} in the document. Devices parsed before a malformed tail are kept. */
    private static void parseDevices(XmlPullParser parser, DeviceInputConfig config)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && "device".equals(parser.getName())) {
                final DeviceInputMapping mapping = parseDeviceV2(parser);
                if (mapping != null) {
                    config.mappings.add(mapping);
                }
            }
            eventType = parser.next();
        }
    }

    /**
     * Parse the display name from a configuration file.
     * Looks for a "name" attribute in the root element or a <name> tag.
     * @param is InputStream containing XML configuration
     * @return Display name, or null if not found
     */
    public static String parseDisplayName(InputStream is) {
        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(is, "UTF-8");

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    final String tagName = parser.getName();
                    if ("device-input-config".equals(tagName) || "device-mappings".equals(tagName)) {
                        final String nameAttr = attr(parser, "name");
                        if (nameAttr != null && !nameAttr.isEmpty()) {
                            return nameAttr;
                        }
                    }
                    if ("name".equals(tagName)) {
                        return parser.nextText();
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Logger.error(TAG, "Error parsing display name from stream: " + e.getMessage());
        }
        return null;
    }

    // ── <device> ─────────────────────────────────────────────────────────────

    /**
     * Parse a device entry in v2.0 format.
     */
    private static DeviceInputMapping parseDeviceV2(XmlPullParser parser)
            throws XmlPullParserException, IOException {

        final DeviceInputMapping mapping = new DeviceInputMapping();
        mapping.mappingType = DeviceInputMapping.InputMappingType.DEFAULT;

        children(parser, "device", (p, tag) -> {
            switch (tag) {
                case "match":
                    parseMatchCriteria(p, mapping);
                    break;
                case "device-type": {
                    final String deviceType = p.nextText();
                    if ("PKB".equalsIgnoreCase(deviceType)) {
                        mapping.forcePkbDevice = true;
                    } else if ("CKB".equalsIgnoreCase(deviceType)) {
                        mapping.forcePkbDevice = true;
                        mapping.forceTouchKeypad = true;
                    }
                    break;
                }
                case "input-mappings":
                    parseInputMappings(p, mapping);
                    break;
                case "settings-overrides":
                    children(p, tag, (q, child) -> {
                        if (!"setting".equals(child)) return;
                        final DeviceSettingOverride override = parseSettingOverride(q);
                        if (override != null) {
                            mapping.settingsOverrides.add(override);
                        }
                    });
                    break;
                case "ckb-key-grid":
                    mapping.ckbKeyGridConfig = parseCkbKeyGrid(p);
                    break;
                case "kdb-variant": {
                    final String variant = trimmedText(p);
                    if (variant != null) mapping.kdbVariant = variant;
                    break;
                }
                case "ckb-y-warp": {
                    final String warp = trimmedText(p);
                    if (warp != null) mapping.ckbYWarp = warp;
                    break;
                }
                case "keypad-layout": {
                    // Schema v2.3: the one override for the detected physical keypad layout.
                    // Validated here so a typo is rejected at parse time rather than becoming a
                    // layout no `<case physicalKeypadVariant=...>` will ever match.
                    final String declared = trimmedText(p);
                    final String layout = KeypadLayoutDetector.normalize(declared);
                    if (layout != null) {
                        mapping.keypadLayout = layout;
                    } else if (declared != null) {
                        Logger.error(TAG, "Ignoring <keypad-layout>" + declared + "</keypad-layout>"
                                + ": expected qwerty, qwertz or azerty");
                    }
                    break;
                }
                default:
                    break;
            }
        });

        // Validate: must have at least device name or match criteria
        final boolean named = mapping.deviceName != null && !mapping.deviceName.isEmpty();
        final boolean matched = mapping.matchCriteria != null && mapping.matchCriteria.hasAnyCriteria();
        return (named || matched) ? mapping : null;
    }

    /**
     * Parse match criteria section. Five element names, one rule shape: {@code exact} wins over
     * {@code regex}, and {@code device-name} additionally records a display string for logging.
     */
    private static void parseMatchCriteria(XmlPullParser parser, DeviceInputMapping mapping)
            throws XmlPullParserException, IOException {

        final DeviceMatchCriteria criteria = new DeviceMatchCriteria();
        children(parser, "match", (p, tag) -> {
            final DeviceMatchCriteria.Field field = DeviceMatchCriteria.Field.of(tag);
            if (field == null) return;
            final String exact = attr(p, "exact");
            final String regex = attr(p, "regex");
            if (exact == null && regex == null) return;
            criteria.set(field, exact != null ? DeviceMatchCriteria.exact(exact)
                                              : DeviceMatchCriteria.regex(regex));
            if (field == DeviceMatchCriteria.Field.DEVICE_NAME) {
                mapping.deviceName = exact != null ? exact : "regex:" + regex;  // For logging
            }
        });

        if (criteria.hasAnyCriteria()) {
            mapping.matchCriteria = criteria;
        }
    }

    // ── <input-mappings> ─────────────────────────────────────────────────────

    private static void parseInputMappings(XmlPullParser parser, DeviceInputMapping mapping)
            throws XmlPullParserException, IOException {
        children(parser, "input-mappings", (p, tag) -> {
            switch (tag) {
                case "alt-mappings":
                    parseAltMappings(p, mapping);
                    break;
                case "keypad-type":
                    mapping.keypadType = p.nextText();
                    break;
                case "layout-overrides":
                    overrides(p, tag, "from", "to", mapping.layoutOverrides);
                    break;
                case "scancode-mappings":
                    parseScancodeMappings(p, mapping);
                    break;
                case "layout-alt-overrides":
                    overrides(p, tag, "layout", "alt-mappings", mapping.layoutAltOverrides);
                    break;
                default:
                    break;
            }
        });
    }

    /** {@code <override a=".." b=".."/>} children into a map; an entry missing either half is dropped. */
    private static void overrides(XmlPullParser parser, String end, String keyAttr,
                                  String valueAttr, Map<String, String> out)
            throws XmlPullParserException, IOException {
        children(parser, end, (p, tag) -> {
            if (!"override".equals(tag)) return;
            final String key = attr(p, keyAttr);
            final String value = attr(p, valueAttr);
            if (key != null && value != null) {
                out.put(key, value);
            }
        });
    }

    private static void parseAltMappings(XmlPullParser parser, DeviceInputMapping mapping)
            throws XmlPullParserException, IOException {
        mapping.mappingType = DeviceInputMapping.InputMappingType.CUSTOM_ALT_MAPPINGS;
        children(parser, "alt-mappings", (p, tag) -> {
            if ("source".equals(tag)) {
                mapping.altMappingsFile = p.nextText();
            } else if ("enabled".equals(tag) && "false".equals(p.nextText())) {
                mapping.mappingType = DeviceInputMapping.InputMappingType.DEFAULT;
            }
        });
    }

    /**
     * Parse scancode-mappings section.
     * Each <key> element maps a raw scancode/keycode to a role and optional attributes.
     */
    private static void parseScancodeMappings(XmlPullParser parser, DeviceInputMapping mapping)
            throws XmlPullParserException, IOException {
        children(parser, "scancode-mappings", (p, tag) -> {
            if (!"key".equals(tag)) return;
            final ScancodeMapping scMapping = parseSingleScancodeMapping(p);
            if (scMapping != null) {
                mapping.scancodeMappings.add(scMapping);
            }
        });

        if (!mapping.scancodeMappings.isEmpty()) {
            Logger.info(TAG, "Parsed " + mapping.scancodeMappings.size()
                    + " scancode mappings for device: " + mapping.deviceName);
        }
    }

    /**
     * Parse a single <key> element within <scancode-mappings>.
     *
     * Attributes:
     *   rawScanCode  — int, optional
     *   rawKeyCode   — int, optional
     *   treatAs      — string (KEYCODE_* name), optional
     *   role         — string (KeyRole name), required
     *   altChar      — single character, optional
     *   board        — int (board ID), optional
     *   default-action — string (MULTIFUNCTION action id, e.g. "emoji_board"), optional
     *   notes        — string, optional
     */
    private static ScancodeMapping parseSingleScancodeMapping(XmlPullParser parser) {
        final String roleStr = attr(parser, "role");
        if (roleStr == null || roleStr.isEmpty()) {
            Logger.error(TAG, "Scancode mapping missing required 'role' attribute");
            return null;
        }
        final KeyRole role = KeyRole.fromString(roleStr);
        if (role == null) {
            Logger.error(TAG, "Scancode mapping has unrecognized role: " + roleStr);
            return null;
        }

        // At least one of rawScanCode or rawKeyCode must be specified
        final String rawScanStr = attr(parser, "rawScanCode");
        final String rawKeyStr = attr(parser, "rawKeyCode");
        if (rawScanStr == null && rawKeyStr == null) {
            Logger.error(TAG, "Scancode mapping must specify at least rawScanCode or rawKeyCode");
            return null;
        }

        final ScancodeMapping mapping = new ScancodeMapping();
        mapping.role = role;
        mapping.treatAs = attr(parser, "treatAs");
        mapping.defaultAction = attr(parser, "default-action");
        mapping.notes = attr(parser, "notes");

        try {
            if (rawScanStr != null) mapping.rawScanCode = Integer.parseInt(rawScanStr);
            if (rawKeyStr != null) mapping.rawKeyCode = Integer.parseInt(rawKeyStr);
        } catch (NumberFormatException e) {
            Logger.error(TAG, "Invalid rawScanCode/rawKeyCode: " + rawScanStr + "/" + rawKeyStr);
            return null;
        }

        final String altCharStr = attr(parser, "altChar");
        if (altCharStr != null && !altCharStr.isEmpty()) {
            mapping.altChar = altCharStr.charAt(0);
        }

        final String boardStr = attr(parser, "board");
        if (boardStr != null) {
            try {
                mapping.boardId = Integer.parseInt(boardStr);
            } catch (NumberFormatException e) {
                Logger.error(TAG, "Invalid board ID: " + boardStr);
            }
        }

        return mapping;
    }

    // ── <settings-overrides> and <ckb-key-grid> ──────────────────────────────

    /**
     * Parse a single setting override.
     */
    private static DeviceSettingOverride parseSettingOverride(XmlPullParser parser) {
        final String key = attr(parser, "key");
        final String typeStr = attr(parser, "type");
        if (key == null || typeStr == null) {
            return null;  // Invalid setting
        }
        final String forcedValueStr = attr(parser, "forced-value");

        final DeviceSettingOverride override = new DeviceSettingOverride();
        override.key = key;

        switch (typeStr.toLowerCase(Locale.ROOT)) {
            case "boolean":
                override.type = DeviceSettingOverride.SettingType.BOOLEAN;
                if (forcedValueStr != null) {
                    override.forcedValue = "true".equals(forcedValueStr);
                }
                break;
            case "integer":
                override.type = DeviceSettingOverride.SettingType.INTEGER;
                if (forcedValueStr != null) {
                    try {
                        override.forcedValue = Integer.parseInt(forcedValueStr);
                    } catch (NumberFormatException e) {
                        Logger.error(TAG, "Invalid integer value for setting " + key + ": " + forcedValueStr);
                    }
                }
                break;
            case "string":
                override.type = DeviceSettingOverride.SettingType.STRING;
                override.forcedValue = forcedValueStr;
                break;
            case "list":
                override.type = DeviceSettingOverride.SettingType.LIST;
                override.forcedValue = forcedValueStr;
                break;
            default:
                Logger.error(TAG, "Unknown setting type: " + typeStr);
                return null;
        }

        override.readOnly = "true".equals(attr(parser, "read-only"));
        override.hidden = "true".equals(attr(parser, "hidden"));
        return override;
    }

    /**
     * Parse a {@code <ckb-key-grid width=".." height="..">} block of {@code <key>} cells
     * (label + normalized cx/cy/w/h). Used to inject per-device CKB geometry.
     */
    private static CkbKeyGridConfig parseCkbKeyGrid(XmlPullParser parser)
            throws XmlPullParserException, IOException {

        final CkbKeyGridConfig grid = new CkbKeyGridConfig();
        grid.width = intAttr(parser, "width", 0);
        grid.height = intAttr(parser, "height", 0);

        children(parser, "ckb-key-grid", (p, tag) -> {
            if (!"key".equals(tag)) return;
            final String label = attr(p, "label");
            if (label != null && !label.isEmpty()) {
                grid.cells.add(new CkbKeyGridConfig.Cell(label,
                        floatAttr(p, "cx", 0f), floatAttr(p, "cy", 0f),
                        floatAttr(p, "w", 0f), floatAttr(p, "h", 0f)));
            }
        });

        if (!grid.cells.isEmpty()) {
            Logger.info(TAG, "Parsed CKB key grid: " + grid.cells.size() + " cells");
        }
        return grid.cells.isEmpty() ? null : grid;
    }
}
