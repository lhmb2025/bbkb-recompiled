package dev.bbkb.ime.core.device.config.model;

import android.util.SparseIntArray;

/**
 * Holds Alt+key character mappings and special function key remappings loaded from XML.
 * Uses SparseIntArray for efficient keyCode → character lookup.
 *
 * <p>Audit W1-F: the {@code List<AltKeyMapping> allMappings} shadow list (and the
 * {@code AltKeyMapping} value type it held) existed only for {@code getAllMappings()}, which had
 * no callers — every real lookup goes through the two SparseIntArrays. The
 * {@code requiredDeviceBrand} / {@code requiredLayout} condition attributes went the same way:
 * the parser wrote them and nothing ever called {@code matchesConditions}. The gating those
 * attributes describe is performed structurally, by {@code <layout-alt-overrides>} in the device
 * config plus that config's {@code <brand exact="..."/>}.
 */
public class AltMappingsTable {

    private final String name;
    // SparseIntArray, not SparseArray<Character>: getMapping is on the Alt+key path
    // (AuxCharacterResolver, KeyEventConverter) and a boxed read plus unbox per lookup is
    // wasted on a primitive char. The sibling specialFunctions field already had it right.
    private final SparseIntArray mappings;          // keyCode → character (with Alt)
    private final SparseIntArray specialFunctions;  // keyCode → virtual keyCode (without Alt)

    public AltMappingsTable(String name) {
        this.name = name;
        this.mappings = new SparseIntArray();
        this.specialFunctions = new SparseIntArray();
    }

    /**
     * Add an Alt+key character mapping.
     */
    public void addMapping(int keyCode, char character) {
        mappings.put(keyCode, character);
    }

    /**
     * Add a special function keycode remapping (hardware keycode → virtual keycode).
     * @param keyCode The hardware keycode
     * @param virtualKeyCode The virtual keycode to remap to
     */
    public void addSpecialFunction(int keyCode, int virtualKeyCode) {
        specialFunctions.put(keyCode, virtualKeyCode);
    }

    /**
     * Get the mapped character for a keyCode (when Alt is pressed).
     * @return The character, or 0 if no mapping exists
     */
    public char getMapping(int keyCode) {
        return (char) mappings.get(keyCode, 0);
    }

    /**
     * Get the virtual keycode for hardware keycode remapping (when Alt is NOT pressed).
     * @return The virtual keycode, or 0 if no remapping exists
     */
    public int getSpecialFunctionCode(int keyCode) {
        return specialFunctions.get(keyCode, 0);
    }

    /**
     * Get the number of mappings.
     */
    public int size() {
        return mappings.size();
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "AltMappingsTable{name='" + name + "', size=" + size() + '}';
    }
}
