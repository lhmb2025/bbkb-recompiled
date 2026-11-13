package dev.bbkb.ime.core.device.config.model;

import android.util.SparseIntArray;

/**
 * Alt+key characters and special function key remaps loaded from XML. Brand/layout gating is done
 * by the device config ({@code <layout-alt-overrides>} plus {@code <brand>}), not here.
 */
public class AltMappingsTable {

    private final String name;
    // SparseIntArray, not SparseArray<Character>: getMapping is on the hot Alt+key path.
    private final SparseIntArray mappings;          // keyCode → character (with Alt)
    private final SparseIntArray specialFunctions;  // keyCode → virtual keyCode (without Alt)

    public AltMappingsTable(String name) {
        this.name = name;
        this.mappings = new SparseIntArray();
        this.specialFunctions = new SparseIntArray();
    }

    public void addMapping(int keyCode, char character) {
        mappings.put(keyCode, character);
    }

    public void addSpecialFunction(int keyCode, int virtualKeyCode) {
        specialFunctions.put(keyCode, virtualKeyCode);
    }

    /** The Alt character for {@code keyCode}, or 0. */
    public char getMapping(int keyCode) {
        return (char) mappings.get(keyCode, 0);
    }

    /** The remapped keycode without Alt, or 0. */
    public int getSpecialFunctionCode(int keyCode) {
        return specialFunctions.get(keyCode, 0);
    }

    /** {@code base} with {@code over} laid on top ({@code over} wins); either may be null. */
    public static AltMappingsTable layered(String name, AltMappingsTable base, AltMappingsTable over) {
        final AltMappingsTable out = new AltMappingsTable(name);
        for (AltMappingsTable from : new AltMappingsTable[] {base, over}) {
            if (from == null) continue;
            for (int i = 0; i < from.mappings.size(); i++) {
                out.mappings.put(from.mappings.keyAt(i), from.mappings.valueAt(i));
            }
            for (int i = 0; i < from.specialFunctions.size(); i++) {
                out.specialFunctions.put(from.specialFunctions.keyAt(i), from.specialFunctions.valueAt(i));
            }
        }
        return out;
    }

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
