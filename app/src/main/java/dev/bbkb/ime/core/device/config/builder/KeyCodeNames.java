package dev.bbkb.ime.core.device.config.builder;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code keyCode -> KEYCODE_* name}, reflected off {@code KeyEvent}'s constants (a framework
 * class, never shrunk). Not {@link KeyEvent#keyCodeToString}, which is native and returns the
 * number off-device, so tests would see different XML than the handset.
 */
public final class KeyCodeNames {

    @Nullable private static volatile Map<Integer, String> sNames;

    private KeyCodeNames() {}

    /** The {@code KEYCODE_*} name for {@code keyCode}, or null for 0 and for unknown codes. */
    @Nullable
    public static String of(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return null;
        }
        return names().get(keyCode);
    }

    private static Map<Integer, String> names() {
        Map<Integer, String> names = sNames;
        if (names != null) {
            return names;
        }
        names = new HashMap<>(300);
        try {
            for (Field field : KeyEvent.class.getFields()) {
                if (!field.getName().startsWith("KEYCODE_")) continue;
                if (field.getType() != int.class) continue;
                if (!Modifier.isStatic(field.getModifiers())) continue;
                final int value = field.getInt(null);
                final String existing = names.get(value);
                // Deterministic if two names share a code: shorter, then alphabetically first.
                if (existing == null || existing.length() > field.getName().length()
                        || (existing.length() == field.getName().length()
                            && field.getName().compareTo(existing) < 0)) {
                    names.put(value, field.getName());
                }
            }
        } catch (Throwable t) {
            // An empty table makes callers fall back to the step's default name.
        }
        sNames = names;
        return names;
    }
}
