package dev.bbkb.ime.core.keyevent;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.AltMappingsTable;
import dev.bbkb.ime.core.layout.PhysicalKeyResolver;

/**
 * Resolves Alt+key characters. Tiers, first hit wins:
 * 0. Letter layouts ({@link PhysicalKeyResolver#alt}): the user's own bound layout, then the built-in one
 * 1. AltMappingsTable (device XML config)
 * 2. The active config's {@code <layout-alt-overrides>}, for firmware with one KCM for every layout
 * 3. System KCM via getUnicodeChar(META_ALT_ON)
 * Tier 0 is read per call so the cached resolver survives layout switches, and is the only tier that can
 * return a non-BMP character (read {@link Result#codePoint}). Immutable and thread-safe once built.
 */
public final class AuxCharacterResolver {

    private static final String TAG = "AuxCharResolver";

    /** Result of a character resolution attempt. */
    public static final class Result {
        /** The character as a code point; 0 for none. */
        public final int codePoint;
        /** As a {@code char}; 0 for none or for a non-BMP {@link #codePoint}. */
        public final char character;
        public final String source;

        private Result(int codePoint, String source) {
            this.codePoint = codePoint;
            this.character = Character.isBmpCodePoint(codePoint) ? (char) codePoint : (char) 0;
            this.source = source;
        }

        public static Result of(char c, String source) {
            return new Result(c, source);
        }

        public static Result ofCodePoint(int codePoint, String source) {
            return new Result(codePoint, source);
        }

        public static Result none() {
            return new Result(0, "none");
        }

        public boolean hasCharacter() {
            return codePoint != 0;
        }

        /** The character as a string, or "" for none. */
        public String text() {
            return hasCharacter() ? new String(Character.toChars(codePoint)) : "";
        }

        @Override
        public String toString() {
            if (!hasCharacter()) return "Result{none}";
            return "Result{'" + text() + "' (0x" + Integer.toHexString(codePoint) + "), source=" + source + "}";
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

    /** Without an event there is no KCM tier, so this can return none where the event overload would not. */
    public Result resolve(int keyCode) {
        // No event, so no scan code to narrow by.
        int user = PhysicalKeyResolver.alt(keyCode, -1);
        if (user != 0) {
            return Result.ofCodePoint(user, "user");
        }

        if (altMappingsTable != null) {
            char c = altMappingsTable.getMapping(keyCode);
            if (c != 0) {
                return Result.of(c, "xml");
            }
        }

        char c = resolveLayoutOverride(keyCode);
        if (c != 0) {
            return Result.of(c, "layout_override");
        }

        return Result.none();
    }

    /** Full chain, with COMBINING_ACCENT filtering. */
    public Result resolve(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();

        int user = PhysicalKeyResolver.alt(event);
        if (user != 0) {
            return Result.ofCodePoint(user, "user");
        }

        if (altMappingsTable != null) {
            char c = altMappingsTable.getMapping(keyCode);
            if (c != 0) {
                return Result.of(c, "xml");
            }
        }

        char c = resolveLayoutOverride(keyCode);
        if (c != 0) {
            return Result.of(c, "layout_override");
        }

        int uc = event.getUnicodeChar(KeyEvent.META_ALT_ON);
        if (filterCombiningAccent && (uc & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            uc = uc & KeyCharacterMap.COMBINING_ACCENT_MASK;
        }
        if (uc > 0 && Character.isValidCodePoint(uc)) {
            return Result.ofCodePoint(uc, "kcm");
        }

        return Result.none();
    }

    /** 0 when the config declares no table, which is true for every shipped config. */
    private char resolveLayoutOverride(int keyCode) {
        return layoutOverridesTable != null ? layoutOverridesTable.getMapping(keyCode) : 0;
    }

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
