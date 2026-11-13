package dev.bbkb.ime.core.device.config.parser;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.view.KeyEvent;

import dev.bbkb.ime.core.device.config.model.AltMappingsTable;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;

import org.xmlpull.v1.XmlPullParser;

import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.BuildConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses Alt+key mappings from XML resources:
 * {@code <alt-key-mappings><mapping keycode="V" char=";"/>...</alt-key-mappings>}.
 */
public class AltMappingsParser {
    
    private static final String TAG = "AltMappingsParser";

    /** Parsed tables, keyed by resource name. */
    private static final Map<String, AltMappingsTable> sCache = new HashMap<>();

    /**
     * Alt+key mappings for an XML resource name (without .xml), parsed once and memoised because
     * this runs on the key-event path. Null when the resource is missing or unparseable.
     */
    public static synchronized AltMappingsTable getCachedMappings(Context context, String resourceName) {
        if (resourceName == null || resourceName.isEmpty()) {
            return null;
        }
        final AltMappingsTable cached = sCache.get(resourceName);
        if (cached != null) {
            return cached;
        }
        final AltMappingsTable table = parseMappings(context, resourceName);
        if (table != null) {
            sCache.put(resourceName, table);
        }
        return table;
    }

    /**
     * The Alt table a profile's keys use, or null when it has none: the table its
     * {@code <source>} names, with the profile's own {@code <mapping>} entries on top. A profile
     * with entries of its own and no {@code <source>} gets just those.
     *
     * <p>Called on the key-event path: the named table is memoised here by name, and the layered
     * one on the profile itself (a profile is parsed afresh whenever the configuration changes).
     */
    public static AltMappingsTable tableFor(Context context, DeviceInputMapping profile) {
        if (profile == null
                || profile.mappingType != DeviceInputMapping.InputMappingType.CUSTOM_ALT_MAPPINGS) {
            return null;
        }
        final boolean named = profile.altMappingsFile != null && !profile.altMappingsFile.isEmpty();
        final AltMappingsTable own = profile.inlineAltMappings;
        if (own == null) {
            return named ? getCachedMappings(context, profile.altMappingsFile) : null;
        }
        AltMappingsTable layered = profile.layeredAltMappings;
        if (layered == null) {
            final AltMappingsTable base = named ? getCachedMappings(context, profile.altMappingsFile) : null;
            layered = AltMappingsTable.layered(own.getName(), base, own);
            profile.layeredAltMappings = layered;
        }
        return layered;
    }

    /** Drops every memoized table, so the next lookup re-parses. Used when the config reloads. */
    public static synchronized void clearMappingsCache() {
        sCache.clear();
    }

    /** Parses an XML resource (name without .xml); null on error. Uncached. */
    public static AltMappingsTable parseMappings(Context context, String resourceName) {
        try {
            Resources resources = context.getResources();
            int resourceId = resources.getIdentifier(
                resourceName, 
                "xml", 
                context.getPackageName()
            );
            
            if (resourceId == 0) {
                Logger.error(TAG, "Alt mappings resource not found: " + resourceName);
                return null;
            }
            
            return parseMappingsFromResource(context, resourceId, resourceName);
            
        } catch (Exception e) {
            Logger.errorWithException(TAG, e, "Error parsing Alt mappings from " + resourceName);
            return null;
        }
    }

    private static AltMappingsTable parseMappingsFromResource(
            Context context, int resourceId, String name) {
        
        AltMappingsTable table = new AltMappingsTable(name);
        XmlResourceParser parser = null;
        
        try {
            parser = context.getResources().getXml(resourceId);
            int eventType = parser.getEventType();
            
            int specialFunctionCount = 0;
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();

                    // Root device-brand / required-layout attributes are ignored: that gating is
                    // done by <layout-alt-overrides> plus the device config's <brand exact="..."/>.
                    if ("mapping".equals(tagName)) {
                        parseMapping(parser, table);
                    } else if ("special-function".equals(tagName)) {
                        parseSpecialFunction(parser, table);
                        specialFunctionCount++;
                    }
                }
                eventType = parser.next();
            }
            
