package dev.bbkb.ime.core.device.config.parser;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.view.KeyEvent;

import dev.bbkb.ime.core.device.config.model.AltMappingsTable;

import org.xmlpull.v1.XmlPullParser;

import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.BuildConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses Alt+key mappings from XML resource files.
 * 
 * Expected XML format:
 * <alt-key-mappings>
 *     <mapping keycode="V" char=";" description="Alt+V → ;"/>
 *     ...
 * </alt-key-mappings>
 */
public class AltMappingsParser {
    
    private static final String TAG = "AltMappingsParser";

    /**
     * Parsed tables, keyed by resource name. Was a separate AltMappingsCache class occupying a
     * package slot of its own; it is the parser's own memo and lives with the parser.
     */
    private static final Map<String, AltMappingsTable> sCache = new HashMap<>();

    /**
     * Alt+key mappings for a resource name, parsing the XML only the first time it is asked for.
     *
     * <p>Called on the key-event path, so it must not re-parse per event.
     *
     * @param context Application context
     * @param resourceName XML resource name (without .xml)
     * @return the table, or null when the resource is missing or unparseable
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

    /** Drops every memoized table, so the next lookup re-parses. Used when the config reloads. */
    public static synchronized void clearMappingsCache() {
        sCache.clear();
    }

    /**
     * Parse Alt+key mappings from an XML resource.
     * 
     * @param context Application context
     * @param resourceName Resource name (without .xml extension)
     * @return AltMappingsTable with loaded mappings, or null on error
     */
    public static AltMappingsTable parseMappings(Context context, String resourceName) {
        try {
            // Get resource ID from name
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
    
    /**
     * Parse mappings from resource ID.
     */
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
                    
                    // Audit W1-F: the root element's device-brand / required-layout attributes
                    // were read into write-only fields (nothing ever called matchesConditions).
                    // The gating they describe is done structurally, by <layout-alt-overrides> in
                    // the device config plus that config's <brand exact="..."/>.
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
    
    /**
     * Parse a single <mapping> element.
     */
    private static void parseMapping(XmlResourceParser parser, AltMappingsTable table) {
        try {
            // Get attributes
            String keycodeStr = parser.getAttributeValue(null, "keycode");
            String charStr = parser.getAttributeValue(null, "char");
            
            if (keycodeStr == null || charStr == null) {
                Logger.warn(TAG, "Mapping missing required attributes: keycode or char");
                return;
            }
            
            // Convert keycode string to KeyEvent constant
            int keyCode = keycodeStringToInt(keycodeStr);
            if (keyCode == -1) {
                Logger.warn(TAG, "Invalid keycode: " + keycodeStr);
                return;
            }
            
            // Get first character from string
            char character = charStr.length() > 0 ? charStr.charAt(0) : 0;
            if (character == 0) {
                Logger.warn(TAG, "Empty character for keycode: " + keycodeStr);
                return;
            }
            
            // Add to table
            table.addMapping(keyCode, character);
            
        } catch (Exception e) {
            Logger.errorWithException(TAG, e, "Error parsing mapping element");
        }
    }
    
    // Special function name to virtual keycode mapping
    private static final int FUNCTION_CODE_VOICE = 7;
    private static final int FUNCTION_CODE_EMOJI = 8888;
    
    /**
     * Parse a single <special-function> element for keycode remapping.
     * 
     * Format: <special-function keycode="667" function="voice" description="..."/>
     * 
     * Valid function names: "voice", "emoji"
     */
    private static void parseSpecialFunction(XmlResourceParser parser, AltMappingsTable table) {
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
    
    /**
     * Convert function name to virtual keycode.
     * @return virtual keycode, or -1 if unknown function name
     */
    private static int functionNameToCode(String functionName) {
        if (functionName == null) return -1;
        // Locale.ROOT: in a Turkish locale "VOICE".toLowerCase() yields a dotless i, the
        // switch falls through to -1, and <special-function function="VOICE"> is dropped.
        switch (functionName.toLowerCase(java.util.Locale.ROOT)) {
            case "voice":
                return FUNCTION_CODE_VOICE;
            case "emoji":
                return FUNCTION_CODE_EMOJI;
            default:
                return -1;
        }
    }
    
    /**
     * Convert keycode string (e.g., "V", "COMMA", "666") to KeyEvent constant.
     * Supports both named constants and numeric keycodes.
     */
    private static int keycodeStringToInt(String keycodeStr) {
        try {
            // First, try to parse as a direct integer (for custom keycodes like 666, 667)
            try {
                int numericKeyCode = Integer.parseInt(keycodeStr);
                if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Parsed numeric keycode: " + numericKeyCode);
                return numericKeyCode;
            } catch (NumberFormatException e) {
                // Not a number, continue to named constant parsing
            }
            
            // Try as full constant name (e.g., "KEYCODE_V")
            if (keycodeStr.startsWith("KEYCODE_")) {
                return toKeyCodeOrInvalid(keycodeStr);
            }
            
            // Try with KEYCODE_ prefix
            String fullName = "KEYCODE_" + keycodeStr.toUpperCase(java.util.Locale.ROOT);
            return toKeyCodeOrInvalid(fullName);
            
        } catch (Exception e) {
            Logger.warn(TAG, "Failed to parse keycode: " + keycodeStr);
            return -1;
        }
    }

    /**
     * {@link KeyEvent#keyCodeFromString} returns {@link KeyEvent#KEYCODE_UNKNOWN} (0), never -1,
     * for a name it does not recognise. Both callers of {@link #keycodeStringToInt} test for -1,
     * so without this a typo such as {@code keycode="KEYCODE_TYPO"} silently installed a mapping
     * on keycode 0 instead of tripping the "Invalid keycode" warning.
     */
    private static int toKeyCodeOrInvalid(String fullName) {
        final int keyCode = KeyEvent.keyCodeFromString(fullName);
        return keyCode == KeyEvent.KEYCODE_UNKNOWN ? -1 : keyCode;
    }
}
