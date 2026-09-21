package com.blackberry.nuanceshim;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.FileObserver;
import android.text.TextUtils;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import dev.bbkb.ime.core.shared.InputPathDebug;

/**
 * NuanceSDK - JNI wrapper for Nuance XT9 predictive text engine
 * Provides word prediction, auto-correction, and next-word suggestions via native library.
 * Manages language packs, dynamic learning models, and context-aware text prediction.
 * Supports both alphabetic and Chinese input methods with profile-aware learning.
 */

public class NuanceSDK {
    public static final String CANGJIE_VARIANT = "cangjie";
    private static final char CATEGORY_CONTACTS = 65281;
    public static final String CHINESE_LOCALE = "zh";
    private static final char CODE_NULL = 0;
    public static final String DEVICE_ATHENA = "athena";
    public static final String DEVICE_MERCURY = "mercury";
    public static final String DEVICE_VENICE = "venice";
    public static final String DYNAMIC_MODEL_ABSTRACT_DIR = "nuance";
    public static final String DYNAMIC_MODEL_FILE_NAME_WORK = "alphadlm_work.bin";
    public static final String DYNAMIC_MODEL_FILE_NAME_ZH_WORK = "zhdlm_work.bin";
    public static final char ET9CPSYLLABLEDELIMITER = '\'';
    public static final int MAX_CONTEXT_LENGTH = 1024;
    public static final String QUICK_CANGJIE_VARIANT = "quick_cangjie";
    private static final String TAG = "NuanceSDK";
    private AutoCommitCallback mAutoCommitHandler;
    private ManagedModeCallback mCallbackHandler;
    private boolean mIsCurrLocaleChinese;
    private boolean mIsLanguageInit;
    private boolean mIsManaged;
    private long mNativeHandle;
    private FileObserver mNuanceFileObserver;
    public static final String DYNAMIC_MODEL_FILE_NAME = "alphadlm.bin";
    public static final String DYNAMIC_MODEL_FILE_NAME_ZH = "zhdlm.bin";
    public static final String[] DYNAMIC_MODEL_FILE_ARRAY = {DYNAMIC_MODEL_FILE_NAME, DYNAMIC_MODEL_FILE_NAME_ZH};
    private static final Map<String, int[]> sPkbDimensionsMap = buildPkbDimensions();

    private static Map<String, int[]> buildPkbDimensions() {
        // A static builder, not double-brace initialization: the latter created an anonymous
        // HashMap subclass holding a reference to its enclosing scope.
        final Map<String, int[]> map = new HashMap<>();
        {
            map.put(NuanceSDK.DEVICE_MERCURY, new int[]{1080, 324});
            // DEAD WEIGHT on the engine path — audit L6. Nothing here reaches ET9 any more: the
            // PKB branch of setKeyboardSize(boolean,short,short) passes a hardcoded (0,0) and
            // never consults this map, and setKeyboardSizePkb() was removed. The only remaining
            // reader is logTapKey's debug NON-PKB branch below, which is a logcat line. Keep the
            // measurement — it is the record of how the athena sensor was characterised — but do
            // not read the paragraph below as describing shipped behaviour.
            //
            // ATHENA keypad-height calibration (what the engine WOULD scale sensor->authored
            // against, had this been passed: authored_y = sensor_y * 324 / H, against the
            // root-324 layout this was measured for, not today's 1080x450 athena KDB).
            // Measured empirically with the sensor visualizer
            // (XT9SENSOR / sensor_viz export, ~18k samples): the three LETTER rows are LINEARLY spaced
            // in the 1080x525 sensor space with density-centers at sensor Y ~69 / ~213 / ~354 (≈142 px
            // apart -- NOT 131). Fitting those to the authored row centers (54/162/270, 108 apart) gives
            // slope 324/H ≈ 0.758  ->  H ≈ 425. (The earlier 394 was a 75%-of-525 guess and over-scaled
            // ~8%, biasing row-3 touches toward the function row.) The function/space row is crammed into
            // the bottom ~115 px and maps to authored Y > 324 (off the letter grid), correctly ignored.
            map.put(NuanceSDK.DEVICE_ATHENA, new int[]{1080, 425});
            map.put(NuanceSDK.DEVICE_VENICE, new int[]{1420, 609});
        }
        return Collections.unmodifiableMap(map);
    }
    private static final Object sMutex = new Object();

    /** Hoisted so addWord does not recompile the pattern per call. */
    private static final java.util.regex.Pattern WHITESPACE =
            java.util.regex.Pattern.compile("\\s+");

    /**
     * Audit EB-2: monotonic counter of INPUT-BUFFER mutations (clear / clearOneSymbol /
     * processKeyBySymbol). Bumped inside {@code sMutex} so a reader that samples it under the
     * same lock discipline sees a consistent value.
     *
     * <p>The suggestion worker reads the engine with a non-atomic clear -> replay ->
     * buildSelectionList -> getSelectionListWord sequence while the main thread can mutate the
     * same buffer through the tracker. This counter lets the worker tell the two cases apart:
     * a tracker that merely ADVANCED (normal at typing speed, and already handled by the
     * delivery-time guards) versus the buffer actually being rewritten INSIDE its read window,
     * which is the case that scores candidates against a phantom symbol stream.
     *
     * <p>Sampled after the worker's own replay, so the worker never trips on its own writes.
     * Static because the engine buffer is process-global, like {@link #sMutex}.
     */
    private static volatile long sInputBufferEpoch = 0L;

    /** @see #sInputBufferEpoch */
    public static long getInputBufferEpoch() {
        return sInputBufferEpoch;
    }

    /**
     * Audit EB-3: monotonic generation of the engine's SELECTION LIST, bumped inside
     * {@code sMutex} every time {@link #buildSelectionList()} rebuilds it.
     *
     * <p>{@code selectionListSelectWord} takes a RAW INDEX into whatever list the engine currently
     * holds. A {@link WordInfo} carries the index it had when the bridge assembled it, so if
     * anything rebuilds the list between assembly and the learn-time select — an in-flight worker
     * request is neither cancelled nor drained by a separator commit — that index now points into
     * a different list and the wrong word gets learned. Stamping the generation onto each WordInfo
     * lets the learner notice and learn by content instead of by index.
     */
    private static volatile long sSelectionListGeneration = 0L;

