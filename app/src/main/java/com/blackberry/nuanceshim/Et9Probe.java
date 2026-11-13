package com.blackberry.nuanceshim;

import android.util.Log;

import dev.bbkb.ime.core.shared.SystemProps;
import dev.bbkb.ime.BuildConfig;

/**
 * Debug-only access to ET9 engine knobs the blob does not bind to Java (2 of 49 are bound), via
 * {@code libxt9trace.so} dlsym of the exported {@code ET9AW*} entry points. Native code checks the
 * engine's ABI fingerprint before touching memory. A knob that becomes a real setting gets a proper
 * binding instead. View: {@code adb logcat -s ET9PROBE:I Et9Probe:I}
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
     * <p>Opt-in because {@code nativeProbe} can SIGSEGV reading the settings block in the first
     * seconds after a cold IME start, and a native crash cannot be caught. The
     * {@code debug.et9.fence N} hook lives inside {@code nativeProbe}, so it also needs this set.
     */
    static boolean isProbeEnabled() {
        String v = SystemProps.get(PROBE_PROPERTY);
        return "1".equals(v) || "2".equals(v) || "3".equals(v);
    }

    /**
     * Logs what the DLM enumeration returns (read by Learned words and the Words backup): both
     * counts, the number enumerated, and the first/last word.
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
     * Logs the engine's learned-model export word by word and saves it as {@code files/dlm-export.bin}.
     * With {@code setprop debug.et9.dlmimport 1} the file is imported back, testing the round trip.
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

    /** Logs the engine's ranking/adaptation configuration; a diagnostic string, or null if off or unavailable. */
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
        // Level 2: DLM dump only, skipping the native probe; level 3 adds the export round trip.
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
     * Sets the three DLM quarantine levels, each 0-5 (else status 26); negative keeps the current
     * value. Returns the engine status. {@code ET9AWSetDLMQuarantineLevel} takes three bytes; the
     * meaning of the second and third is unknown (defaults 1, 1, 0).
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
