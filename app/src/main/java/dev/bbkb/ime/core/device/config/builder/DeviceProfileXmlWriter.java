package dev.bbkb.ime.core.device.config.builder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.KeyRole;

import java.util.Locale;

/**
 * Serialises a {@link DeviceProfileDraft} to schema-v2.3 device-config XML.
 *
 * <p>A pure string function, deliberately: it is the half of export that a unit test can pin
 * exactly, and {@link DeviceProfileExporter} refuses to write anything this produces until
 * {@code DeviceInputMappingParser} has read it back. Nothing here is allowed to assume the parser
 * is lenient.
 *
 * <p>The header comment is the part a human reads when the file arrives on someone else's phone.
 * It carries the app version, the date and the ROM build id, plus the detected facts the config
 * itself cannot express — vendor/product ids, whether the keychar map had an Alt layer, the
 * auto-repeat cadence measured while a key was held. On a device whose Sym keycode changed
 * between two ROMs (the MP01), the ROM id in that header is the difference between a file that is
 * still true and one that is not.
 */
public final class DeviceProfileXmlWriter {

    /** The schema level this writer emits. */
    public static final String SCHEMA_VERSION = "2.3";

    private static final String NL = "\n";

    private DeviceProfileXmlWriter() {} // No instantiation

    @NonNull
    public static String toXml(@NonNull DeviceProfileDraft draft) {
        final DeviceFacts facts = draft.facts;
        final StringBuilder sb = new StringBuilder(2048);

        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>").append(NL);
        appendHeaderComment(sb, draft);
        sb.append(NL);
        sb.append("<device-input-config version=\"").append(SCHEMA_VERSION)
                .append("\" name=\"").append(attr(draft.name)).append("\">").append(NL);
        sb.append(NL);
        sb.append("    <device>").append(NL);

        appendMatch(sb, facts);

        sb.append(NL);
        sb.append("        <device-type>").append(facts.deviceType()).append("</device-type>")
                .append(NL);

        appendKeypadLayout(sb, facts);
        appendScancodeMappings(sb, draft);

        sb.append("    </device>").append(NL);
        sb.append(NL);
        sb.append("</device-input-config>").append(NL);
        return sb.toString();
    }

    // ── header ───────────────────────────────────────────────────────────────

    private static void appendHeaderComment(StringBuilder sb, DeviceProfileDraft draft) {
        final DeviceFacts facts = draft.facts;
        sb.append("<!--").append(NL);
        sb.append("    Device Configuration: ").append(comment(draft.name)).append(NL);
        sb.append("    Schema v").append(SCHEMA_VERSION).append(NL);
        sb.append(NL);
        sb.append("    Built on the device by the profile builder.").append(NL);
        sb.append("    App: ").append(comment(facts.appVersion)).append(NL);
        sb.append("    Date: ").append(comment(draft.exportDate)).append(NL);
        sb.append("    ROM: ").append(comment(facts.romDisplayId))
                .append(" (Android ").append(comment(facts.androidRelease))
                .append(", SDK ").append(facts.sdkInt).append(")").append(NL);
        sb.append("    Build: DEVICE=").append(comment(facts.buildDevice))
                .append(" MODEL=").append(comment(facts.buildModel))
                .append(" MANUFACTURER=").append(comment(facts.buildManufacturer))
                .append(" BRAND=").append(comment(facts.buildBrand)).append(NL);

        for (DeviceFacts.Keyboard keyboard : facts.keyboards) {
            sb.append("    Keyboard: ").append(comment(keyboard.name))
                    .append(" [id=").append(keyboard.deviceId)
                    .append(", vendor=").append(hex(keyboard.vendorId))
                    .append(", product=").append(hex(keyboard.productId))
                    .append(", sources=0x").append(Integer.toHexString(keyboard.sources))
                    .append(", keyboardType=").append(keyboard.keyboardType)
                    .append(", touch=").append(keyboard.hasTouch)
                    .append(", altLayer=").append(keyboard.hasAltLayer)
                    .append("]").append(NL);
        }
        if (facts.keyboards.isEmpty()) {
            sb.append("    Keyboard: none reported a SOURCE_KEYBOARD InputDevice").append(NL);
        }

        sb.append("    Keypad layout: ").append(comment(facts.keypadLayout))
                .append(" (source ").append(facts.keypadLayoutSource).append(")").append(NL);
        sb.append("    Touch keypad: ").append(facts.hasTouchKeypad).append(NL);
        sb.append("    Matched shipped config: ")
                .append(facts.matchedConfigName == null ? "none" : comment(facts.matchedConfigName))
                .append(NL);

        sb.append(NL);
        sb.append("    Captured keys (raw, as this hardware reported them):").append(NL);
        if (draft.capturedKeys.isEmpty()) {
            sb.append("      (none)").append(NL);
        }
        for (CapturedKey key : draft.capturedKeys) {
            sb.append("      ").append(key.step)
                    .append(": scanCode=").append(key.scanCode)
                    .append(" keyCode=").append(key.keyCode)
                    .append(" (").append(comment(keyCodeName(key.keyCode))).append(")")
                    .append(" deviceId=").append(key.deviceId)
                    .append(" repeat=").append(key.hasRepeatCadence()
                            ? key.repeatIntervalMs + "ms x" + key.repeatCount
                            : "not held")
                    .append(NL);
        }
        sb.append("-->").append(NL);
    }

