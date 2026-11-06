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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import dev.bbkb.ime.core.engine.EngineFacts;
import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.locale.LocaleUtils;
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

    /**
     * {@link #getSelectionListSources} value of a word from the learned model: learned,
     * typed-once and added-on-purpose words alike. Language-dictionary words and contact names
     * are 2, the typed word 9.
     */
    public static final int WORD_SOURCE_LEARNED_MODEL = 1;
    public static final String QUICK_CANGJIE_VARIANT = "quick_cangjie";
    private static final String TAG = "NuanceSDK";
    // Volatile, written without sMutex (audit P-7): see setCallbackHandler.
    private volatile AutoCommitCallback mAutoCommitHandler;
    private volatile ManagedModeCallback mCallbackHandler;
    private boolean mIsCurrLocaleChinese;
    private boolean mIsLanguageInit;
    private volatile boolean mIsManaged;
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
    /**
     * Serialises every call into the blob. Static because the primary and the spell checker's
     * engine share native state. Only two threads ever take it, the engine thread and the loader
     * thread ({@link EngineThreads}); it arbitrates between a short op on the one and a long hold
     * (a language load, a contacts import) on the other. The main thread never takes it.
     */
    private static final Object sMutex = new Object();

    /**
     * The first statement of every public wrapper that takes {@link #sMutex}. In debug builds it
     * throws unless the caller is the engine thread or the loader thread: the blob keeps pointers
     * into the stack of the thread that last called it, so a call from any thread that can exit
     * (a pool thread, a binder thread, a per-service startup pool) leaves the next caller reading
     * a dead stack (the KEY2 SIGSEGV of 2026-11-04). In release builds it compiles to nothing.
     */
    private static void enter(String what) {
        EngineThreads.assertEngineCapable(what);
    }

    /**
     * What the main thread may know about this engine without calling into it: refreshed under
     * sMutex by every call that can change it (a language load, an input-method switch, a model
     * attach) before that call returns, and read without a lock.
     */
    private volatile EngineFacts mFacts = EngineFacts.NONE;

    /**
     * Whether a native setLanguage has ever succeeded on this engine. Until then the facts read
     * only the language list (setLanguage itself reads that first), not the input-mode and
     * separator natives, which nothing has ever called on an engine with no language. Under sMutex.
     */
    private boolean mLanguageActive = false;

    /** The latest published facts; {@link EngineFacts#NONE} before init and after dispose. */
    public EngineFacts getFacts() {
        return this.mFacts;
    }

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

    // The engine's Amharic dictionary stores each syllable as its first-order letter plus a vowel
    // marker, while the keyboard types and shows precomposed syllables. While the primary language
    // is written in Ethiopic every word crossing this boundary is translated; see EthiopicSyllables.
    // Read and written under sMutex only.
    private boolean mEthiopicTranslation = false;
    private boolean mIsExplicitLearning = false;
    /**
     * Whether {@link #loadDLMFromFile} has attached the learned model to this engine. Written
     * under sMutex; volatile so {@link #setCallbackHandler} can read it without the lock.
     */
    private volatile boolean mDlmAttached = false;

    /**
     * The last {@link #setContactsCategoryState} choice, applied again whenever the model is
     * (re)attached; null until the first call leaves the engine's own default alone.
     */
    private Boolean mContactsCategoryEnabled = null;
    /**
     * Bumped by every call that changes the learned model (a word learned, added, deleted,
     * changed, the contacts batch, an import, a re-attach), so {@link #getDLMWords} can tell
     * (in debug logs) that the model changed while it released the lock between chunks.
     */
    private volatile int mDlmMutations;

    /** Words read per hold of sMutex in {@link #getDLMWords}. */
    private static final int DLM_ENUMERATION_CHUNK = 512;

    /** The contacts-category value the attached model last accepted; null = must be applied. */
    private volatile Boolean mContactsCategoryApplied = null;
    /** The value the engine last accepted from {@link #setEmojiPredictionEnabled}; null = unknown. */
    private volatile Boolean mEmojiPredictionApplied = null;

    /** Whether the learned model is attached, i.e. a language has been set up on this engine. */
    public boolean isDLMAttached() {
        enter("isDLMAttached");
        synchronized (sMutex) {
            return this.mDlmAttached;
        }
    }
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

    private native boolean setCategoryState(long j, char c, boolean z);

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
        enter("NuanceSDK");
        synchronized (sMutex) {
            this.mNativeHandle = init(context.getAssets(), context.getFilesDir().getAbsolutePath(), context.getNoBackupFilesDir().getAbsolutePath());
            if (this.mNativeHandle == 0) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Native init returned 0; skipping FileObserver startup");
                throw new IllegalStateException("Failed to initialize text prediction");
            }
            setIsPkb(true);
            observeNuanceFiles(context);
            refreshFactsLocked();
            if (BuildConfig.DEBUG) Log.i(TAG, "NuanceSDK(): " + this);
        }
    }

    public void dispose() {
        enter("dispose");
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
            this.mDlmAttached = false;
            this.mContactsCategoryApplied = null;
            this.mEmojiPredictionApplied = null;
            this.mLanguageActive = false;
            this.mFacts = EngineFacts.NONE;
            if (BuildConfig.DEBUG) Log.i(TAG, "dispose(): " + this);
        }
    }

    public void setCallbackHandler(ManagedModeCallback callback) {
        // Audit P-7: no sMutex. The lock guarded two Java fields here, not native state, and
        // DynamicLearningManager calls this on the main thread during onCreate, where it waited
        // out a whole dictionary load (301 ms measured on the emulator). Both fields are volatile:
        // this writes the handler then reads mDlmAttached, loadDLMFromFile writes mDlmAttached
        // then reads the handler, so at least one of the two delivers onManagedModeChanged. Both
        // may, which is harmless: the callback only switches the learner on.
        this.mCallbackHandler = callback;
        boolean replay = callback != null && this.mDlmAttached;
        // The learned model may already be attached when the handler arrives: the Personal
        // learned words screen gives this engine a language (and so the model) when it is
        // opened before the keyboard has bound in the process, and the keyboard's later
        // setLanguage then skips loadDLMFromFile (the language-init latch). Without this
        // replay DynamicLearningManager never heard onManagedModeChanged and no learner was
        // active for the rest of the process -- the "open question (2026-08)" in its source.
        if (replay) {
            callback.onManagedModeChanged(this.mIsManaged);
        }
    }

    public void setAutoCommitCallbackHandler(AutoCommitCallback autoCommitCallback) {
        // Audit P-7: a volatile write, no sMutex; the lock protected only this field.
        this.mAutoCommitHandler = autoCommitCallback;
    }

    // Called by native code (JNI callback) - DO NOT REMOVE
    public void updateAutoCommittedString(String str) {
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.updateAutoCommittedString: str=" + str + " mAutoCommitHandler=" + this.mAutoCommitHandler);
        AutoCommitCallback autoCommitCallback = this.mAutoCommitHandler;
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
        // FileObserver(String, int) is deprecated since API 29 in favour of the File overload,
        // which does not exist below API 29.
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
                // FileObserver delivers on its own thread; the engine is only called from the
                // engine thread.
                EngineThreads.post(() -> invalidateKeyboard(fullPath));
                break;
            }
        }
    }

    public boolean isManaged() {
        enter("isManaged");
        boolean z;
        synchronized (sMutex) {
            z = this.mIsManaged;
        }
        return z;
    }

    public void setIsManaged(boolean z) {
        enter("setIsManaged");
        synchronized (sMutex) {
            this.mIsManaged = z;
        }
    }

    public void setIsExplicitLearning(boolean z) {
        enter("setIsExplicitLearning");
        synchronized (sMutex) {
            this.mIsExplicitLearning = z;
            setExplicitLearning(this.mNativeHandle, z);
        }
    }

    public void setIsPkb(boolean z) {
        enter("setIsPkb");
        synchronized (sMutex) {
            this.mIsPkb = z;
        }
    }

    public boolean isCurrLocaleChinese() {
        enter("isCurrLocaleChinese");
        boolean z;
        synchronized (sMutex) {
            z = this.mIsCurrLocaleChinese;
        }
        return z;
    }

    public int buildSelectionList() {
        enter("buildSelectionList");
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
        enter("buildHangul");
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
        enter("decodeHangul");
        String strDecodeHangul;
        synchronized (sMutex) {
            strDecodeHangul = decodeHangul(this.mNativeHandle, str, z);
        }
        return strDecodeHangul;
    }

    public char compatibilityJamoToJamoTransform(char c) {
        enter("compatibilityJamoToJamoTransform");
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
        enter("getSelectionListSize");
        int selectionListSize;
        synchronized (sMutex) {
            selectionListSize = getSelectionListSize(this.mNativeHandle);
        }
        return selectionListSize;
    }

    /**
     * The source of each of the first {@code count} words of the selection list as last built
     * ({@link #WORD_SOURCE_LEARNED_MODEL} and the others), which the blob's own word read leaves
     * out; null when the engine cannot be reached. One read of the engine's list, no lookups.
     */
    public byte[] getSelectionListSources(int count) {
        enter("getSelectionListSources");
        synchronized (sMutex) {
            if (this.mNativeHandle == 0 || count <= 0 || !Et9Dlm.isLoaded()) return null;
            try {
                return Et9Dlm.nativeSelectionListSources(this.mNativeHandle, count);
            } catch (Throwable t) {
                if (BuildConfig.DEBUG) Log.w(TAG, "getSelectionListSources", t);
                return null;
            }
        }
    }

    public WordInfo getSelectionListWord(int i) {
        enter("getSelectionListWord");
        WordInfo selectionListWord;
        synchronized (sMutex) {
            selectionListWord = getSelectionListWord(this.mNativeHandle, i);
            if (this.mEthiopicTranslation && selectionListWord != null) {
                selectionListWord.word = EthiopicSyllables.compose(selectionListWord.word);
                selectionListWord.spell = EthiopicSyllables.compose(selectionListWord.spell);
            }
        }
        return selectionListWord;
    }

    public String getInlineWord() {
        enter("getInlineWord");
        String inlineWord;
        synchronized (sMutex) {
            inlineWord = getInlineWord(this.mNativeHandle);
            if (this.mEthiopicTranslation) {
                inlineWord = EthiopicSyllables.compose(inlineWord);
            }
        }
        return inlineWord;
    }

    public boolean processKeyBySymbol(char c) {
        enter("processKeyBySymbol");
        boolean zProcessKeyBySymbol;
        synchronized (sMutex) {
            sInputBufferEpoch++;   // audit EB-2: input-buffer mutation
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "IME:nuanceSDK:processKey" +
                    " char='" + c + "'" +
                    " handle=" + this.mNativeHandle +
                    " thread=" + Thread.currentThread().getName());
            }
            final String symbols = this.mEthiopicTranslation ? EthiopicSyllables.decompose(String.valueOf(c)) : null;
            if (symbols != null && symbols.length() > 1) {
                zProcessKeyBySymbol = true;
                for (int i = 0; i < symbols.length(); i++) {
                    zProcessKeyBySymbol &= processKeyBySymbol(this.mNativeHandle, symbols.charAt(i));
                }
            } else {
                zProcessKeyBySymbol = processKeyBySymbol(this.mNativeHandle, c);
            }
        }
        return zProcessKeyBySymbol;
    }

    public boolean addExplicitSymb(char c) {
        enter("addExplicitSymb");
        boolean zAddExplicitSymb;
        synchronized (sMutex) {
            zAddExplicitSymb = addExplicitSymb(this.mNativeHandle, c);
        }
        return zAddExplicitSymb;
    }

    public boolean clearOneSymbol() {
        enter("clearOneSymbol");
        boolean zClearOneSymbol;
        synchronized (sMutex) {
            sInputBufferEpoch++;   // audit EB-2: input-buffer mutation
            zClearOneSymbol = clearOneSymbol(this.mNativeHandle);
        }
        return zClearOneSymbol;
    }

    public boolean clear() {
        enter("clear");
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
        enter("setKeyboardSize");
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
                // DEVICE_ATHENA constant (orig ibid.:26) is the KEY2's model number — while
                // Build.DEVICE on the LineageOS KEY2 this was measured on is "athena". That is
                // not an assumption: it is what selected our own athena config there, via
                // res/xml/device_config_athena.xml's <build-device> rule, and the athena PKB
                // layout loading on device is confirmed by the NKL dump quoted on
                // syncKeyboardLayout below. So on that device the original's athena entry is
                // dead code, and {1080, 525} never reached its engine.
                //
                // 2026-10-01: that holds only where Build.DEVICE is "athena". A KEY2 whose
                // ro.product.device is "bbf100" (a custom LineageOS build reports it; stock
                // firmware presumably does too, given the original's key) would HIT in the
                // original and pass {1080, 525}. Not re-measured; this branch does not depend
                // on it, because it never consults the map.
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
        enter("setLanguage");
        // A long hold (the whole LDB + DLM load). It runs on the loader thread, so an engine-thread
        // op that arrives meanwhile waits for sMutex; the main thread never does.
        synchronized (sMutex) {
            try {
                return setLanguageLocked(localeArr);
            } finally {
                // Every path, success or not: the facts describe whatever the engine holds now,
                // and they are published before setLanguage reports completion.
                refreshFactsLocked();
            }
        }
    }

    private boolean setLanguageLocked(Locale[] localeArr) {
        synchronized (sMutex) {
            if (localeArr != null) {
                if (localeArr.length > 0) {
                    this.mPrevPrimaryLocale = getPrimaryLanguage();
                    boolean activated = setLanguage(this.mNativeHandle, localeToString(localeArr));
                    // The native setLanguage re-creates the engine core with its multithreaded
                    // selection-list build on, which spawned 3 to 7 short-lived threads per
                    // keystroke and gave non-deterministic candidate lists; the engine's own
                    // ClearMultiThreading switches it off, so it is applied after every
                    // activation, whatever the activation returned.
                    Et9EngineOptions.setMultiThreading(this.mNativeHandle, false);
                    if (activated) {
                        this.mLanguageActive = true;
                        this.mEthiopicTranslation = EthiopicSyllables.appliesTo(localeArr[0]);
                        // Re-apply the per-engine options once after a language change; whether
                        // activation resets them inside the blob is not documented.
                        this.mEmojiPredictionApplied = null;
                        // Dictionary probe (debug): after the language activates, ask the engine
                        // directly whether common words are in the active LDB. Settles "is 'hello'
                        // missing from the dictionary" without relying on a focused text field.
                        if (BuildConfig.DEBUG) {
                            String[] probe = {"hello", "hell", "help", "the", "jello", "he'll", "hero", "teh", "vey", "adn"};
                            StringBuilder sb = new StringBuilder("DICTPROBE lang=").append(java.util.Arrays.toString(localeToString(localeArr))).append(" ->");
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
            // The blob's language table holds `in` and `iw`, never `id` or `he`.
            strArr[i2] = LocaleUtils.languageCode(localeArr[i]);
            strArr[i2 + 1] = localeArr[i].getCountry();
        }
        return strArr;
    }

    public Locale[] getLanguage() {
        enter("getLanguage");
        synchronized (sMutex) {
            return languageLocked();
        }
    }

    /** The native half of {@link #getLanguage()}; callers hold sMutex. */
    private Locale[] languageLocked() {
        String[] language = getLanguage(this.mNativeHandle);
        if (language == null) {
            return new Locale[0];
        }
        Locale[] localeArr = new Locale[language.length / 2];
        for (int i = 0; i < language.length / 2; i++) {
            int i2 = i * 2;
            localeArr[i] = new Locale(language[i2], language[i2 + 1]);
        }
        return localeArr;
    }

    /**
     * Recomputes {@link #mFacts} from the engine. Callers hold sMutex; it reads the private
     * natives and fields directly, never the public wrappers.
     */
    private void refreshFactsLocked() {
        if (this.mNativeHandle == 0L) {
            this.mFacts = EngineFacts.NONE;
            return;
        }
        Locale[] languages = languageLocked();
        List<Locale> list = Collections.unmodifiableList(java.util.Arrays.asList(languages));
        // The same default as getPrimaryLanguage(), so a caller switched to the facts sees the
        // value it saw before.
        Locale primary = languages.length > 0 ? languages[0] : Locale.ENGLISH;
        final boolean active = this.mLanguageActive;
        this.mFacts = new EngineFacts(
                primary,
                list,
                this.mIsCurrLocaleChinese,
                active && isChineseStrokeMode(this.mNativeHandle),
                active && isChineseCangjieMode(this.mNativeHandle),
                active && isChineseBPMFMode(this.mNativeHandle),
                active ? String.valueOf(getDefaultWordSeparator(this.mNativeHandle))
                        : EngineFacts.NONE.getDefaultWordSeparator(),
                this.mIsLanguageInit,
                this.mDlmAttached);
    }

    /** The form the engine's dictionary expects; identity unless an Ethiopic language is primary. */
    private String toEngineForm(String word) {
        return this.mEthiopicTranslation ? EthiopicSyllables.decompose(word) : word;
    }

    /** A cursor position inside {@code word}, moved to the same place in its engine form. */
    private int toEnginePosition(String word, int position) {
        if (!this.mEthiopicTranslation || word == null || position <= 0 || position > word.length()) {
            return position;
        }
        return EthiopicSyllables.decompose(word.substring(0, position)).length();
    }

    private String[] toEngineForm(String[] words) {
        if (!this.mEthiopicTranslation || words == null) {
            return words;
        }
        final String[] out = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            out[i] = EthiopicSyllables.decompose(words[i]);
        }
        return out;
    }

    public Locale getPrimaryLanguage() {
        Locale[] language = getLanguage();
        if (language.length > 0) {
            return language[0];
        }
        return Locale.ENGLISH;
    }

    public boolean registerLdb(String str) {
        enter("registerLdb");
        boolean zRegisterLdb;
        synchronized (sMutex) {
            // The pack installer reaches the spell checker's engine through peekSecondary(),
            // which can now be disposed between that read and this call.
            if (this.mNativeHandle == 0L) {
                return false;
            }
            zRegisterLdb = registerLdb(this.mNativeHandle, str);
        }
        return zRegisterLdb;
    }

    public void deregisterLdb(String str, String str2) {
        enter("deregisterLdb");
        synchronized (sMutex) {
            if (this.mNativeHandle == 0L) {
                return;
            }
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
        enter("isSpellingCorrect");
        boolean zIsSpellingCorrect;
        synchronized (sMutex) {
            zIsSpellingCorrect = isSpellingCorrect(this.mNativeHandle, toEngineForm(str));
        }
        return zIsSpellingCorrect;
    }

    /**
     * Whether the language dictionaries alone know {@code str}: the learned model is switched off
     * for this one check (this instance only; the other instance and the model file are untouched)
     * and back on. Words added on purpose and contact names live in the learned model too, so they
     * fail here. Where the model cannot be switched off, the ordinary check answers.
     */
    public boolean isSpellingCorrectWithoutLearnedWords(String str) {
        enter("isSpellingCorrectWithoutLearnedWords");
        synchronized (sMutex) {
            String word = toEngineForm(str);
            if (!this.mDlmAttached || !Et9Dlm.isLoaded()) {
                return isSpellingCorrect(this.mNativeHandle, word);
            }
            int off;
            try {
                off = Et9Dlm.nativeSetLearnedModelEnabled(this.mNativeHandle, false);
            } catch (Throwable t) {
                off = Et9Dlm.ERR_BLOB;
            }
            if (off != 0) {
                Log.w(TAG, "learned model could not be switched off (" + off + ")");
                return isSpellingCorrect(this.mNativeHandle, word);
            }
            try {
                return isSpellingCorrect(this.mNativeHandle, word);
            } finally {
                int on = Et9Dlm.nativeSetLearnedModelEnabled(this.mNativeHandle, true);
                if (on != 0) Log.w(TAG, "learned model could not be switched back on (" + on + ")");
            }
        }
    }

    public boolean selectionListSelectWord(int i, boolean z, String str) {
        enter("selectionListSelectWord");
        boolean zSelectionListSelectWord;
        synchronized (sMutex) {
            this.mDlmMutations++;
            zSelectionListSelectWord = selectionListSelectWord(this.mNativeHandle, i, z, toEngineForm(str));
        }
        return zSelectionListSelectWord;
    }

    public char getDefaultWordSeparator() {
        enter("getDefaultWordSeparator");
        char defaultWordSeparator;
        synchronized (sMutex) {
            defaultWordSeparator = getDefaultWordSeparator(this.mNativeHandle);
        }
        return defaultWordSeparator;
    }

    public KeyInfo[] getKeys() {
        enter("getKeys");
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
        enter("setExplicitLearning");
        boolean explicitLearning;
        synchronized (sMutex) {
            explicitLearning = setExplicitLearning(this.mNativeHandle, z);
        }
        return explicitLearning;
    }

    public boolean scanBuffer(String str) {
        enter("scanBuffer");
        boolean zScanBuffer;
        synchronized (sMutex) {
            zScanBuffer = scanBuffer(this.mNativeHandle, toEngineForm(str));
        }
        return zScanBuffer;
    }

    public boolean addWord(String str) {
        enter("addWord");
        if (str == null || str.isEmpty()) {
            return false;
        }
        synchronized (sMutex) {
            if (!mIsLanguageInit) {
                return false;
            }
            this.mDlmMutations++;
            str = toEngineForm(str);
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
        enter("wordChanged");
        synchronized (sMutex) {
            this.mDlmMutations++;
            return wordChanged(this.mNativeHandle, toEngineForm(str), toEnginePosition(str, i), toEngineForm(str2), toEngineForm(str3));
        }
    }

    public void addContactsWords(String[] strArr) {
        enter("addContactsWords");
        // A long hold like setLanguage (one native call over every contact name): a loader op.
        synchronized (sMutex) {
            if (!mIsLanguageInit) {
                if (BuildConfig.DEBUG) Log.d(TAG, "DLM not ready, skipping addContactsWords (" + strArr.length + " words)");
                return;
            }
            this.mDlmMutations++;
            addCategoryWords(this.mNativeHandle, CATEGORY_CONTACTS, toEngineForm(strArr));
        }
    }

    public void addContactsWords(String[] strArr, boolean z) {
        enter("addContactsWords");
        synchronized (sMutex) {
            if (z == this.mIsManaged) {
                addContactsWords(strArr);
            }
        }
    }

    /**
     * Whether the engine may offer the words filed under the contacts category, the names
     * {@link #addContactsWords} adds: the engine half of "Suggest from contacts", which until now
     * only started or stopped the address-book sync and left the names already learned active.
     * The choice is kept on this instance and applied again whenever the model is (re)attached,
     * since the state lives in the engine's copy of the model. Returns false when the model is
     * not attached yet (the choice is applied on attach) or the engine refused: a model that has
     * no contacts category yet answers status 93, and then there is nothing to switch off.
     */
    public boolean setContactsCategoryState(boolean enabled) {
        enter("setContactsCategoryState");
        // loadSettings calls this on every field focus with the same value. Skip the lock when
        // the attached model already holds it.
        Boolean applied = this.mContactsCategoryApplied;
        if (applied != null && applied == enabled) {
            return true;
        }
        synchronized (sMutex) {
            return setContactsChoiceLocked(enabled);
        }
    }

    /** Records the contacts choice and applies it to an attached model; callers hold sMutex. */
    private boolean setContactsChoiceLocked(boolean enabled) {
        this.mContactsCategoryEnabled = enabled;
        if (this.mNativeHandle == 0 || !this.mDlmAttached) {
            return false;
        }
        return applyContactsCategoryState();
    }

    /** Pushes the remembered contacts choice into the attached model; callers hold sMutex. */
    private boolean applyContactsCategoryState() {
        if (this.mContactsCategoryEnabled == null || this.mNativeHandle == 0) {
            return false;
        }
        boolean enabled = this.mContactsCategoryEnabled;
        boolean ok = setCategoryState(this.mNativeHandle, CATEGORY_CONTACTS, enabled);
        // Remembered only when the engine accepted it: a refusal (no contacts category in the
        // model yet) has to be retried on the next call.
        this.mContactsCategoryApplied = ok ? enabled : null;
        if (BuildConfig.DEBUG) Log.d(TAG, "contacts category " + (enabled ? "on" : "off") + " -> " + ok);
        return ok;
    }

    public boolean invalidateKeyboard(String str) {
        enter("invalidateKeyboard");
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

    /**
     * The contacts in flight and their move counts, side by side. There are one or two at a
     * time, so a scan beats a map, and nothing is boxed per touch sample (a HashMap keyed by
     * Long allocated a key per lookup and a count per move past 127). Guarded by sMutex.
     */
    private long[] mGestureMoveIds = new long[4];
    private int[] mGestureMoveCounts = new int[4];
    private int mGestureMoveSize;

    private int gestureMoveIndex(long id) {
        for (int n = 0; n < this.mGestureMoveSize; n++) {
            if (this.mGestureMoveIds[n] == id) {
                return n;
            }
        }
        return -1;
    }

    /** A contact starts: its count is 0, whatever an earlier contact of the same id left. */
    private void startGestureMoves(long id) {
        int n = gestureMoveIndex(id);
        if (n < 0) {
            if (this.mGestureMoveSize == this.mGestureMoveIds.length) {
                this.mGestureMoveIds = java.util.Arrays.copyOf(this.mGestureMoveIds, this.mGestureMoveSize * 2);
                this.mGestureMoveCounts = java.util.Arrays.copyOf(this.mGestureMoveCounts, this.mGestureMoveSize * 2);
            }
            n = this.mGestureMoveSize++;
            this.mGestureMoveIds[n] = id;
        }
        this.mGestureMoveCounts[n] = 0;
    }

    /** One more move for a contact that started; a move for an unknown contact counts nowhere. */
    private void countGestureMove(long id) {
        int n = gestureMoveIndex(id);
        if (n >= 0) {
            this.mGestureMoveCounts[n]++;
        }
    }

    /** Forgets the contact and returns its move count, 0 for one that never started. */
    private int endGestureMoves(long id) {
        int n = gestureMoveIndex(id);
        if (n < 0) {
            return 0;
        }
        int count = this.mGestureMoveCounts[n];
        int last = --this.mGestureMoveSize;
        this.mGestureMoveIds[n] = this.mGestureMoveIds[last];
        this.mGestureMoveCounts[n] = this.mGestureMoveCounts[last];
        return count;
    }

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
        enter("touchStart");
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchStart: pointer=" + j + " x=" + f + " y=" + f2 + " time=" + j2);
        boolean z;
        synchronized (sMutex) {
            startGestureMoves(j);
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
        enter("touchMove");
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchMove: pointer=" + j + " x=" + f + " y=" + f2 + " time=" + j2);
        boolean z;
        synchronized (sMutex) {
            countGestureMove(j);
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
        enter("touchEnd");
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchEnd: pointer=" + j + " x=" + f + " y=" + f2 + " time=" + j2);
        boolean z;
        synchronized (sMutex) {
            int n = endGestureMoves(j);
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
        enter("touchCancel");
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchCancel: pointer=" + j);
        boolean z;
        synchronized (sMutex) {
            endGestureMoves(j);
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
     * The second matched the then-shipped KEY2 uneven-band layout (now assets/kdb/keypad_pkb_athena_test.xml) exactly (1080x450, 30 keys, 108x180
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
        enter("syncKeyboardLayout");
        final boolean isPkb = !z;   // engine wants isPkb; callers hand us isTouchKb
        // A change of keyboard can change whether the active letter layout's KDBs apply; any
        // switch it queues is on the registration thread awaited before the load.
        dev.bbkb.ime.core.layout.LetterLayoutEngineSync.beforeEngineLayoutSync(isPkb);
        sDbgKbZ = z; sDbgKbStr = str;   // remember for the RE re-parse trigger
        // The sync runs on the engine thread, so every tap, swipe or key posted after it is fed
        // against the new layout by FIFO order alone. Behind a language load it waits for sMutex
        // on that thread; the main thread never waits for it.
        // Device KDB variants register on a worker; make sure the registry is populated
        // before the engine's first SetKdbNum. No-op once registration has completed.
        Xt9KdbVariant.awaitReady(2000);
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.syncKeyboardLayout: isTouchKb=" + z + " locale='" + str + "' width=" + i + " height=" + i2 + " kdbVariant='" + Xt9KdbVariant.applied() + "'");
        synchronized (sMutex) {
            return applyKeyboardLayoutLocked(z, isPkb, str, i, i2);
        }
    }

    /** The native half of {@link #syncKeyboardLayout}; callers hold sMutex. */
    private boolean applyKeyboardLayoutLocked(boolean z, boolean isPkb, String str, int i, int i2) {
        if (this.mNativeHandle == 0) {
            return false;   // disposed before this op ran
        }
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

    public boolean loadKeyboardLayout(boolean z) {
        enter("loadKeyboardLayout");
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
        enter("setContextBuffer");
        boolean contextBuffer;
        synchronized (sMutex) {
            contextBuffer = setContextBuffer(this.mNativeHandle, toEngineForm(str));
        }
        return contextBuffer;
    }

    public boolean setEmojiPredictionEnabled(boolean z) {
        enter("setEmojiPredictionEnabled");
        // Same per-field caller and same reason as setContactsCategoryState: no lock when the
        // engine already holds the value.
        Boolean applied = this.mEmojiPredictionApplied;
        if (applied != null && applied == z) {
            return true;
        }
        synchronized (sMutex) {
            return applyEmojiPredictionLocked(z);
        }
    }

    /** The native half of {@link #setEmojiPredictionEnabled(boolean)}; callers hold sMutex. */
    private boolean applyEmojiPredictionLocked(boolean z) {
        if (this.mNativeHandle == 0) {
            return false;
        }
        boolean emojiPredictionEnabled = setEmojiPredictionEnabled(this.mNativeHandle, z);
        this.mEmojiPredictionApplied = emojiPredictionEnabled ? z : null;
        return emojiPredictionEnabled;
    }

    /**
     * Check if the DLM (Dynamic Learning Model) is initialized and ready for word operations.
     * @return true if language has been set and DLM is loaded
     */
    public boolean isDLMReady() {
        enter("isDLMReady");
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
        enter("shouldReloadDLMFile");
        synchronized (sMutex) {
            boolean zIsChineseLocale = isChineseLocale();
            if (this.mIsCurrLocaleChinese == zIsChineseLocale && this.mIsLanguageInit) {
                return false;
            }
            this.mIsLanguageInit = true;
            this.mIsCurrLocaleChinese = zIsChineseLocale;
            refreshFactsLocked();
            return true;
        }
    }

    public boolean loadDLMFromFile() {
        enter("loadDLMFromFile");
        synchronized (sMutex) {
            // A disposed engine (the spell checker's, released while idle) has no model to attach.
            if (this.mNativeHandle == 0L || !reattachDLM()) {
                return false;
            }
            this.mDlmAttached = true;
            refreshFactsLocked();
            if (this.mCallbackHandler != null) {
                this.mCallbackHandler.onManagedModeChanged(this.mIsManaged);
            }
            return true;
        }
    }

    public int getDLMWordCount(boolean z) {
        enter("getDLMWordCount");
        int dLMWordCount;
        synchronized (sMutex) {
            dLMWordCount = getDLMWordCount(this.mNativeHandle, z);
        }
        return dLMWordCount;
    }

    public String getDLMWord(int i) {
        enter("getDLMWord");
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
        enter("deleteDLMWord");
        boolean zDeleteDLMWord;
        synchronized (sMutex) {
            this.mDlmMutations++;
            zDeleteDLMWord = deleteDLMWord(this.mNativeHandle, str);
        }
        return zDeleteDLMWord;
    }

    /**
     * Every word in the dynamic language model.
     *
     * <p>The blob's {@code getDLMWord(i)} is not an indexed read. It hands {@code i} to
     * {@code ET9AWDLMGetWord} in the in/out halfword that engine API uses as a cursor command:
     * 0 rewinds to the first word, anything else steps to the next one, and the engine rejects
     * a value above 64 (status 26) because that field doubles as a word length. Walking the
     * model as {@code getDLMWord(0), getDLMWord(1), …, getDLMWord(n-1)} therefore worked only by
     * accident, and stopped dead at the 66th word: the Learned words screen and the Words
     * backup never saw more than 65 entries (owner's KEY2, 2026-10-12; reproduced on the
     * emulator 2026-10-17 with 72 learned words: indices 65–71 came back null). Rewind once,
     * then step.
     */
    public ArrayList<String> getDLMWords() {
        enter("getDLMWords");
        // The Learned words screen and the daily backup read up to 12,882 words, one JNI call
        // each. Holding sMutex across the whole walk (65 to 250 ms) would make the engine thread's
        // next key wait for it, so the walk releases the lock every chunk. The engine's word
        // cursor is engine state (rewind with 0, then step with 1); this runs on the loader
        // thread and nothing else enumerates, so one walk is enough. An engine-thread op that
        // changes the model between chunks (a word learned or deleted) can shift the cursor by
        // that one change; the walk does not restart for it.
        final ArrayList<String> words = new ArrayList<>();
        final int started = this.mDlmMutations;
        final int count;
        synchronized (sMutex) {
            if (this.mNativeHandle == 0L) {
                return words;
            }
            count = getDLMWordCount(this.mNativeHandle, false);
        }
        int i = 0;
        while (i < count) {
            synchronized (sMutex) {
                if (this.mNativeHandle == 0L) {
                    break;
                }
                final int end = Math.min(count, i + DLM_ENUMERATION_CHUNK);
                for (; i < end; i++) {
                    String dLMWord = getDLMWord(this.mNativeHandle, i == 0 ? 0 : 1);
                    if (dLMWord != null) {
                        words.add(dLMWord);
                    }
                }
            }
        }
        if (BuildConfig.DEBUG && this.mDlmMutations != started) {
            Log.d(TAG, "getDLMWords: the model changed during the walk ("
                    + (this.mDlmMutations - started) + " change(s)); " + words.size() + " words read");
        }
        return words;
    }

    /** {@link #importDLM} succeeded. */
    public static final int DLM_IMPORT_OK = 0;
    /** No engine, no attached model, or the shim could not reach the engine on this phone. */
    public static final int DLM_IMPORT_NOT_AVAILABLE = -1;
    /** The engine refused the data: checksum or structure (its status 22). */
    public static final int DLM_IMPORT_CORRUPT = 22;
    /** The data was written by a newer engine (its status 91). */
    public static final int DLM_IMPORT_NEWER_VERSION = 91;

    /**
     * Whether the engine's own learned-model export and import ({@link #exportDLM},
     * {@link #importDLM}) can reach the engine on this phone: the model is attached and the shim
     * recognises the engine build. False on any phone whose engine library is not the one the
     * shim was pinned to, where both calls do nothing.
     */
    public boolean isDLMTransferAvailable() {
        enter("isDLMTransferAvailable");
        synchronized (sMutex) {
            if (this.mNativeHandle == 0 || !this.mDlmAttached || !Et9Dlm.isLoaded()) return false;
            try {
                return Et9Dlm.nativeExportMaxSize(this.mNativeHandle) > 0;
            } catch (Throwable t) {
                if (BuildConfig.DEBUG) Log.w(TAG, "isDLMTransferAvailable", t);
                return false;
            }
        }
    }

    /**
     * The whole learned model in the engine's own portable form (every word with its use count,
     * language, category and quality, the categories, the next-word pairs), as the engine's
     * {@code ET9AWDLMExport} writes it: a byte stream with a trailing checksum, independent of
     * the process pointers the model file embeds. Null when the model is not attached or the
     * engine cannot be reached. Read it with {@code LearnedModelStream}.
     */
    public byte[] exportDLM() {
        enter("exportDLM");
        synchronized (sMutex) {
            if (this.mNativeHandle == 0 || !this.mDlmAttached || !Et9Dlm.isLoaded()) return null;
            try {
                return Et9Dlm.nativeExport(this.mNativeHandle);
            } catch (Throwable t) {
                if (BuildConfig.DEBUG) Log.w(TAG, "exportDLM failed", t);
                return null;
            }
        }
    }

    /**
     * Replaces the learned model with one from {@link #exportDLM} (this phone's or another's),
     * then re-attaches the model so every engine cache is rebuilt from the file. Returns
     * {@link #DLM_IMPORT_OK}, {@link #DLM_IMPORT_CORRUPT}, {@link #DLM_IMPORT_NEWER_VERSION},
     * another engine status, or {@link #DLM_IMPORT_NOT_AVAILABLE}.
     *
     * <p>The engine resets the model BEFORE it checks the data, so a refused import leaves the
     * model empty: callers validate the stream first ({@code LearnedModelStream.parse}) and keep an
     * export of the current model to put back on a refusal — {@code LearnedModelTransfer} does
     * both. The secondary (spell-checker) engine maps the same file and has to be re-attached
     * afterwards too; that is also the transfer's job.
     */
    public int importDLM(byte[] data) {
        enter("importDLM");
        synchronized (sMutex) {
            if (this.mNativeHandle == 0 || !this.mDlmAttached || !Et9Dlm.isLoaded() || data == null) {
                return DLM_IMPORT_NOT_AVAILABLE;
            }
            this.mDlmMutations++;
            int status;
            try {
                status = Et9Dlm.nativeImport(this.mNativeHandle, data);
            } catch (Throwable t) {
                if (BuildConfig.DEBUG) Log.w(TAG, "importDLM failed", t);
                return DLM_IMPORT_NOT_AVAILABLE;
            }
            // Attached or not, the engine rewrote the model in place: re-attach so the caches
            // (hash buckets, prediction cache) are rebuilt from what the file holds now.
            reattachDLM();
            if (BuildConfig.DEBUG) Log.i(TAG, "importDLM: status=" + status + " words=" + getDLMWordCount(false));
            return status < 0 ? DLM_IMPORT_NOT_AVAILABLE : status;
        }
    }

    public String getDLMFilePath() {
        enter("getDLMFilePath");
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
        enter("resetDynamicModel");
        synchronized (sMutex) {
            detachDLMFile(this.mNativeHandle);
            java.io.File dlmFile = new java.io.File(nuanceDir, getDLMFilePath());
            if (dlmFile.exists() && !dlmFile.delete()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "resetDynamicModel: could not delete " + dlmFile);
            }
            boolean reloaded = loadDLMFromFile();
            refreshFactsLocked();
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
        enter("isChineseStrokeMode");
        boolean zIsChineseStrokeMode;
        synchronized (sMutex) {
            zIsChineseStrokeMode = isChineseStrokeMode(this.mNativeHandle);
        }
        return zIsChineseStrokeMode;
    }

    public boolean isChineseCangjieMode() {
        enter("isChineseCangjieMode");
        boolean zIsChineseCangjieMode;
        synchronized (sMutex) {
            zIsChineseCangjieMode = isChineseCangjieMode(this.mNativeHandle);
        }
        return zIsChineseCangjieMode;
    }

    public boolean isChineseBPMFMode() {
        enter("isChineseBPMFMode");
        boolean zIsChineseBPMFMode;
        synchronized (sMutex) {
            zIsChineseBPMFMode = isChineseBPMFMode(this.mNativeHandle);
        }
        return zIsChineseBPMFMode;
    }

    public boolean isBpmfUpperCaseSymbol(char c) {
        enter("isBpmfUpperCaseSymbol");
        boolean zIsBpmfUpperCaseSymbol;
        synchronized (sMutex) {
            zIsBpmfUpperCaseSymbol = isBpmfUpperCaseSymbol(this.mNativeHandle, c);
        }
        return zIsBpmfUpperCaseSymbol;
    }

    public String convertBpmfSymbolToLower(char c) {
        enter("convertBpmfSymbolToLower");
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
            this.mContactsCategoryApplied = null;   // the state lives in the copy being replaced
            this.mDlmMutations++;
            z = detachDLMFile(this.mNativeHandle) && attachDLMFile(this.mNativeHandle, getDLMFilePath());
            if (z) {
                // The category states live in the attached copy of the model.
                applyContactsCategoryState();
            }
            refreshFactsLocked();
        }
        return z;
    }

    public void deleteWorkDLMWordsExplicitly(ArrayList<String> arrayList) {
        enter("deleteWorkDLMWordsExplicitly");
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
        enter("getWorkDLMWordsExplicitly");
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
        enter("setInputMethod");
        synchronized (sMutex) {
            setInputMethod(this.mNativeHandle, str);
            // The Chinese input modes are what this switches.
            refreshFactsLocked();
        }
    }

    public void setShiftState(int i) {
        enter("setShiftState");
        synchronized (sMutex) {
            setShiftState(this.mNativeHandle, i);
        }
    }
}
