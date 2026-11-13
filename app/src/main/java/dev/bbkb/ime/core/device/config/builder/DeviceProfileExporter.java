package dev.bbkb.ime.core.device.config.builder;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.core.util.AtomicFile;

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
 * Writes a built profile to the imported-config directory and shares it.
 *
 * <p>Nothing is written until {@link #validate} has round-tripped the XML through the runtime
 * parser: a bad config would silently do nothing at runtime, so the failure must surface here.
 */
public final class DeviceProfileExporter {

    private static final String TAG = "DeviceProfileExporter";

    /**
     * Mirror {@code CustomDeviceConfigManager}'s private constants (pinned by
     * {@code DeviceProfileExportTest}) so a built profile is an imported config, activatable at once.
     */
    public static final String CUSTOM_CONFIG_DIR = "device_configs";
    public static final String PREFIX_CUSTOM = "custom:";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private DeviceProfileExporter() {}

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

    /** Parses {@code xml} as the app will; returns null when it says what the draft meant, else a reason. */
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

        // Compare <match> against the inputs, not via the matcher: it ANDs with the live
        // Build.DEVICE, which would be circular here and fail where Build is not the real device's.
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

    /** Serialises, validates and writes the profile; the result id suits {@code setActiveConfigId}. */
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

        // AtomicFile so a crash never leaves a half-written config the manager would load; it only
        // lists "*.xml", so a leftover ".new" is ignored.
        final AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            final Writer writer = new OutputStreamWriter(out, UTF_8);
            writer.write(xml);
            // Flushed, not closed: finishWrite syncs the stream before it closes it.
            writer.flush();
            atomic.finishWrite(out);
        } catch (IOException e) {
            atomic.failWrite(out);
            Logger.error(TAG, "Failed to write config: " + e.getMessage());
            return Result.failed("could not write the file: " + e.getMessage(), xml);
        }
        // finishWrite only logs a failed rename, which leaves the config in the .new file.
        final File pending = new File(file.getPath() + ".new");
        if (pending.exists()) {
            //noinspection ResultOfMethodCallIgnored
            pending.delete();
            return Result.failed("could not move the config into place", xml);
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

    @NonNull
    public static File configDir(@NonNull Context context) {
        return new File(context.getApplicationContext().getFilesDir(), CUSTOM_CONFIG_DIR);
    }

    /**
     * A share-sheet intent for a written profile, or null if it cannot be exposed. Needs the
     * {@code device_configs} path in {@code res/xml/file_paths.xml}.
     */
    @Nullable
    public static Intent shareIntent(@NonNull Context context, @NonNull File file,
                                     @NonNull String chooserTitle) {
        return shareIntent(context, file, chooserTitle, "text/xml");
    }

    /** {@link #shareIntent(Context, File, String)} for another MIME type (custom letter layouts). */
    @Nullable
    public static Intent shareIntent(@NonNull Context context, @NonNull File file,
                                     @NonNull String chooserTitle, @NonNull String mimeType) {
        try {
            final Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", file);
            final Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, file.getName())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return Intent.createChooser(send, chooserTitle);
        } catch (Exception e) {
            Logger.error(TAG, "Could not build share intent: " + e.getMessage());
            return null;
        }
    }
}