    // ── <match> ──────────────────────────────────────────────────────────────

    /**
     * Both criteria are ANDed by {@code DeviceMatchCriteria}, which is what is wanted here: the
     * keypad's {@code InputDevice} name is the precise identifier (it is what selects the vendor
     * {@code .idc}/{@code .kl} in the first place), and {@code Build.DEVICE} keeps the config from
     * claiming some unrelated handset whose vendor happened to use the same keypad part.
     */
    private static void appendMatch(StringBuilder sb, DeviceFacts facts) {
        sb.append("        <match>").append(NL);
        final String deviceName = facts.matchDeviceName();
        if (deviceName != null && !deviceName.isEmpty()) {
            sb.append("            <device-name exact=\"").append(attr(deviceName))
                    .append("\"/>").append(NL);
        } else {
            sb.append("            <!-- No physical keyboard InputDevice was present at capture")
                    .append(" time; matching on Build.DEVICE alone. -->").append(NL);
        }
        if (!facts.buildDevice.isEmpty()) {
            sb.append("            <build-device exact=\"").append(attr(facts.buildDevice))
                    .append("\"/>").append(NL);
        }
        sb.append("        </match>").append(NL);
    }

    // ── <keypad-layout> ──────────────────────────────────────────────────────

    private static void appendKeypadLayout(StringBuilder sb, DeviceFacts facts) {
        sb.append(NL);
        if (facts.shouldDeclareKeypadLayout()) {
            sb.append("        <!-- Declared because no firmware source answered: the detected")
                    .append(" layout came from ").append(facts.keypadLayoutSource)
                    .append(", which ranks below the keypad device name, the")
                    .append(" ro.*.keypadlanguage property and the KeyCharacterMap fingerprint.")
                    .append(" -->").append(NL);
            sb.append("        <keypad-layout>").append(attr(facts.keypadLayout))
                    .append("</keypad-layout>").append(NL);
        } else {
            sb.append("        <!-- No <keypad-layout> here, deliberately: the firmware answered")
                    .append(" (").append(facts.keypadLayout).append(" via ")
                    .append(facts.keypadLayoutSource).append(") and declaring it would pin that")
                    .append(" one answer for every unit this config matches, including QWERTZ and")
                    .append(" AZERTY units. -->").append(NL);
        }
    }

    // ── <scancode-mappings> ──────────────────────────────────────────────────