    /** @see #sSelectionListGeneration */
    public static long getSelectionListGeneration() {
        return sSelectionListGeneration;
    }
    private Locale mPrevPrimaryLocale = null;
    private boolean mIsExplicitLearning = false;
    public boolean mIsPkb = true;

    
    public interface AutoCommitCallback {
        void onAutoCommitWord(String str);
    }

    
    public interface ManagedModeCallback {
        void onManagedModeChanged(boolean z);
    }

    private native int addCategoryWords(long j, char c, String[] strArr);

    private native boolean addExplicitSymb(long j, char c);

    private native boolean addWord(long j, String str);

    private native boolean attachDLMFile(long j, String str);

    private native String buildHangul(long j);

    private native int buildSelectionList(long j);

    private native boolean clear(long j);

    private native boolean clearOneSymbol(long j);

    private native String compatibilityJamoToJamo(String str);

    private native String convertBpmfSymbolToLower(long j, char c);

    private native String decodeHangul(long j, String str, boolean z);

    private native boolean deleteDLMWord(long j, String str);

    private native void deregisterLdb(long j, String[] strArr);

    private native boolean detachDLMFile(long j);

    private native void dispose(long j);

    private native String getDLMWord(long j, int i);

    private native int getDLMWordCount(long j, boolean z);

    private native char getDefaultWordSeparator(long j);

    private native String getInlineWord(long j);

    private native KeyInfo[] getKeys(long j);

    private native String[] getLanguage(long j);

    private native int getSelectionListSize(long j);

    private native WordInfo getSelectionListWord(long j, int i);

    private native long init(AssetManager assetManager, String str, String str2);

    private native boolean invalidateKeyboard(long j, String str);

    private native boolean isBpmfUpperCaseSymbol(long j, char c);

    private native boolean isChineseBPMFMode(long j);

    private native boolean isChineseCangjieMode(long j);

    private native boolean isChineseStrokeMode(long j);

    private native boolean isSpellingCorrect(long j, String str);

    private native boolean loadKeyboardLayout(long j, boolean z, String str);

    private static native void nativeInit();

    private native boolean processKeyBySymbol(long j, char c);

    private native boolean registerLdb(long j, String str);

    private native boolean scanBuffer(long j, String str);

    private native boolean selectionListSelectWord(long j, int i, boolean z, String str);

    private native boolean setContextBuffer(long j, String str);

    private native boolean setEmojiPredictionEnabled(long j, boolean z);

    private native boolean setExplicitLearning(long j, boolean z);

    private native boolean setInputMethod(long j, String str);

    private native boolean setKeyboardSize(long j, short s, short s2);

    private native boolean setLanguage(long j, String[] strArr);

    private native boolean setShiftState(long j, int i);

    private native boolean touchCancel(long j, long j2);

    private native boolean touchEnd(long j, long j2, float f, float f2, long j3);

    private native boolean touchMove(long j, long j2, float f, float f2, long j3);

    private native boolean touchStart(long j, long j2, float f, float f2, long j3);

    private native boolean wordChanged(long j, String str, int i, String str2, String str3);

    static {
        System.loadLibrary("native-lib");
        nativeInit();
    }