            Logger.info(TAG, "Loaded " + table.size() + " Alt+key mappings and "
                    + specialFunctionCount + " special function remappings from " + name);
            return table;

        } catch (Exception e) {
            Logger.errorWithException(TAG, e, "Error parsing XML resource " + name);
            return null;
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    /** Parses one {@code <mapping keycode=".." char=".."/>} element. */
    public static void parseMapping(XmlPullParser parser, AltMappingsTable table) {
        try {
            String keycodeStr = parser.getAttributeValue(null, "keycode");
            String charStr = parser.getAttributeValue(null, "char");
            
            if (keycodeStr == null || charStr == null) {
                Logger.warn(TAG, "Mapping missing required attributes: keycode or char");
                return;
            }

            int keyCode = keycodeStringToInt(keycodeStr);
            if (keyCode == -1) {
                Logger.warn(TAG, "Invalid keycode: " + keycodeStr);
                return;
            }

            char character = charStr.length() > 0 ? charStr.charAt(0) : 0;
            if (character == 0) {
                Logger.warn(TAG, "Empty character for keycode: " + keycodeStr);
                return;
            }

            table.addMapping(keyCode, character);
            
        } catch (Exception e) {
            Logger.errorWithException(TAG, e, "Error parsing mapping element");
        }
    }

    /** Virtual keycodes a {@code <special-function>} remaps a hardware key to. */
    private static final int FUNCTION_CODE_VOICE = 7;
    private static final int FUNCTION_CODE_EMOJI = 8888;
    
    /**
     * Parses {@code <special-function keycode="667" function="voice|emoji"/>}, which remaps a
     * hardware keycode to a virtual function key.
     */
    public static void parseSpecialFunction(XmlPullParser parser, AltMappingsTable table) {
        try {
            String keycodeStr = parser.getAttributeValue(null, "keycode");
            String functionStr = parser.getAttributeValue(null, "function");
            String description = parser.getAttributeValue(null, "description");
            
            if (keycodeStr == null || functionStr == null) {
                Logger.warn(TAG, "Special function missing required attributes: keycode and function");
                return;
            }
            
            int hardwareKeyCode = keycodeStringToInt(keycodeStr);
            if (hardwareKeyCode == -1) {
                Logger.warn(TAG, "Invalid keycode in special function: " + keycodeStr);
                return;
            }
            
            int virtualKeyCode = functionNameToCode(functionStr);
            if (virtualKeyCode == -1) {
                Logger.warn(TAG, "Unknown function name: " + functionStr + ". Valid names: voice, emoji");
                return;
            }
            
            table.addSpecialFunction(hardwareKeyCode, virtualKeyCode);
            if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "Added special function: " + hardwareKeyCode + 
                " → " + functionStr + 
                (description != null ? " (" + description + ")" : ""));
            }
            
        } catch (Exception e) {
            Logger.errorWithException(TAG, e, "Error parsing special-function element");
        }
    }

    /** Virtual keycode for a function name, or -1 if unknown. */
    private static int functionNameToCode(String functionName) {
        if (functionName == null) return -1;
        // Locale.ROOT: a Turkish locale lowercases "VOICE" with a dotless i and the switch misses.
        switch (functionName.toLowerCase(java.util.Locale.ROOT)) {
            case "voice":
                return FUNCTION_CODE_VOICE;
            case "emoji":
                return FUNCTION_CODE_EMOJI;
            default:
                return -1;
        }
    }

    /** KeyEvent code for "V", "COMMA", "KEYCODE_V" or a number such as "666"; -1 if invalid. */
    private static int keycodeStringToInt(String keycodeStr) {
        try {
            try {
                int numericKeyCode = Integer.parseInt(keycodeStr);
                if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Parsed numeric keycode: " + numericKeyCode);
                return numericKeyCode;
            } catch (NumberFormatException e) {
                // Not a number: fall through to named constants.
            }

            if (keycodeStr.startsWith("KEYCODE_")) {
                return toKeyCodeOrInvalid(keycodeStr);
            }

            String fullName = "KEYCODE_" + keycodeStr.toUpperCase(java.util.Locale.ROOT);
            return toKeyCodeOrInvalid(fullName);
            
        } catch (Exception e) {
            Logger.warn(TAG, "Failed to parse keycode: " + keycodeStr);
            return -1;
        }
    }

    /**
     * {@link KeyEvent#keyCodeFromString} returns KEYCODE_UNKNOWN (0) for an unknown name; callers
     * test for -1, so map 0 to -1 or a typo would install a mapping on keycode 0.
     */
    private static int toKeyCodeOrInvalid(String fullName) {
        final int keyCode = KeyEvent.keyCodeFromString(fullName);
        return keyCode == KeyEvent.KEYCODE_UNKNOWN ? -1 : keyCode;
    }
}
