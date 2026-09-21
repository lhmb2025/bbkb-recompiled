package dev.bbkb.ime.core.device.config.builder;

import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code keyCode -> "KEYCODE_…"}, for the {@code treatAs} attribute and the header comment.
 *
 * <p>Not {@link KeyEvent#keyCodeToString}: that one is a native call, so off a device — in the
 * Robolectric suite, in any JVM — it silently degrades to returning the number. A config generated
 * under test would then differ from the one the same capture produces on the handset, which makes
 * the pinned document in {@code DeviceProfileExportTest} a test of the test environment rather
 * than of the writer.
 *
 * <p>The table is reflected off {@code KeyEvent}'s own {@code KEYCODE_*} constants rather than
 * hand-written, so a keycode this app has never seen still gets its real name. The class is a
 * framework class and is never shrunk, so the reflection is safe in a release build; if it ever
 * failed, the effect is a {@code treatAs} that falls back to the step's default, which is what the
 * attribute meant anyway.
 */
public final class KeyCodeNames {

    @Nullable private static volatile Map<Integer, String> sNames;

    private KeyCodeNames() {} // No instantiation

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
                // Two names for one code (there are none today) would otherwise depend on field
                // order; the shorter, then alphabetically first, keeps it deterministic.
                if (existing == null || existing.length() > field.getName().length()
                        || (existing.length() == field.getName().length()
                            && field.getName().compareTo(existing) < 0)) {
                    names.put(value, field.getName());
                }
            }
        } catch (Throwable t) {
            // A blocked or stripped KeyEvent leaves the table empty: every caller then falls back
            // to the step's own default name, which is the behaviour this replaced.
        }
        sNames = names;
        return names;
    }
}
