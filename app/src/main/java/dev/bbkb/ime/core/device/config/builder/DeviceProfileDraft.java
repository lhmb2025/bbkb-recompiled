package dev.bbkb.ime.core.device.config.builder;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A device config about to be written: the name it will carry, the facts it was built from, and
 * the keys the user captured.
 *
 * <p>Everything {@link DeviceProfileXmlWriter} needs and nothing it does not, so serialising is a
 * pure function of this object — no clock, no {@code Context}, no live hardware. {@link
 * #exportDate} is the one value that would otherwise be read from the clock mid-write; it is a
 * field so a test can pin it.
 */
public final class DeviceProfileDraft {

    @NonNull public final String name;
    @NonNull public final DeviceFacts facts;
    @NonNull public final List<CapturedKey> capturedKeys;

    /** Date written into the header comment; defaults to today, in ISO form. */
    @NonNull public String exportDate = today();

    public DeviceProfileDraft(@NonNull String name, @NonNull DeviceFacts facts,
                              @NonNull List<CapturedKey> capturedKeys) {
        this.name = name;
        this.facts = facts;
        this.capturedKeys = Collections.unmodifiableList(new ArrayList<>(capturedKeys));
    }

    /** Fluent form of {@link #exportDate}, for tests and for a caller with its own clock. */
    @NonNull
    public DeviceProfileDraft withExportDate(@NonNull String date) {
        this.exportDate = date;
        return this;
    }

    /**
     * A config is only worth writing if it says something about this device: at minimum a
     * {@code <match>} block that can identify it.
     */
    public boolean isExportable() {
        return !facts.buildDevice.isEmpty() || facts.matchDeviceName() != null;
    }

    /** The file name this profile is stored under, sanitised the way the importer would. */
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
