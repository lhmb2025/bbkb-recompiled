package com.blackberry.nuanceshim;

import android.util.Log;

import dev.bbkb.ime.core.shared.SystemProps;
import dev.bbkb.ime.BuildConfig;

/**
 * Debug-only access to the stock ET9 engine's configuration knobs.
 *
 * <p>The blob binds only two of its 49 settings to Java (see
 * {@code docs/et9-ranking-documentation.md}). This class reaches the rest through
 * {@code libxt9trace.so}, which dlsyms the exported {@code ET9AW*} entry points at runtime.
 * Every call validates the engine's ABI fingerprint before touching memory and returns a
 * diagnostic instead of crashing if the layout does not match.
 *
 * <p>Guarded by {@link BuildConfig#DEBUG} at every entry: this is an investigation tool, not
 * a product surface. When a knob graduates to a real setting it should get a proper binding
 * rather than going through here.
 *
 * <p>View output: {@code adb logcat -s ET9PROBE:I Et9Probe:I}
 */
public final class Et9Probe {

    private static final String TAG = "Et9Probe";

    static {
        try { System.loadLibrary("xt9trace"); }
        catch (Throwable t) { Log.e(TAG, "libxt9trace load failed", t); }
    }

    private Et9Probe() {}

    private static native String nativeProbe(long handle);
    private static native int nativeSetFences(long handle, int primary, int secondary);
    private static native int nativeSetQuarantine(long handle, int level1, int level2, int level3);

    /** System property that opts in to {@link #probe}: {@code adb shell setprop debug.et9.probe 1}. */
    static final String PROBE_PROPERTY = "debug.et9.probe";

    /**
     * Whether {@link #probe} may run. Off unless {@value #PROBE_PROPERTY} is {@code 1}.
     *
     * <p>Opt-in because the probe reads raw engine memory, and on a cold IME start (the process
     * killed, then restarted straight into {@code onStartInput}) it SIGSEGVs inside
     * {@code nativeProbe} while dereferencing the settings block — three times in about eight
     * cold starts on the KEY2 (2026-09-15), always within ~5 s of process start, never on a warm
     * session. The likely cause is the context passing the fingerprint checks before the engine
     * has finished building it; that is not confirmed. A native crash cannot be caught, so running
     * the probe on every debug session made the debug build itself unstable.
     *
     * <p>The {@code setprop debug.et9.fence N} experiment hook lives inside {@code nativeProbe}
     * ({@code et9probe.c}), so it also needs this property set.
     */
    static boolean isProbeEnabled() {
        String v = SystemProps.get(PROBE_PROPERTY);
        return "1".equals(v) || "2".equals(v) || "3".equals(v);
    }

    /**
     * Read the engine's current ranking/adaptation configuration.
     *
     * @param sdk the live engine instance (from {@code NuanceSDKManager.getInstance()})
     * @return a JSON-ish diagnostic string, or null if unavailable or not opted in
     */
    /**
     * Logs what the DLM enumeration returns (the Learned words screen and the Words backup both
     * read it): both counts, how many words came back, and the first/last. This is what found
     * the 65-word cap (see {@link NuanceSDK#getDLMWords}); {@code setprop debug.et9.probe 2}
     * runs just this dump without the native probe.
     */
    private static void dumpDlm(NuanceSDK sdk) {
        try {
            int plain = sdk.getDLMWordCount(false);
            int explicit = sdk.getDLMWordCount(true);
            java.util.ArrayList<String> words = sdk.getDLMWords();
            Log.i(TAG, "dlm: count(false)=" + plain + " count(true)=" + explicit
                    + " enumerated=" + words.size()
                    + " first=" + (words.isEmpty() ? "-" : words.get(0))
                    + " last=" + (words.isEmpty() ? "-" : words.get(words.size() - 1)));
            Log.i(TAG, "dlm words: " + words);
        } catch (Throwable t) {
            Log.w(TAG, "dlm dump failed", t);
        }
    }