    public NuanceSDK(Context context) {
        synchronized (sMutex) {
            this.mNativeHandle = init(context.getAssets(), context.getFilesDir().getAbsolutePath(), context.getNoBackupFilesDir().getAbsolutePath());
            if (this.mNativeHandle == 0) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Native init returned 0; skipping FileObserver startup");
                throw new IllegalStateException("Failed to initialize text prediction");
            }
            setIsPkb(true);
            observeNuanceFiles(context);
            if (BuildConfig.DEBUG) Log.i(TAG, "NuanceSDK(): " + this);
        }
    }

    public void dispose() {
        synchronized (sMutex) {
            if (this.mNuanceFileObserver != null) {
                this.mNuanceFileObserver.stopWatching();
                this.mNuanceFileObserver = null;   // a second dispose() re-stopped a dead observer
            }
            setIsPkb(false);
            dispose(this.mNativeHandle);
            this.mCallbackHandler = null;
            this.mAutoCommitHandler = null;
            this.mNativeHandle = 0L;
            if (BuildConfig.DEBUG) Log.i(TAG, "dispose(): " + this);
        }
    }

    public void setCallbackHandler(ManagedModeCallback callback) {
        synchronized (sMutex) {
            this.mCallbackHandler = callback;
        }
    }

    public void setAutoCommitCallbackHandler(AutoCommitCallback autoCommitCallback) {
        synchronized (sMutex) {
            this.mAutoCommitHandler = autoCommitCallback;
        }
    }

    // Called by native code (JNI callback) - DO NOT REMOVE
    public void updateAutoCommittedString(String str) {
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.updateAutoCommittedString: str=" + str + " mAutoCommitHandler=" + this.mAutoCommitHandler);
        AutoCommitCallback autoCommitCallback;
        synchronized (sMutex) {
            autoCommitCallback = this.mAutoCommitHandler;
        }
        if (autoCommitCallback == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Callback handler is null.");
        } else {
            autoCommitCallback.onAutoCommitWord(str);
        }
    }

    private void observeNuanceFiles(Context context) {
        final File nuanceDir = new File(context.getFilesDir(), DYNAMIC_MODEL_ABSTRACT_DIR);
        // Create it rather than bailing out: this used to return early on a first run, and it
        // was never retried, so the watcher that invalidates the keyboard when alphadlm.bin
        // changes was simply absent for the rest of the process -- a freshly seeded DLM was
        // not picked up until the next process start.
        if (!nuanceDir.isDirectory() && !nuanceDir.mkdirs()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Cannot create nuance dir: " + nuanceDir.getAbsolutePath());
            return;
        }
        final String nuancePath = nuanceDir.getAbsolutePath();
        final int mask = FileObserver.CLOSE_WRITE | FileObserver.CREATE | FileObserver.MOVED_TO;
        // FileObserver(String, int) is deprecated since API 29 in favour of the File overload;
        // minSdk 23 keeps both arms live.
        this.mNuanceFileObserver = (android.os.Build.VERSION.SDK_INT >= 29)
                ? new FileObserver(nuanceDir, mask) {
            @Override
            public void onEvent(int event, String path) {
                onNuanceFileEvent(nuancePath, path);
            }
        }
                : new FileObserver(nuancePath, mask) {
            @Override
            public void onEvent(int event, String path) {
                onNuanceFileEvent(nuancePath, path);
            }
        };
        this.mNuanceFileObserver.startWatching();
    }

    private void onNuanceFileEvent(String nuancePath, String path) {
        if (path == null) return;
        String fullPath = nuancePath + "/" + path;
        // Only react to our dynamic model files
        for (String name : DYNAMIC_MODEL_FILE_ARRAY) {
            if (fullPath.endsWith(name)) {
                invalidateKeyboard(fullPath);
                break;
            }
        }
    }

    public boolean isManaged() {
        boolean z;
        synchronized (sMutex) {
            z = this.mIsManaged;
        }
        return z;
    }

    public void setIsManaged(boolean z) {
        synchronized (sMutex) {
            this.mIsManaged = z;
        }
    }

    public void setIsExplicitLearning(boolean z) {
        synchronized (sMutex) {
            this.mIsExplicitLearning = z;
            setExplicitLearning(this.mNativeHandle, z);
        }
    }

    public void setIsPkb(boolean z) {
        synchronized (sMutex) {
            this.mIsPkb = z;
        }
    }

    public boolean isCurrLocaleChinese() {
        boolean z;
        synchronized (sMutex) {
            z = this.mIsCurrLocaleChinese;
        }
        return z;
    }

    public int buildSelectionList() {
        int iBuildSelectionList;
        synchronized (sMutex) {
            iBuildSelectionList = buildSelectionList(this.mNativeHandle);
            sSelectionListGeneration++;   // audit EB-3: the list just changed identity
            if (BuildConfig.DEBUG) {
                // XT9 recognition output: golden target for the owned gesture decoder. For gestures the
                // result arrives as the INLINE (composing) word, not the selection list (count is 0).
                // 'spell' = literal decoded keys, 'word' = lexicon-matched result.
                // View: adb logcat -s XT9WORDS:I XT9OWNED:I  (deflooded: only meaningful results, deduped)
                String inline = getInlineWord(this.mNativeHandle);
                boolean meaningful = iBuildSelectionList > 0 || (inline != null && !inline.isEmpty());
                String sig = iBuildSelectionList + "|" + inline;
                if (meaningful && !sig.equals(mLastWordsLog)) {
                    mLastWordsLog = sig;
                    android.util.Log.i("XT9WORDS", "build: count=" + iBuildSelectionList + " inline='" + inline + "'");
                    int n = Math.min(iBuildSelectionList, 8);
                    for (int i = 0; i < n; i++) {
                        WordInfo w = getSelectionListWord(this.mNativeHandle, i);
                        if (w != null) android.util.Log.i("XT9WORDS",
                            "  [" + i + "] word='" + w.word + "' spell='" + w.spell
                            + "' corr=" + w.isSpellCorrected + " auto=" + w.shouldAutoAccept);
                    }
                }
            }
        }
        return iBuildSelectionList;
    }

    public String buildHangul() {
        String strBuildHangul;
        synchronized (sMutex) {
            strBuildHangul = buildHangul(this.mNativeHandle);
            if (strBuildHangul == null) {
                strBuildHangul = "";
            }
        }
        return strBuildHangul;
    }

    public String decodeHangul(String str, boolean z) {
        String strDecodeHangul;
        synchronized (sMutex) {
            strDecodeHangul = decodeHangul(this.mNativeHandle, str, z);
        }
        return strDecodeHangul;
    }

    public char compatibilityJamoToJamoTransform(char c) {
        char cCharAt;
        synchronized (sMutex) {
            String strCompatibilityJamoToJamo = compatibilityJamoToJamo(Character.toString(c));
            boolean zIsEmpty = TextUtils.isEmpty(strCompatibilityJamoToJamo);
            cCharAt = CODE_NULL;
            if (!zIsEmpty) {
                cCharAt = strCompatibilityJamoToJamo.charAt(0);
            }
        }
        return cCharAt;
    }

    public int getSelectionListSize() {
        int selectionListSize;
        synchronized (sMutex) {
            selectionListSize = getSelectionListSize(this.mNativeHandle);
        }
        return selectionListSize;
    }

    public WordInfo getSelectionListWord(int i) {
        WordInfo selectionListWord;
        synchronized (sMutex) {
            selectionListWord = getSelectionListWord(this.mNativeHandle, i);
        }
        return selectionListWord;
    }

    public String getInlineWord() {
        String inlineWord;
        synchronized (sMutex) {
            inlineWord = getInlineWord(this.mNativeHandle);
        }
        return inlineWord;
    }

    public boolean processKeyBySymbol(char c) {
        boolean zProcessKeyBySymbol;
        synchronized (sMutex) {
            sInputBufferEpoch++;   // audit EB-2: input-buffer mutation
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "IME:nuanceSDK:processKey" +
                    " char='" + c + "'" +
                    " handle=" + this.mNativeHandle +
                    " thread=" + Thread.currentThread().getName());
            }
            zProcessKeyBySymbol = processKeyBySymbol(this.mNativeHandle, c);
        }
        return zProcessKeyBySymbol;
    }

    public boolean addExplicitSymb(char c) {
        boolean zAddExplicitSymb;
        synchronized (sMutex) {
            zAddExplicitSymb = addExplicitSymb(this.mNativeHandle, c);
        }
        return zAddExplicitSymb;
    }

    public boolean clearOneSymbol() {
        boolean zClearOneSymbol;
        synchronized (sMutex) {
            sInputBufferEpoch++;   // audit EB-2: input-buffer mutation
            zClearOneSymbol = clearOneSymbol(this.mNativeHandle);
        }
        return zClearOneSymbol;
    }

    public boolean clear() {
        boolean zClear;
        synchronized (sMutex) {
            sInputBufferEpoch++;   // audit EB-2: input-buffer mutation
            if (InputPathDebug.perGesture()) {
                final StackTraceElement[] st = new Throwable().getStackTrace();
                Log.d("PIPELINE", "IME:nuanceSDK:clear" +
                    " handle=" + this.mNativeHandle +
                    " caller=" + (st.length > 2 ? st[2].toString() : "?") +
                    " thread=" + Thread.currentThread().getName());
            }
            zClear = clear(this.mNativeHandle);
        }
        return zClear;
    }

    public boolean setKeyboardSize(boolean z, short s, short s2) {
        synchronized (sMutex) {
            if (z) {
                // PKB. The ORIGINAL app calls setKeyboardSizePkb(), which looks its dimensions up
                // by Build.DEVICE — and on the KEY2 that lookup MISSES, so it passes (0,0): a 1:1
                // sensor->authored mapping.
                //
                // Audit L6 (2026-09-20) corrected why it misses. This comment used to claim the
                // original's map held ONLY mercury. It does not: it has all three entries, athena
                // included, at {1080, 525} (orig/.../nuanceshim/NuanceSDK.java:51-57). The lookup
                // misses because the original KEYS that entry by the string "bbf100" — its
                // DEVICE_ATHENA constant (orig ibid.:26) is the KEY2's model number, not its
                // Build.DEVICE — while Build.DEVICE on the KEY2 is "athena". That is not an
                // assumption: it is what selects our own athena KDB variant, via
                // res/xml/device_config_athena.xml's <build-device exact="athena"/>, and the
                // athena PKB layout loading on device is confirmed by the NKL dump quoted on
                // syncKeyboardLayout below. So the original's athena entry is dead code in the
                // original, and {1080, 525} never reached its engine.
                //
                // The conclusion is unchanged, and so is this branch: our custom athena KDB is
                // authored 1080x450 and is verified (taps) to map 1:1 to the touch surface
                // anchored to the top, so (0,0) is exactly right for it.
                // (An interim fix that routed through setKeyboardSizePkb + our added athena={1080,425}
                //  entry stretched the 450 KDB by 450/425 and is reverted; that 425 belonged to the
                //  root-324 layout, not athena. See the original-APK diff, 2026-08-13.)
                return setKeyboardSize(this.mNativeHandle, (short) 0, (short) 0);
            }
            return setKeyboardSize(this.mNativeHandle, s, s2);
        }
    }

    public boolean setLanguage(Locale[] localeArr) {
        synchronized (sMutex) {
            if (localeArr != null) {
                if (localeArr.length > 0) {
                    this.mPrevPrimaryLocale = getPrimaryLanguage();
                    if (setLanguage(this.mNativeHandle, localeToString(localeArr))) {
                        // Dictionary probe (debug): after the language activates, ask the engine
                        // directly whether common words are in the active LDB. Settles "is 'hello'
                        // missing from the dictionary" without relying on a focused text field.
                        if (BuildConfig.DEBUG) {
                            String[] probe = {"hello", "hell", "help", "the", "jello", "he'll", "hero", "teh", "vey", "adn"};
                            StringBuilder sb = new StringBuilder("DICTPROBE lang=").append(localeToString(localeArr)).append(" ->");
                            for (String w : probe) sb.append(' ').append(w).append('=').append(isSpellingCorrect(this.mNativeHandle, w));
                            Log.i(TAG, sb.toString());
                        }
                        checkAndUpdateLearningStatus();
                        if (!shouldReloadDLMFile()) {
                            return true;
                        }
                        // Audit EB-6: shouldReloadDLMFile() latches mIsLanguageInit BEFORE the
                        // load is attempted, so a single failed load meant the retry check said
                        // "already initialised" forever after — no learner for the rest of the
                        // process, and every commit took the do-nothing path. Release the latch on
                        // failure so the next setLanguage tries again.
                        boolean dlmLoaded = loadDLMFromFile();
                        if (!dlmLoaded) {
                            releaseLanguageInitLatch();
                        }
                        return dlmLoaded;
                    }
                    if (BuildConfig.DEBUG) Log.e(TAG, "Failed to set Language");
                    return false;
                }
            }
            return false;
        }
    }

    private void checkAndUpdateLearningStatus() {
        Locale locale = this.mPrevPrimaryLocale;
        if (locale == null || !CHINESE_LOCALE.equals(locale.getLanguage())) {
            return;
        }
        setExplicitLearning(this.mIsExplicitLearning);
    }

    private String[] localeToString(Locale[] localeArr) {
        String[] strArr = new String[localeArr.length * 2];
        for (int i = 0; i < localeArr.length; i++) {
            int i2 = i * 2;
            strArr[i2] = localeArr[i].getLanguage();
            strArr[i2 + 1] = localeArr[i].getCountry();
        }
        return strArr;
    }

    public Locale[] getLanguage() {
        Locale[] localeArr;
        synchronized (sMutex) {
            String[] language = getLanguage(this.mNativeHandle);
            localeArr = new Locale[language.length / 2];
            for (int i = 0; i < language.length / 2; i++) {
                int i2 = i * 2;
                localeArr[i] = new Locale(language[i2], language[i2 + 1]);
            }
        }
        return localeArr;
    }

    public Locale getPrimaryLanguage() {
        Locale[] language = getLanguage();
        if (language.length > 0) {
            return language[0];
        }
        return Locale.ENGLISH;
    }

    public boolean registerLdb(String str) {
        boolean zRegisterLdb;
        synchronized (sMutex) {
            zRegisterLdb = registerLdb(this.mNativeHandle, str);
        }
        return zRegisterLdb;
    }

    public void deregisterLdb(String str, String str2) {
        synchronized (sMutex) {
            String[] strArr = new String[2];
            strArr[0] = str;
            if (str2 == null) {
                str2 = "";
            }
            strArr[1] = str2;
            deregisterLdb(this.mNativeHandle, strArr);
        }
    }

    public boolean isSpellingCorrect(String str) {
        boolean zIsSpellingCorrect;
        synchronized (sMutex) {
            zIsSpellingCorrect = isSpellingCorrect(this.mNativeHandle, str);
        }
        return zIsSpellingCorrect;
    }

    public boolean selectionListSelectWord(int i, boolean z, String str) {
        boolean zSelectionListSelectWord;
        synchronized (sMutex) {
            zSelectionListSelectWord = selectionListSelectWord(this.mNativeHandle, i, z, str);
        }
        return zSelectionListSelectWord;
    }

    public char getDefaultWordSeparator() {
        char defaultWordSeparator;
        synchronized (sMutex) {
            defaultWordSeparator = getDefaultWordSeparator(this.mNativeHandle);
        }
        return defaultWordSeparator;
    }

    public KeyInfo[] getKeys() {
        KeyInfo[] keys;
        synchronized (sMutex) {
            keys = getKeys(this.mNativeHandle);
        }
        if (BuildConfig.DEBUG && keys != null) {
            // XT9 key-model capture: the loaded KDB geometry, ground truth for the
            // owned point->key resolver and GetKeyPositions. View: adb logcat -s XT9KEYS:I
            android.util.Log.i("XT9KEYS", "getKeys: " + keys.length + " keys");
            for (int i = 0; i < keys.length; i++) {
                KeyInfo k = keys[i];
                char c = (k.keyCode >= 32 && k.keyCode < 127) ? (char) k.keyCode : '?';
                android.util.Log.i("XT9KEYS", String.format(
                    "  k[%02d] code=%-5d '%c'  L=%-4d T=%-4d R=%-4d B=%-4d  c=(%d,%d)",
                    i, k.keyCode, c, k.left, k.top, k.right, k.bottom, k.x, k.y));
            }
        }
        return keys;
    }

    public boolean setExplicitLearning(boolean z) {
        boolean explicitLearning;
        synchronized (sMutex) {
            explicitLearning = setExplicitLearning(this.mNativeHandle, z);
        }
        return explicitLearning;
    }

    public boolean scanBuffer(String str) {
        boolean zScanBuffer;
        synchronized (sMutex) {
            zScanBuffer = scanBuffer(this.mNativeHandle, str);
        }
        return zScanBuffer;
    }

    public boolean addWord(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        synchronized (sMutex) {
            if (!mIsLanguageInit) {
                return false;
            }
            // DLM doesn't support phrases with spaces - split and add each word
            if (str.contains(" ")) {
                boolean anyAdded = false;
                // Every non-empty token is added. The old guard was `length() > 1`, which
                // silently dropped the single-letter components of a phrase ("a la carte",
                // "I ll"); its comment named a class, not a word, which is rename-pass damage.
                for (String word : WHITESPACE.split(str)) {
                    if (!word.isEmpty()) {
                        anyAdded |= addWord(this.mNativeHandle, word);
                    }
                }
                return anyAdded;
            }
            return addWord(this.mNativeHandle, str);
        }
    }

    public boolean wordChanged(String str, int i, String str2, String str3) {
        synchronized (sMutex) {
            return wordChanged(this.mNativeHandle, str, i, str2, str3);
        }
    }

    public void addContactsWords(String[] strArr) {
        synchronized (sMutex) {
            if (!mIsLanguageInit) {
                if (BuildConfig.DEBUG) Log.d(TAG, "DLM not ready, skipping addContactsWords (" + strArr.length + " words)");
                return;
            }
            addCategoryWords(this.mNativeHandle, CATEGORY_CONTACTS, strArr);
        }
    }

    public void addContactsWords(String[] strArr, boolean z) {
        synchronized (sMutex) {
            if (z == this.mIsManaged) {
                addContactsWords(strArr);
            }
        }
    }

    public boolean invalidateKeyboard(String str) {
        synchronized (sMutex) {
            if (this.mNativeHandle == 0L) {
                if (BuildConfig.DEBUG) Log.w(TAG, "invalidateKeyboard called with no native handle; ignoring path=" + str);
                return false;
            }
            return invalidateKeyboard(this.mNativeHandle, str);
        }
    }

    // Stray-touch filter: a real swipe produces many touchMove events; taps and capacitive
    // edge blips produce 0-2. We track per-pointer move counts and, on touch-up, CANCEL
    // (rather than end) degenerate gestures so they never register as 1-point records that
    // pollute the engine's gesture buffer. Both gesture paths (PointerTracker and
    // GestureEventProcessor) funnel through these wrappers, so this is the single choke point.
    private static final int MIN_GESTURE_MOVES = 3;
    private final java.util.HashMap<Long, Integer> mGestureMoves = new java.util.HashMap<>();

    // Debug: log which KDB key a tap lands on, replicating the engine's tap->key mapping —
    // scale sensor coords into the KDB's authored 1080x324 using the active setKeyboardSize
    // dimensions, then point-in-rect against the live key model. Tap a physical key and see
    // what the engine resolves it to. Tag: XT9TAPKEY.
    private KeyInfo[] mDbgKeys;
    private String mLastWordsLog;   // dedup for the deflooded XT9WORDS recognition log
    private static boolean sDbgKbZ;         // last syncKeyboardLayout args (to reload the same layout for RE)
    private static String  sDbgKbStr = "";
    private static boolean sDescWatchDone;  // one-shot: caught the NKL+0x4c descriptor writer yet?

    private void logTapKey(float x, float y) {
        KeyInfo[] keys = mDbgKeys;
        if (keys == null || keys.length == 0) { android.util.Log.i("XT9TAPKEY", "no key model yet"); return; }
        // Mirror what the engine ACTUALLY does, rather than re-deriving it here — this method has
        // now misreported the resolved key twice by inventing its own mapping.
        //
        // PKB passes SetKeyboardSize(0,0), which zeroes the scale, and sensor_to_authored then
        // skips scaling altogether: the mapping is 1:1, which is exactly what the athena layout
        // is authored for (1080x450 over a 1080x525 sensor, letters in the top 450). The touch
        // offset is zero for every shipped layout, so this is a true 1:1 map — but the RAW
        // coordinates are printed alongside because they are the only value that cannot drift
        // out of sync with the engine.
        //
        // VKB does pass real dimensions, so it keeps a proportional mapping.
        float ax, ay;
        if (mIsPkb) {
            ax = x;
            ay = y;
        } else {
            float authoredW = 1f, authoredH = 1f;
            for (KeyInfo k : keys) {
                if (k.right > authoredW) authoredW = k.right;
                if (k.bottom > authoredH) authoredH = k.bottom;
            }
            int[] dim = sPkbDimensionsMap.get(Build.DEVICE);
            float kw = (dim != null && dim[0] > 0) ? dim[0] : 1080f;
            float kh = (dim != null && dim[1] > 0) ? dim[1] : 525f;
            ax = x * authoredW / kw;
            ay = y * authoredH / kh;
        }
        KeyInfo hit = null;
        for (KeyInfo k : keys) {
            if (ax >= k.left && ax < k.right && ay >= k.top && ay < k.bottom) { hit = k; break; }
        }
        boolean inside = hit != null;
        if (hit == null) {                  // nearest-center fallback (engine does similar)
            float bd = Float.MAX_VALUE;
            for (KeyInfo k : keys) { float dx = ax - k.x, dy = ay - k.y, d = dx * dx + dy * dy; if (d < bd) { bd = d; hit = k; } }
        }
        char c = (hit != null && hit.keyCode >= 32 && hit.keyCode < 127) ? (char) hit.keyCode : '?';
        android.util.Log.i("XT9TAPKEY", String.format("touchdown raw(%.0f,%.0f) pkb=%b -> authored(%.0f,%.0f) -> '%c' code=%d%s",
            x, y, mIsPkb, ax, ay, c, hit != null ? hit.keyCode : -1, inside ? "" : " (NEAREST - outside all keys)"));
    }

    public boolean touchStart(long j, float f, float f2, long j2) {
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchStart: pointer=" + j + " x=" + f + " y=" + f2 + " time=" + j2);
        boolean z;
        synchronized (sMutex) {
            mGestureMoves.put(Long.valueOf(j), Integer.valueOf(0));
            if (InputPathDebug.perGesture() && mDbgKeys == null) {
                mDbgKeys = getKeys(this.mNativeHandle);
                if (InputPathDebug.on()) Xt9Trace.dumpNkl(this.mNativeHandle);
            }
            z = touchStart(this.mNativeHandle, j, f, f2, j2);
        }
        if (InputPathDebug.perGesture()) logTapKey(f, f2);   // one line per gesture — cheap
        if (InputPathDebug.on()) Xt9Kdb.logTap(f, f2);           // tap calibration: owned resolution + coords (any contact)
        if (InputPathDebug.on()) Xt9Kdb.traceStart(f, f2, j2);   // W3a: mirror to owned scratch trace
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchStart: returned " + z);
        return z;
    }

    public boolean touchMove(long j, float f, float f2, long j2) {
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchMove: pointer=" + j + " x=" + f + " y=" + f2 + " time=" + j2);
        boolean z;
        synchronized (sMutex) {
            Integer c = mGestureMoves.get(Long.valueOf(j));
            if (c != null) mGestureMoves.put(Long.valueOf(j), Integer.valueOf(c.intValue() + 1));
            z = touchMove(this.mNativeHandle, j, f, f2, j2);
        }
        if (InputPathDebug.on()) Xt9Kdb.traceMove(f, f2, j2);   // W3a: mirror to owned scratch trace
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchMove: returned " + z);
        return z;
    }

    /**
     * Finish pointer {@code j}'s gesture.
     *
     * <p><b>Return contract:</b> {@code true} means <em>the engine took a gesture and there is a
     * deposit to consume</em> — that is exactly what both callers use it for
     * ({@code GestureEventProcessor.onUpEvent}, {@code PointerTracker.onUpEvent}, each calling
     * {@code NuanceSDKManager.noteGestureDeposit()} on true). It is NOT "the native call
     * succeeded": the native {@code touchEnd} deliberately returns success whatever the
     * recognizer said, and the stray-touch branch below does not reach the recognizer at all.
     */
    public boolean touchEnd(long j, float f, float f2, long j2) {
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchEnd: pointer=" + j + " x=" + f + " y=" + f2 + " time=" + j2);
        boolean z;
        synchronized (sMutex) {
            Integer moves = mGestureMoves.remove(Long.valueOf(j));
            int n = (moves == null) ? 0 : moves.intValue();
            if (n < MIN_GESTURE_MOVES) {
                // Stray / tap: cancel so it doesn't register as a 1-point gesture record.
                touchCancel(this.mNativeHandle, j);
                // AUDIT L5 (docs/2026-09_kdb-touch-abi_audit.md): report FALSE, not touchCancel's
                // return. Both callers read this boolean as "the engine accepted a gesture" and
                // call NuanceSDKManager.noteGestureDeposit() on true — and touchCancel always
                // returns true, because ET9KDB_TouchCancel returns status 0 and the JNI shim maps
                // 0 to true. So "I cancelled this contact" and "I accepted this gesture" were
                // indistinguishable to the caller. Nothing broke, because noteGestureDeposit()
                // re-publishes the CURRENT native rank sequence, which for a cancelled contact is
                // the previous gesture's already-consumed value — a no-op. It was still an
                // ignored error return on the one boolean the gesture path acts on.
                z = false;
                if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchEnd: stray (" + n + " moves) -> touchCancel");
            } else {
                if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchEnd: gesture end after " + n + " move points");
                // S1: bracket the native call — recognition consumes + frees the record INSIDE
                // touchEnd, so "pre" shows the in-progress record (real tag/header bytes) and
                // "post" shows the freed/consumed state.
                if (InputPathDebug.on()) Xt9Trace.dumpTraceRec(this.mNativeHandle, "pre");
                z = touchEnd(this.mNativeHandle, j, f, f2, j2);
                if (InputPathDebug.on()) Xt9Trace.dump(this.mNativeHandle, "touchEnd", 0);
                if (InputPathDebug.on()) Xt9Trace.dumpTraceRec(this.mNativeHandle, "post");   // S1: blob record ground-truth for byte-diff
                // W3b: gestures do NOT populate the tap symbol buffer (ctx+0x52) — confirmed empty
                // after a swipe. The blob's gesture output is the selection list, already logged as
                // XT9WORDS in buildSelectionList ('spell' = literal decode, the owned-decoder target).
                if (InputPathDebug.on()) Xt9Kdb.traceEnd(f, f2, j2);   // W3a: owned decode + XT9OWNED log
            }
        }
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchEnd: returned " + z);
        return z;
    }

    public boolean touchCancel(long j) {
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchCancel: pointer=" + j);
        boolean z;
        synchronized (sMutex) {
            mGestureMoves.remove(Long.valueOf(j));
            z = touchCancel(this.mNativeHandle, j);
        }
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchCancel: returned " + z);
        return z;
    }

    /**
     * The engine's keyboard-kind flag is <b>isPkb</b>, not isTouchKb — they are opposites, and
     * passing the wrong one silently loads the wrong keyboard layout.
     *
     * <p>Confirmed on-device 2026-08-12 by dumping the live NKL right after the layout sync, on a
     * physical-keyboard session (isTouchKb=false, keyboardId=0):
     * <pre>
     *   passing isTouchKb (false) -> NKL 1080x600, 33 keys  = a VKB layout   (wrong)
     *   passing isPkb     (true)  -> NKL 1080x450, 30 keys  = athena PKB     (correct)
     * </pre>
     * The second matches assets/kdb/athena/qwerty_pkb.xml exactly (1080x450, 30 keys, 108x180
     * keys), and is the only polarity under which {@link #setKeyboardSize(boolean, short, short)}
     * takes its documented "true = PKB" branch at all — with isTouchKb it never ran on the PKB,
     * and {@code setKeyboardSizePkb()} was dead code (since removed).
     *
     * <p>Why it mattered: with a VKB layout loaded, physical-keyboard gestures were scored against
     * on-screen key rectangles, so touches resolved to the wrong keys (a 'd' landing in 's' —
     * "done" recognised as "some"/"songs"). This also blocked the whole point of the device-variant
     * KDB registry: the athena override could never win because the PKB branch was never selected.
     */
    public boolean syncKeyboardLayout(boolean z, String str, int i, int i2) {
        // Device KDB variants register on a worker; make sure the registry is populated
        // before the engine's first SetKdbNum. No-op once registration has completed.
        Xt9KdbVariant.awaitReady(2000);
        final boolean isPkb = !z;   // engine wants isPkb; callers hand us isTouchKb
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.syncKeyboardLayout: isTouchKb=" + z + " locale='" + str + "' width=" + i + " height=" + i2 + " kdbVariant='" + Xt9KdbVariant.applied() + "'");
        sDbgKbZ = z; sDbgKbStr = str;   // remember for the RE re-parse trigger
        synchronized (sMutex) {
            boolean loadResult = dbgFirstLoad(this.mNativeHandle, isPkb, str);
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.syncKeyboardLayout: loadKeyboardLayout returned " + loadResult);
            if (!loadResult) {
                return false;
            }
            boolean sizeResult = setKeyboardSize(isPkb, (short) i, (short) i2);
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.syncKeyboardLayout: setKeyboardSize returned " + sizeResult);
            if (InputPathDebug.on()) {
                android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.syncKeyboardLayout: isPkb=" + isPkb
                    + " (isTouchKb=" + z + ")");
                // Identify the KDB the engine ACTUALLY ended up with, after the load+size calls.
                // The one-shot dump on first touch fires before this sync and can be stale.
                Xt9Trace.dumpNkl(this.mNativeHandle);
            }
            return sizeResult;
        }
    }

    public boolean loadKeyboardLayout(boolean z) {
        Xt9KdbVariant.awaitReady(2000);
        boolean zLoadKeyboardLayout;
        synchronized (sMutex) {
            zLoadKeyboardLayout = dbgFirstLoad(this.mNativeHandle, z, "");
        }
        return zLoadKeyboardLayout;
    }

    /** RE (one-shot): the descriptor computer runs only on the VERY FIRST native loadKeyboardLayout of the
     *  process (then it's cached + memcpy'd). Arm a watchpoint on the bump head neighborhood BEFORE that
     *  first call, report the writer PC after. All loadKeyboardLayout call sites route through here so the
     *  earliest one is caught. View: adb logcat -s XT9WP:I */
    private boolean dbgFirstLoad(long h, boolean z, String s) {
        boolean reArm = BuildConfig.DEBUG && !sDescWatchDone && h != 0;
        if (reArm) { sDescWatchDone = true; Xt9Trace.armDescWatch(h, 0x4c, 1); }
        boolean r = loadKeyboardLayout(h, z, s);
        if (reArm) Xt9Trace.reportDescWatch(h);
        return r;
    }

    public boolean setContextBuffer(String str) {
        boolean contextBuffer;
        synchronized (sMutex) {
            contextBuffer = setContextBuffer(this.mNativeHandle, str);
        }
        return contextBuffer;
    }

    public boolean setEmojiPredictionEnabled(boolean z) {
        boolean emojiPredictionEnabled;
        synchronized (sMutex) {
            emojiPredictionEnabled = setEmojiPredictionEnabled(this.mNativeHandle, z);
        }
        return emojiPredictionEnabled;
    }

    /**
     * Check if the DLM (Dynamic Learning Model) is initialized and ready for word operations.
     * @return true if language has been set and DLM is loaded
     */
    public boolean isDLMReady() {
        synchronized (sMutex) {
            return this.mIsLanguageInit;
        }
    }

    /**
     * Audit EB-6: undo {@link #shouldReloadDLMFile()}'s optimistic latch so a failed DLM load is
     * retried on the next setLanguage rather than being treated as done for the process lifetime.
     */
    private void releaseLanguageInitLatch() {
        synchronized (sMutex) {
            this.mIsLanguageInit = false;
        }
    }

    public boolean shouldReloadDLMFile() {
        synchronized (sMutex) {
            boolean zIsChineseLocale = isChineseLocale();
            if (this.mIsCurrLocaleChinese == zIsChineseLocale && this.mIsLanguageInit) {
                return false;
            }
            this.mIsLanguageInit = true;
            this.mIsCurrLocaleChinese = zIsChineseLocale;
            return true;
        }
    }

    public boolean loadDLMFromFile() {
        synchronized (sMutex) {
            if (!reattachDLM()) {
                return false;
            }
            if (this.mCallbackHandler != null) {
                this.mCallbackHandler.onManagedModeChanged(this.mIsManaged);
            }
            return true;
        }
    }

    public int getDLMWordCount(boolean z) {
        int dLMWordCount;
        synchronized (sMutex) {
            dLMWordCount = getDLMWordCount(this.mNativeHandle, z);
        }
        return dLMWordCount;
    }

    public String getDLMWord(int i) {
        String dLMWord;
        synchronized (sMutex) {
            dLMWord = getDLMWord(this.mNativeHandle, i);
        }
        return dLMWord;
    }

    /**
     * The raw engine handle, for {@link Et9Probe} only. The blob's JNI binds just two of the
     * engine's 49 configuration knobs; the probe reaches the rest by dlsym'ing the exported
     * ET9AW* entry points, which needs this pointer. Package-private and debug-gated at the
     * call sites — not a general accessor.
     */
    long getNativeHandleForDebug() {
        synchronized (sMutex) {
            return this.mNativeHandle;
        }
    }

    public boolean deleteDLMWord(String str) {
        boolean zDeleteDLMWord;
        synchronized (sMutex) {
            zDeleteDLMWord = deleteDLMWord(this.mNativeHandle, str);
        }
        return zDeleteDLMWord;
    }

    public ArrayList<String> getDLMWords() {
        ArrayList<String> arrayList;
        synchronized (sMutex) {
            arrayList = new ArrayList<>();
            int dLMWordCount = getDLMWordCount(false);
            for (int i = 0; i < dLMWordCount; i++) {
                String dLMWord = getDLMWord(i);
                if (dLMWord != null) {
                    arrayList.add(dLMWord);
                }
            }
        }
        return arrayList;
    }

    public String getDLMFilePath() {
        synchronized (sMutex) {
            return this.mIsCurrLocaleChinese ? this.mIsManaged ? DYNAMIC_MODEL_FILE_NAME_ZH_WORK : DYNAMIC_MODEL_FILE_NAME_ZH : this.mIsManaged ? DYNAMIC_MODEL_FILE_NAME_WORK : DYNAMIC_MODEL_FILE_NAME;
        }
    }

    /**
     * Full dynamic-model reset: detach the DLM, delete its persisted file, and reload
     * so the engine recreates a virgin model. The per-word delete path
     * ({@link #deleteDLMWord}/{@link #deleteWorkDLMWordsExplicitly}) only prunes the
     * enumerable unigrams — bigrams ("He'll go"), case variants, and usage boosts all
     * survive it, which is how a poisoned model persisted through "Clear all learned
     * words" (see the 2026-08 DLM-poisoning diagnosis).
     *
     * @param nuanceDir the engine's file directory (contextFilesDir/nuance)
     * @return true if the fresh model loaded
     */
    public boolean resetDynamicModel(java.io.File nuanceDir) {
        synchronized (sMutex) {
            detachDLMFile(this.mNativeHandle);
            java.io.File dlmFile = new java.io.File(nuanceDir, getDLMFilePath());
            if (dlmFile.exists() && !dlmFile.delete()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "resetDynamicModel: could not delete " + dlmFile);
            }
            boolean reloaded = loadDLMFromFile();
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "resetDynamicModel: reloaded=" + reloaded
                        + " dlmWords=" + getDLMWordCount(false));
            }
            return reloaded;
        }
    }

    private boolean isChineseLocale() {
        for (Locale locale : getLanguage()) {
            if (CHINESE_LOCALE.equals(locale.getLanguage())) {
                return true;
            }
        }
        return false;
    }

    public boolean isChineseStrokeMode() {
        boolean zIsChineseStrokeMode;
        synchronized (sMutex) {
            zIsChineseStrokeMode = isChineseStrokeMode(this.mNativeHandle);
        }
        return zIsChineseStrokeMode;
    }

    public boolean isChineseCangjieMode() {
        boolean zIsChineseCangjieMode;
        synchronized (sMutex) {
            zIsChineseCangjieMode = isChineseCangjieMode(this.mNativeHandle);
        }
        return zIsChineseCangjieMode;
    }

    public boolean isChineseBPMFMode() {
        boolean zIsChineseBPMFMode;
        synchronized (sMutex) {
            zIsChineseBPMFMode = isChineseBPMFMode(this.mNativeHandle);
        }
        return zIsChineseBPMFMode;
    }

    public boolean isBpmfUpperCaseSymbol(char c) {
        boolean zIsBpmfUpperCaseSymbol;
        synchronized (sMutex) {
            zIsBpmfUpperCaseSymbol = isBpmfUpperCaseSymbol(this.mNativeHandle, c);
        }
        return zIsBpmfUpperCaseSymbol;
    }

    public String convertBpmfSymbolToLower(char c) {
        String strConvertBpmfSymbolToLower;
        synchronized (sMutex) {
            strConvertBpmfSymbolToLower = convertBpmfSymbolToLower(this.mNativeHandle, c);
        }
        return strConvertBpmfSymbolToLower;
    }

    /**
     * Detaches and re-attaches the current DLM.
     *
     * <p>This was {@code loadDLMFromFile(String)}, whose parameter was silently ignored: the
     * body always used {@link #getDLMFilePath()}. All three callers happened to pass exactly
     * that, so the bug was latent -- but given the DLM-poisoning history, a signature that
     * promises "load this DLM" and loads a different one is the wrong thing to leave in place.
     */
    private boolean reattachDLM() {
        boolean z;
        synchronized (sMutex) {
            z = detachDLMFile(this.mNativeHandle) && attachDLMFile(this.mNativeHandle, getDLMFilePath());
        }
        return z;
    }

    public void deleteWorkDLMWordsExplicitly(ArrayList<String> arrayList) {
        synchronized (sMutex) {
            boolean zLoadWorkDLMExplicitly = loadWorkDLMExplicitly();
            Iterator<String> it = arrayList.iterator();
            while (it.hasNext()) {
                String next = it.next();
                if (!deleteDLMWord(next)) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Failed to delete word : " + next);
                }
            }
            setDLMToPrevState(zLoadWorkDLMExplicitly);
        }
    }

    public ArrayList<String> getWorkDLMWordsExplicitly() {
        ArrayList<String> dLMWords;
        synchronized (sMutex) {
            boolean zLoadWorkDLMExplicitly = loadWorkDLMExplicitly();
            dLMWords = getDLMWords();
            setDLMToPrevState(zLoadWorkDLMExplicitly);
        }
        return dLMWords;
    }

    private boolean loadWorkDLMExplicitly() {
        boolean z;
        synchronized (sMutex) {
            z = this.mIsManaged;
            if (!this.mIsManaged) {
                this.mIsManaged = true;
                reattachDLM();
            }
        }
        return z;
    }

    private void setDLMToPrevState(boolean z) {
        this.mIsManaged = z;
        if (z) {
            return;
        }
        reattachDLM();
    }

    public void setInputMethod(String str) {
        synchronized (sMutex) {
            setInputMethod(this.mNativeHandle, str);
        }
    }

    public void setShiftState(int i) {
        synchronized (sMutex) {
            setShiftState(this.mNativeHandle, i);
        }
    }
}
