package dev.bbkb.ime.core.keyevent;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.AltMappingsTable;

/**
 * Consolidated resolver for Alt+key character mappings.
 *
 * Priority order:
 * 1. AltMappingsTable (device XML config) — most specific
 * 2. Layout-specific overrides, from the active config's {@code <layout-alt-overrides>}
 * 3. System KCM via KeyEvent.getUnicodeChar(META_ALT_ON) — universal fallback
 *
 * <p>Tier 2 exists only for devices whose firmware ships ONE KeyCharacterMap for every keypad
 * layout. It is not one of those on a BlackBerry: the KEY2 (athena) carries
 * {@code /vendor/usr/keychars/stmpe{,_azerty,_qwertz}.kcm} with a matching {@code .kl} and
 * {@code .idc} per variant, so tier 3 already returns the right character for AZERTY and QWERTZ
 * and no shipped config declares a tier-2 table. A hardcoded AZERTY/QWERTZ table used to sit
 * below tier 2 and fire on athena in place of the correct firmware values; see the changelog.
 *
 * Thread-safe after initialization. Immutable once built.
 */
public final class AuxCharacterResolver {

    private static final String TAG = "AuxCharResolver";

    /** Result of a character resolution attempt. */
    public static final class Result {
        public final char character;
        public final String source;

        private Result(char character, String source) {
            this.character = character;
            this.source = source;
        }

        public static Result of(char c, String source) {
            return new Result(c, source);
        }

        public static Result none() {
            return new Result((char) 0, "none");
        }

        public boolean hasCharacter() {
            return character != 0;
        }

        @Override
        public String toString() {
            if (!hasCharacter()) return "Result{none}";
            return "Result{'" + character + "' (0x" + Integer.toHexString(character) + "), source=" + source + "}";
        }
    }

    @Nullable
    private final AltMappingsTable altMappingsTable;
    @Nullable
    private final AltMappingsTable layoutOverridesTable;
    private final boolean filterCombiningAccent;

    private AuxCharacterResolver(@Nullable AltMappingsTable table, @Nullable AltMappingsTable layoutOverrides,
            boolean filterCombiningAccent) {
        this.altMappingsTable = table;
        this.layoutOverridesTable = layoutOverrides;
        this.filterCombiningAccent = filterCombiningAccent;
    }

    // ===== Primary API =====

    /**
     * Resolve the alt character for a keyCode.
     * Does NOT require a KeyEvent — uses AltMappingsTable and layout overrides only.
     * Falls back to Result.none() if no mapping found (no KCM available without KeyEvent).
     */
    public Result resolve(int keyCode) {
        // Tier 1: AltMappingsTable (device XML config)
        if (altMappingsTable != null) {
            char c = altMappingsTable.getMapping(keyCode);
            if (c != 0) {
                return Result.of(c, "xml");
            }
        }

        // Tier 2: Layout-specific overrides (AZERTY/QWERTZ)
        char c = resolveLayoutOverride(keyCode);
        if (c != 0) {
            return Result.of(c, "layout_override");
        }

        // No KCM fallback without KeyEvent
        return Result.none();
    }

    /**
     * Resolve the alt character for a KeyEvent.
     * Full resolution chain: AltMappingsTable → layout overrides → system KCM.
     * Handles COMBINING_ACCENT filtering automatically.
     */
    public Result resolve(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();

        // Tier 1: AltMappingsTable (device XML config)
        if (altMappingsTable != null) {
            char c = altMappingsTable.getMapping(keyCode);
            if (c != 0) {
                return Result.of(c, "xml");
            }
        }

        // Tier 2: Layout-specific overrides (AZERTY/QWERTZ)
        char c = resolveLayoutOverride(keyCode);
        if (c != 0) {
            return Result.of(c, "layout_override");
        }

        // Tier 3: System KCM via getUnicodeChar(META_ALT_ON)
        int uc = event.getUnicodeChar(KeyEvent.META_ALT_ON);
        if (filterCombiningAccent && (uc & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            uc = uc & KeyCharacterMap.COMBINING_ACCENT_MASK;
        }
        if (uc > 0 && Character.isValidCodePoint(uc)) {
            return Result.of((char) uc, "kcm");
        }

        return Result.none();
    }

    // ===== Layout Override Logic =====

    /**
     * Resolve a layout-specific override, from the {@code <layout-alt-overrides>} table the
     * active device config named for the current keypad layout. Returns 0 when the config
     * declares no such table — which is the case for every config this app ships, so on real
     * hardware this tier is a pass-through to the KCM.
     */
    private char resolveLayoutOverride(int keyCode) {
        return layoutOverridesTable != null ? layoutOverridesTable.getMapping(keyCode) : 0;
    }

    // ===== Lifecycle =====

    /**
     * Builder for constructing the resolver.
     */
    public static final class Builder {
        private AltMappingsTable table;
        private AltMappingsTable layoutOverrides;
        private boolean filterCombiningAccent = true;

        public Builder withAltMappingsTable(@Nullable AltMappingsTable table) {
            this.table = table;
            return this;
        }

        public Builder withLayoutOverridesTable(@Nullable AltMappingsTable layoutOverrides) {
            this.layoutOverrides = layoutOverrides;
            return this;
        }

        public Builder withCombiningAccentFilter(boolean filter) {
            this.filterCombiningAccent = filter;
            return this;
        }

        public AuxCharacterResolver build() {
            return new AuxCharacterResolver(table, layoutOverrides, filterCombiningAccent);
        }
    }
}
