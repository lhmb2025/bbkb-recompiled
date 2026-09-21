package dev.bbkb.ime.core.device.config.builder;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import dev.bbkb.ime.core.device.config.CustomDeviceConfigManager;
import dev.bbkb.ime.core.device.config.model.DeviceInputConfig;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser;
import dev.bbkb.ime.core.shared.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * Writes a built profile to the directory imported configs live in, and hands it to a share sheet.
 *
 * <p><b>Nothing is written until the parser has read it back.</b> {@link #validate} runs the
 * generated XML through {@code DeviceInputMappingParser} — the same parser that will load it on
 * the next IME start, on this device and on whoever else's the file reaches — and checks that what
 * came back is the config that went in: one device, matching this handset's keyboard name, with
 * every captured key present. A config that fails is a config that silently does nothing at
 * runtime (the parser logs and keeps going), so the failure has to surface here, while the user is
 * still standing in front of the keyboard they just captured.
 */
public final class DeviceProfileExporter {

    private static final String TAG = "DeviceProfileExporter";

    /**
     * Mirrors {@code CustomDeviceConfigManager}'s two private constants: imported configs live in
     * {@code filesDir/device_configs} and are activated by the id {@code "custom:<filename>"}.
     * Writing the profile there is what lets it be activated immediately — it becomes an entry in
     * the same list the file importer produces, with no second copy of the file anywhere.
     * {@code DeviceProfileExportTest} pins both against the manager so a rename there fails here.
     */
    public static final String CUSTOM_CONFIG_DIR = "device_configs";
    public static final String PREFIX_CUSTOM = "custom:";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private DeviceProfileExporter() {} // No instantiation

    /** The outcome of an export: either a written file and the id that activates it, or a reason. */
    public static final class Result {
        @Nullable public final File file;
        @Nullable public final String configId;
        @Nullable public final String failure;
        @NonNull public final String xml;

        private Result(@Nullable File file, @Nullable String configId, @Nullable String failure,
                       @NonNull String xml) {
            this.file = file;
            this.configId = configId;
            this.failure = failure;
            this.xml = xml;
        }

        public boolean ok() {
            return file != null && configId != null;
        }

        static Result failed(@NonNull String reason, @NonNull String xml) {
            return new Result(null, null, reason, xml);
        }

        static Result written(@NonNull File file, @NonNull String configId, @NonNull String xml) {
            return new Result(file, configId, null, xml);
        }
    }

    // ── validation ───────────────────────────────────────────────────────────

    /**
     * Parses {@code xml} the way the app will and checks it says what the draft meant.
     *
     * @return null when the round trip is clean, or a human-readable reason it is not
     */
    @Nullable
    public static String validate(@NonNull String xml, @NonNull DeviceProfileDraft draft) {
        final DeviceInputConfig parsed = DeviceInputMappingParser.parseConfigFromStream(
                new ByteArrayInputStream(xml.getBytes(UTF_8)));
        if (parsed == null || parsed.mappings.isEmpty()) {
            return "the generated config parsed to no devices at all";
        }
        if (parsed.mappings.size() != 1) {
            return "the generated config parsed to " + parsed.mappings.size() + " devices";
        }
        final DeviceInputMapping mapping = parsed.mappings.get(0);

        // The <match> block is checked against the values that went in, not by asking the matcher
        // whether it matches: the generated block ANDs <build-device> with the live
        // android.os.Build.DEVICE, so running the matcher here would only ever re-confirm that
        // Build.DEVICE equals itself — and would fail spuriously anywhere Build is not the real
        // device's. DeviceConfigSummary reads the criteria back out, which is also exactly what
        // the import dialog will show on the phone this file is carried to.
        final DeviceConfigSummary summary = DeviceConfigSummary.of(
                new ByteArrayInputStream(xml.getBytes(UTF_8)));
        if (summary == null) {
            return "the generated config could not be read back";
        }
        final String deviceName = draft.facts.matchDeviceName();
        if (deviceName != null && !deviceName.isEmpty() && !deviceName.equals(summary.deviceName)) {
            return "the generated <match> does not match this device's keyboard (" + deviceName
                    + "); it came back as " + summary.deviceName;
        }
        if (!draft.facts.buildDevice.isEmpty()
                && !draft.facts.buildDevice.equals(summary.buildDevice)) {
            return "the generated <match> came back with build device " + summary.buildDevice
                    + " instead of " + draft.facts.buildDevice;
        }
        if (summary.matchesNothing()) {
            return "the generated <match> names nothing to match on";
        }
        if (deviceName != null && !deviceName.isEmpty()
                && !deviceName.equals(mapping.deviceName)) {
            return "the config parser read the device name as " + mapping.deviceName;
        }
        if (mapping.scancodeMappings.size() != draft.capturedKeys.size()) {
            return "expected " + draft.capturedKeys.size() + " captured keys, the parser read "
                    + mapping.scancodeMappings.size();
        }
        for (int i = 0; i < draft.capturedKeys.size(); i++) {
            final CapturedKey captured = draft.capturedKeys.get(i);
            final dev.bbkb.ime.core.device.config.model.ScancodeMapping read =
                    mapping.scancodeMappings.get(i);
            if (read.role != captured.step.role()) {
                return "key " + (i + 1) + " came back as role " + read.role
                        + " instead of " + captured.step.role();
            }
            if (!read.matches(captured.scanCode, captured.keyCode)) {
                return "key " + (i + 1) + " (" + captured.step + ") came back as " + read
                        + ", which does not match the captured scanCode " + captured.scanCode
                        + " / keyCode " + captured.keyCode;
            }
        }
        final String name = DeviceInputMappingParser.parseDisplayName(
                new ByteArrayInputStream(xml.getBytes(UTF_8)));
        if (name == null || !name.equals(draft.name)) {
            return "the config's name came back as " + name + " instead of " + draft.name;
        }
        return null;
    }

    // ── export ───────────────────────────────────────────────────────────────

    /**
     * Serialises, validates and writes the profile. The file lands in the imported-config
     * directory, so the returned id can be handed straight to
     * {@code CustomDeviceConfigManager.setActiveConfigId}.
     */
    @NonNull
    public static Result export(@NonNull Context context, @NonNull DeviceProfileDraft draft) {
        final String xml = DeviceProfileXmlWriter.toXml(draft);

        if (!draft.isExportable()) {
            return Result.failed("this device reports neither a keyboard name nor a build device,"
                    + " so the config could not identify it", xml);
        }
        final String invalid = validate(xml, draft);
        if (invalid != null) {
            Logger.error(TAG, "Refusing to write generated config: " + invalid);
            return Result.failed(invalid, xml);
        }

        final File dir = configDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.failed("could not create " + dir.getAbsolutePath(), xml);
        }
        final File file = new File(dir, draft.fileName());

        // Written through a sibling temp file: a half-written config in this directory is one the
        // manager would list, parse and fail on at the next IME start.
        final File temp = new File(dir, file.getName() + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(temp), UTF_8);
            writer.write(xml);
            writer.flush();
            writer.close();
            writer = null;
            if (file.exists() && !file.delete()) {
                return Result.failed("could not replace " + file.getName(), xml);
            }
            if (!temp.renameTo(file)) {
                return Result.failed("could not move the config into place", xml);
            }
        } catch (IOException e) {
            Logger.error(TAG, "Failed to write config: " + e.getMessage());
            return Result.failed("could not write the file: " + e.getMessage(), xml);
        } finally {
            closeQuietly(writer);
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }

        Logger.info(TAG, "Wrote device profile " + file.getAbsolutePath());
        return Result.written(file, PREFIX_CUSTOM + file.getName(), xml);
    }

    /** Writes the profile and makes it the active device config. */
    @NonNull
    public static Result exportAndActivate(@NonNull Context context,
                                           @NonNull DeviceProfileDraft draft) {
        final Result result = export(context, draft);
        if (result.ok()) {
            CustomDeviceConfigManager.getInstance(context).setActiveConfigId(result.configId);
        }
        return result;
    }

    /** The directory imported and built configs share. */
    @NonNull
    public static File configDir(@NonNull Context context) {
        return new File(context.getApplicationContext().getFilesDir(), CUSTOM_CONFIG_DIR);
    }

    // ── sharing ──────────────────────────────────────────────────────────────

    /**
     * A share-sheet intent for a written profile, or null if the file cannot be exposed.
     *
     * <p>The file is inside {@code filesDir}, so it needs the app's {@code FileProvider} (the
     * {@code device_configs} path was added to {@code res/xml/file_paths.xml} for this) and a
     * read grant for whatever app the user picks.
     */
    @Nullable
    public static Intent shareIntent(@NonNull Context context, @NonNull File file,
                                     @NonNull String chooserTitle) {
        try {
            final Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", file);
            final Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/xml")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, file.getName())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return Intent.createChooser(send, chooserTitle);
        } catch (Exception e) {
            Logger.error(TAG, "Could not build share intent: " + e.getMessage());
            return null;
        }
    }

    private static void closeQuietly(@Nullable Writer writer) {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
            // Nothing useful to do: the export already succeeded or already failed.
        }
    }
}