    /**
     * {@code setprop debug.et9.probe 3}: the engine's own export of the learned model
     * ({@link NuanceSDK#exportDLM}), read back with {@code LearnedModelStream} and logged word by
     * word with use count, language, category, quality and flags, then saved as
     * {@code files/dlm-export.bin}. With {@code setprop debug.et9.dlmimport 1} as well, that saved
     * file is imported again through {@code LearnedModelTransfer} and the model re-read, which
     * exercises the whole round trip on a device.
     */
    private static void dumpDlmExport(NuanceSDK sdk) {
        try {
            byte[] stream = sdk.exportDLM();
            if (stream == null) {
                Log.i(TAG, "dlm export: unavailable (transferAvailable=" + sdk.isDLMTransferAvailable() + ")");
                return;
            }
            dev.bbkb.ime.core.engine.dlm.LearnedModelStream.Model model =
                    dev.bbkb.ime.core.engine.dlm.LearnedModelStream.INSTANCE.parse(stream);
            Log.i(TAG, "dlm export: " + stream.length + " bytes, version " + model.getVersion()
                    + ", updates " + model.getUpdateCount() + ", categories " + model.getCategories().size()
                    + ", words " + model.getWords().size() + ", next-word records " + model.getNextWordRecords());
            for (dev.bbkb.ime.core.engine.dlm.LearnedModelStream.Word w : model.getWords()) {
                Log.i(TAG, "dlm word: " + w.getText() + " count=" + w.getCount() + " lang=" + w.getLanguage()
                        + " cat=" + w.getCategory() + " quality=" + w.getQuality() + " flags=" + w.getFlags());
            }
            android.content.Context app = dev.bbkb.ime.core.ImeApplication.getInstance();
            if (app != null) {
                java.io.File file = new java.io.File(app.getFilesDir(), "dlm-export.bin");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(stream); }
                Log.i(TAG, "dlm export: saved " + file);
                if ("1".equals(SystemProps.get("debug.et9.dlmimport"))) {
                    byte[] again = java.nio.file.Files.readAllBytes(file.toPath());
                    dev.bbkb.ime.core.engine.dlm.LearnedModelTransfer.Outcome outcome =
                            dev.bbkb.ime.core.engine.dlm.LearnedModelTransfer.INSTANCE.importModel(again);
                    Log.i(TAG, "dlm import: " + outcome.getClass().getSimpleName()
                            + (outcome instanceof dev.bbkb.ime.core.engine.dlm.LearnedModelTransfer.Outcome.Refused
                                ? " status=" + ((dev.bbkb.ime.core.engine.dlm.LearnedModelTransfer.Outcome.Refused) outcome).getStatus() : "")
                            + " words now " + sdk.getDLMWordCount(false));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "dlm export failed", t);
        }
    }

    public static String probe(NuanceSDK sdk) {
        if (!BuildConfig.DEBUG || sdk == null || !isProbeEnabled()) return null;
        // Experiment hook: `setprop debug.et9.quarantine A[,B,C]` sets the engine's three DLM
        // quarantine levels (each 0-5); a level left out keeps the engine's current value.
        String q = SystemProps.get("debug.et9.quarantine");
        if (q != null && !q.isEmpty()) {
            int[] levels = parseLevels(q);
            Log.i(TAG, "setQuarantine(" + q + ") -> " + setQuarantine(sdk, levels[0], levels[1], levels[2]));
        }
        dumpDlm(sdk);
        // `setprop debug.et9.probe 2`: the DLM dump only, skipping the native probe (which can
        // SIGSEGV on a cold start, see isProbeEnabled); `3` adds the model export round trip.
        String level = SystemProps.get(PROBE_PROPERTY);
        if ("3".equals(level)) dumpDlmExport(sdk);
        if ("2".equals(level) || "3".equals(level)) return null;
        try {
            String result = nativeProbe(sdk.getNativeHandleForDebug());
            Log.i(TAG, "probe: " + result);
            return result;
        } catch (Throwable t) {
            Log.w(TAG, "probe failed", t);
            return null;
        }
    }

    /** Set both auto-correct fences. Returns the engine status (0 = success). */
    public static int setFences(NuanceSDK sdk, int primary, int secondary) {
        if (!BuildConfig.DEBUG || sdk == null) return -1;
        try {
            return nativeSetFences(sdk.getNativeHandleForDebug(), primary, secondary);
        } catch (Throwable t) {
            Log.w(TAG, "setFences failed", t);
            return -1;
        }
    }

    /**
     * Set the three DLM quarantine levels, each 0-5 (the engine answers status 26 otherwise); a
     * negative level keeps the engine's current value. Returns the engine status (0 = success).
     * {@code ET9AWSetDLMQuarantineLevel} takes three bytes, not one; what the second and third
     * mean is not yet known (the engine's defaults are 1, 1 and 0).
     */
    public static int setQuarantine(NuanceSDK sdk, int level1, int level2, int level3) {
        if (!BuildConfig.DEBUG || sdk == null) return -1;
        try {
            return nativeSetQuarantine(sdk.getNativeHandleForDebug(), level1, level2, level3);
        } catch (Throwable t) {
            Log.w(TAG, "setQuarantine failed", t);
            return -1;
        }
    }

    /** "A", "A,B" or "A,B,C" as three levels; -1 (keep) where a level is missing or not a number. */
    static int[] parseLevels(String spec) {
        int[] levels = {-1, -1, -1};
        String[] parts = spec.split(",", -1);
        for (int i = 0; i < levels.length && i < parts.length; i++) {
            try {
                levels[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // keep
            }
        }
        return levels;
    }
}