    private static void appendScancodeMappings(StringBuilder sb, DeviceProfileDraft draft) {
        sb.append(NL);
        sb.append("        <input-mappings>").append(NL);
        sb.append("            <scancode-mappings>").append(NL);
        if (draft.capturedKeys.isEmpty()) {
            sb.append("                <!-- No keys were captured. -->").append(NL);
        }
        for (CapturedKey key : draft.capturedKeys) {
            appendKey(sb, key);
        }
        sb.append("            </scancode-mappings>").append(NL);
        sb.append("        </input-mappings>").append(NL);
    }

    /**
     * One {@code <key>} element.
     *
     * <p>Both {@code rawScanCode} and {@code rawKeyCode} are written whenever both were observed,
     * which makes the match an AND of the two — the precise identity of the key on this ROM. A
     * device that reports one of them as 0 (a scancode of 0 is common on a virtual or remapped
     * device) gets only the half that is real, because a literal {@code 0} would be matched
     * against every event whose other half was unset.
     */
    private static void appendKey(StringBuilder sb, CapturedKey key) {
        final CaptureStep step = key.step;
        sb.append("                <key");
        if (key.scanCode > 0) {
            sb.append(" rawScanCode=\"").append(key.scanCode).append("\"");
        }
        if (key.keyCode > 0) {
            sb.append(" rawKeyCode=\"").append(key.keyCode).append("\"");
        }
        sb.append(" role=\"").append(step.role().name()).append("\"");

        final String treatAs = step.treatAsFor(key.keyCode);
        if (treatAs != null) {
            sb.append(NL).append("                     treatAs=\"").append(attr(treatAs)).append("\"");
        }
        if (step.boardId() != 0) {
            sb.append(" board=\"").append(step.boardId()).append("\"");
        }
        final String defaultAction = step.defaultAction();
        if (defaultAction != null) {
            sb.append(NL).append("                     default-action=\"")
                    .append(attr(defaultAction)).append("\"");
        }
        sb.append(NL).append("                     notes=\"").append(attr(notesFor(key)))
                .append("\"/>").append(NL);
    }

    @NonNull
    private static String notesFor(CapturedKey key) {
        final StringBuilder notes = new StringBuilder("captured on device: ")
                .append(key.step.name().toLowerCase(Locale.ROOT)).append(" key");
        if (key.step.role() == KeyRole.MULTIFUNCTION) {
            notes.append(" (user-mappable; default-action is the out-of-box choice)");
        }
        return notes.toString();
    }

    // ── escaping ─────────────────────────────────────────────────────────────

    /**
     * Escapes an attribute value. A device name is not a safe string: it comes from the kernel and
     * is whatever the vendor put in the driver, which on shipping hardware has included ampersands
     * and quotes.
     */
    @NonNull
    public static String attr(@Nullable String value) {
        if (value == null) return "";
        final StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                default:
                    // Control characters are not representable in XML 1.0 at all, escaped or not.
                    if (c < 0x20 && c != '\t') {
                        out.append(' ');
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /**
     * Makes a value safe to drop inside an XML comment. Entities do not apply there, so the only
     * hazards are a literal {@code --} (illegal in a comment) and a trailing {@code -}.
     */
    @NonNull
    public static String comment(@Nullable String value) {
        if (value == null || value.isEmpty()) return "";
        String out = value.replace("--", "- -").replace("<!", "< !").replace("\n", " ")
                .replace("\r", " ");
        if (out.endsWith("-")) {
            out = out.substring(0, out.length() - 1) + "- ";
        }
        return out;
    }

    @NonNull
    private static String hex(int value) {
        return String.format(Locale.ROOT, "0x%04x", value);
    }

    @NonNull
    private static String keyCodeName(int keyCode) {
        final String name = KeyCodeNames.of(keyCode);
        return name != null ? name : "no keycode";
    }
}
