package dev.bbkb.ime.core.device.config.builder;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A device config about to be written: name, facts and captured keys. Serialising is a pure
 * function of this object; {@link #exportDate} is a field so tests can pin it.
 */
public final class DeviceProfileDraft {

    @NonNull public final String name;
    @NonNull public final DeviceFacts facts;
    @NonNull public final List<CapturedKey> capturedKeys;

    /** Header comment date; defaults to today (ISO). */
    @NonNull public String exportDate = today();

    public DeviceProfileDraft(@NonNull String name, @NonNull DeviceFacts facts,
                              @NonNull List<CapturedKey> capturedKeys) {
        this.name = name;
        this.facts = facts;
        this.capturedKeys = Collections.unmodifiableList(new ArrayList<>(capturedKeys));
    }

    @NonNull
    public DeviceProfileDraft withExportDate(@NonNull String date) {
        this.exportDate = date;
        return this;
    }

    /** Only worth writing when a {@code <match>} block can identify this device. */
    public boolean isExportable() {
        return !facts.buildDevice.isEmpty() || facts.matchDeviceName() != null;
    }

    /** The stored file name, sanitised as the importer would. */
    @NonNull
    public String fileName() {
        final String safe = name.replaceAll("[^a-zA-Z0-9.\\-]", "_");
        return (safe.isEmpty() ? "device_profile" : safe) + ".xml";
    }

    @NonNull
    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }
}
