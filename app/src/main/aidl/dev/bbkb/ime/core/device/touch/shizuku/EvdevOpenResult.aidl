package dev.bbkb.ime.core.device.touch.shizuku;

import dev.bbkb.ime.core.device.touch.shizuku.EvdevDeviceInfo;

/**
 * What IEvdevService.open() did. `device` is non-null when a stream is running (events are on
 * their way to the callback). `error` explains a null `device` ("not_found", "bad_pattern",
 * "open_failed:13", ...), or, next to a non-null one, a non-fatal problem such as
 * "grab_failed:16" (streaming, but without the exclusive grab). Numbers are errno values.
 */
parcelable EvdevOpenResult {
    EvdevDeviceInfo device;
    String error;
}
