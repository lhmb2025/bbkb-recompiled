package dev.bbkb.ime.keyboard.internal;

import android.text.TextUtils;

import dev.bbkb.ime.core.keyevent.ModifierState;
import dev.bbkb.ime.core.layout.PhysicalKeyResolver;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Alternative characters ("more keys") for a held physical key, from three sources in order:
 * accent variants ({@link KeyboardSwitcher#getMoreKeysForKey}), Alt-held style variants
 * ({@link KeyboardSwitcher#getMoreKeysForKeyByStyle}), and in symbol mode
 * {@link PhysicalKeyResolver#moreKeys}. Duplicates keep their first position.
 */
public final class MoreKeysProvider {
    public static final class MoreKeysContext {
        /** The base character that was typed (e.g. "n", "-"). */
        public final String baseCharacter;

        public final ModifierState modifiers;

        /** repeatCount >= 1. */
        public final boolean isHolding;

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

    public List<String> getMoreKeys(MoreKeysContext context) {
        if (context == null || !context.isHolding || keyboardSwitcher == null) {
            return Collections.emptyList();
        }
        // LinkedHashSet: runs on the long-press timer while the user waits, and insertion
        // order is the source priority.
        final Set<String> out = new LinkedHashSet<>();
        final boolean hasBase = !TextUtils.isEmpty(context.baseCharacter);

        // Accents/diacritics: not offered in symbol mode.
        if (hasBase && !context.inSymbolMode) {
            addAll(out, keyboardSwitcher.getMoreKeysForKey(context.baseCharacter));
        }
        // Alt styles: offered in symbol mode too.
        if (hasBase && context.modifiers.isAltHeld()) {
            addAll(out, keyboardSwitcher.getMoreKeysForKeyByStyle(context.baseCharacter));
        }
        // What the symbol page typed (e.g. the currency list); the resolver tolerates an empty base.
        if (context.inSymbolMode) {
            addAll(out, PhysicalKeyResolver.moreKeys(context.baseCharacter));
        }
        return out.isEmpty() ? Collections.<String>emptyList() : new ArrayList<>(out);
    }

    private static void addAll(Set<String> out, String[] moreKeys) {
        if (moreKeys != null) {
            Collections.addAll(out, moreKeys);
        }
    }
}
