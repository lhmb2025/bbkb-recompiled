package dev.bbkb.ime.keyboard.internal;

import android.text.TextUtils;

import dev.bbkb.ime.core.keyevent.ModifierState;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Alternative characters ("more keys") for a held physical key.
 *
 * <p>Three sources, queried in this order: accent/diacritic variants of the base character
 * ({@link KeyboardSwitcher#getMoreKeysForKey}), Alt-held style variants
 * ({@link KeyboardSwitcher#getMoreKeysForKeyByStyle}), and symbol-mode multitap alternates
 * ({@link Keyboard#getMultiTapAlternates}). Duplicates keep their first position.
 */
public final class MoreKeysProvider {

    /** Everything the lookup depends on. */
    public static final class MoreKeysContext {
        /** The base character that was typed (e.g., "n", "e", "-"). */
        public final String baseCharacter;

        /**
         * Current modifier state, from the one query API.
         *
         * <p>Phase 1f: this was a {@code core.device.state.MetaState}, which had become a naming
         * layer over {@link ModifierState} with two call sites — this one and the
         * {@code AuxBarManager} that fills it in. Both are the accent bar's raw-event reader, which
         * Phase 1b named as the API's first consumer and could not migrate because it did not own
         * these files. Taking the {@link ModifierState} directly removes the layer.
         */
        public final ModifierState modifiers;

        /** Whether the key is being held (repeatCount >= 1). */
        public final boolean isHolding;

        /** Whether we're in symbol mode. */
        public final boolean inSymbolMode;

        public MoreKeysContext(String baseCharacter, ModifierState modifiers,
                boolean isHolding, boolean inSymbolMode) {
            this.baseCharacter = baseCharacter;
            this.modifiers = modifiers != null ? modifiers : ModifierState.none();
            this.isHolding = isHolding;
            this.inSymbolMode = inSymbolMode;
        }

        @Override
        public String toString() {
            return "MoreKeysContext{base='" + baseCharacter + '\''
                    + ", holding=" + isHolding
                    + ", meta=" + modifiers
                    + ", symbolMode=" + inSymbolMode + '}';
        }
    }

    private final KeyboardSwitcher keyboardSwitcher;

    public MoreKeysProvider(KeyboardSwitcher keyboardSwitcher) {
        this.keyboardSwitcher = keyboardSwitcher;
    }

    /**
     * Get alternative characters for a key based on the provided context.
     *
     * @return List of alternative characters, or empty list if none available
     */
    public List<String> getMoreKeys(MoreKeysContext context) {
        if (context == null || !context.isHolding || keyboardSwitcher == null) {
            return Collections.emptyList();
        }
        // LinkedHashSet, not List.contains: this runs synchronously on the long-press timer
        // callback, i.e. while the user is holding a key waiting for the popup. Insertion order
        // is the source priority order and must be preserved.
        final Set<String> out = new LinkedHashSet<>();
        final boolean hasBase = !TextUtils.isEmpty(context.baseCharacter);

        // Accents/diacritics: not offered in symbol mode.
        if (hasBase && !context.inSymbolMode) {
            addAll(out, keyboardSwitcher.getMoreKeysForKey(context.baseCharacter));
        }
        // Alt styles: no symbol-mode guard, matching the previous StyleMoreKeysProvider.
        // isAltHeld() is what MetaState.isAltActive() delegated to for an event-only state (its two
        // other witnesses, the span and the per-key state, are only filled in by the tracker).
        if (hasBase && context.modifiers.isAltHeld()) {
            addAll(out, keyboardSwitcher.getMoreKeysForKeyByStyle(context.baseCharacter));
        }
        // Symbol multitap alternates: no base-empty guard; getMultiTapAlternates tolerates null.
        if (context.inSymbolMode) {
            final Keyboard keyboard = keyboardSwitcher.getCurrentKeyboard();
            if (keyboard != null) {
                addAll(out, keyboard.getMultiTapAlternates(context.baseCharacter));
            }
        }
        return out.isEmpty() ? Collections.<String>emptyList() : new ArrayList<>(out);
    }

    private static void addAll(Set<String> out, String[] moreKeys) {
        if (moreKeys != null) {
            Collections.addAll(out, moreKeys);
        }
    }
}
