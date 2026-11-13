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

/** JNI wrapper for the Nuance XT9 engine blob (libnative-lib.so); the blob binds to this class by name. */

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
     * {@link #getSelectionListSources} value of a learned-model word (learned, typed-once or added).
     * Language-dictionary words and contact names are 2, the typed word 9.
     */
    public static final int WORD_SOURCE_LEARNED_MODEL = 1;
    public static final String QUICK_CANGJIE_VARIANT = "quick_cangjie";
    private static final String TAG = "NuanceSDK";
    // Volatile, written without sMutex: see setCallbackHandler.
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
        final Map<String, int[]> map = new HashMap<>();
        {
            map.put(NuanceSDK.DEVICE_MERCURY, new int[]{1080, 324});
            // Never reaches the engine (the PKB path passes (0,0)); only logTapKey's debug line reads it.
            // Athena 425: letter rows at sensor Y ~69/213/354 in 1080x525 fit authored 54/162/270 of a
            // 324-high layout (authored_y = sensor_y * 324 / H). Not valid for today's 1080x450 KDB.
            map.put(NuanceSDK.DEVICE_ATHENA, new int[]{1080, 425});
            map.put(NuanceSDK.DEVICE_VENICE, new int[]{1420, 609});
        }
        return Collections.unmodifiableMap(map);
    }
    /**
     * Serialises every call into the blob; static because both engines share native state. Only
     * the engine and loader threads ({@link EngineThreads}) take it, never the main thread.
     */
    private static final Object sMutex = new Object();

    /**
     * The first statement of every public wrapper that takes {@link #sMutex}. In debug builds it
     * throws unless the caller is the engine thread or the loader thread: the blob keeps pointers
     * into the stack of the thread that last called it, so a call from any thread that can exit
     * (a pool thread, a binder thread, a per-service startup pool) leaves the next caller reading
     * a dead stack. In release builds it compiles to nothing.
     */
    private static void enter(String what) {
        EngineThreads.assertEngineCapable(what);
    }

    /**
     * Engine state the main thread may read without a lock; refreshed under sMutex by every call
     * that can change it before that call returns.
     */
    private volatile EngineFacts mFacts = EngineFacts.NONE;

    /**
     * Whether a native setLanguage has ever succeeded here; until then the facts skip the
     * input-mode and separator natives, never called on an engine with no language. Under sMutex.
     */
    private boolean mLanguageActive = false;

    /** The latest published facts; {@link EngineFacts#NONE} before init and after dispose. */
    public EngineFacts getFacts() {
        return this.mFacts;
    }

    private static final java.util.regex.Pattern WHITESPACE =
            java.util.regex.Pattern.compile("\\s+");

    /**
     * Monotonic count of input-buffer mutations (clear, clearOneSymbol, processKeyBySymbol), bumped
     * under sMutex. The suggestion worker samples it after its own replay to detect the buffer
     * being rewritten inside its non-atomic read window. Static: the engine buffer is process-global.
     */
    private static volatile long sInputBufferEpoch = 0L;

    /** @see #sInputBufferEpoch */
    public static long getInputBufferEpoch() {
        return sInputBufferEpoch;
    }

    /**
     * Monotonic generation of the selection list, bumped under sMutex by {@link #buildSelectionList()}.
     * {@code selectionListSelectWord} takes a raw index, so a {@link WordInfo} stamped with an older
     * generation must be learned by content instead.
     */
    private static volatile long sSelectionListGeneration = 0L;

    /** @see #sSelectionListGeneration */
    public static long getSelectionListGeneration() {
        return sSelectionListGeneration;
    }
    private Locale mPrevPrimaryLocale = null;

    // True while an Ethiopic language is primary: words crossing the engine boundary go through
    // EthiopicSyllables. Under sMutex only.
    private boolean mEthiopicTranslation = false;
    private boolean mIsExplicitLearning = false;
    /**
     * Whether {@link #loadDLMFromFile} has attached the learned model to this engine. Written
     * under sMutex; volatile so {@link #setCallbackHandler} can read it without the lock.
     */
    private volatile boolean mDlmAttached = false;

    /** The last {@link #setContactsCategoryState} choice, reapplied on every model attach; null = engine default. */
    private Boolean mContactsCategoryEnabled = null;
    /** Bumped by every learned-model change, so {@link #getDLMWords} can log a change between chunks. */
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
        /** @param word the word the engine auto-committed */
        void onAutoCommitWord(String word);
    }

    
    public interface ManagedModeCallback {
        /** @param isManaged true for managed (work profile) mode */
        void onManagedModeChanged(boolean isManaged);
    }

    // Natives: handle is the engine handle; the other parameters match the public wrappers below.
    private native int addCategoryWords(long handle, char category, String[] words);

    private native boolean addExplicitSymb(long handle, char symbol);

    private native boolean addWord(long handle, String word);

    private native boolean attachDLMFile(long handle, String dlmPath);

    private native String buildHangul(long handle);

    private native int buildSelectionList(long handle);

    private native boolean clear(long handle);

    private native boolean clearOneSymbol(long handle);

    private native String compatibilityJamoToJamo(String jamo);

    private native String convertBpmfSymbolToLower(long handle, char symbol);

    private native String decodeHangul(long handle, String text, boolean forDisplay);

    private native boolean deleteDLMWord(long handle, String word);

    private native void deregisterLdb(long handle, String[] languageAndCountry);

    private native boolean detachDLMFile(long handle);

    private native void dispose(long handle);

    private native String getDLMWord(long handle, int cursorCommand);

    private native int getDLMWordCount(long handle, boolean explicitOnly);

    private native char getDefaultWordSeparator(long handle);

    private native String getInlineWord(long handle);

    private native KeyInfo[] getKeys(long handle);

    private native String[] getLanguage(long handle);

    private native int getSelectionListSize(long handle);

    private native WordInfo getSelectionListWord(long handle, int index);

    private native long init(AssetManager assetManager, String filesDirPath, String noBackupFilesDirPath);

    private native boolean invalidateKeyboard(long handle, String path);

    private native boolean isBpmfUpperCaseSymbol(long handle, char symbol);

    private native boolean isChineseBPMFMode(long handle);

    private native boolean isChineseCangjieMode(long handle);

    private native boolean isChineseStrokeMode(long handle);

    private native boolean isSpellingCorrect(long handle, String word);

    private native boolean loadKeyboardLayout(long handle, boolean isPkb, String locale);

    private static native void nativeInit();

    private native boolean processKeyBySymbol(long handle, char symbol);

    private native boolean registerLdb(long handle, String ldbPath);

    private native boolean scanBuffer(long handle, String text);

    private native boolean selectionListSelectWord(long handle, int index, boolean isExplicitSelection, String spelling);

    private native boolean setCategoryState(long handle, char category, boolean enabled);

    private native boolean setContextBuffer(long handle, String contextText);

    private native boolean setEmojiPredictionEnabled(long handle, boolean enabled);

    private native boolean setExplicitLearning(long handle, boolean enabled);

    private native boolean setInputMethod(long handle, String inputMethodName);

    private native boolean setKeyboardSize(long handle, short width, short height);

    private native boolean setLanguage(long handle, String[] localeNames);

    private native boolean setShiftState(long handle, int shiftState);

    private native boolean touchCancel(long handle, long pointerId);

    private native boolean touchEnd(long handle, long pointerId, float x, float y, long eventTime);

    private native boolean touchMove(long handle, long pointerId, float x, float y, long eventTime);

    private native boolean touchStart(long handle, long pointerId, float x, float y, long eventTime);

    private native boolean wordChanged(long handle, String textBeforeCursor, int cursorPosition, String originalWord, String currentWord);

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
        // No sMutex: called on the main thread, and the lock is held for whole dictionary loads.
        // This writes the handler then reads mDlmAttached; loadDLMFromFile does the reverse, so at
        // least one delivers onManagedModeChanged (both is harmless).
        this.mCallbackHandler = callback;
        boolean replay = callback != null && this.mDlmAttached;
        // The model may already be attached (a settings screen set a language first, so the later
        // setLanguage skips loadDLMFromFile); replay or no learner runs for the process.
        if (replay) {
            callback.onManagedModeChanged(this.mIsManaged);
        }
    }

    public void setAutoCommitCallbackHandler(AutoCommitCallback autoCommitCallback) {
        // Volatile write, no sMutex needed.
        this.mAutoCommitHandler = autoCommitCallback;
    }

    /**
     * JNI callback from the blob; do not remove.
     * @param word the auto-committed word
     */
    public void updateAutoCommittedString(String word) {
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.updateAutoCommittedString: str=" + word + " mAutoCommitHandler=" + this.mAutoCommitHandler);
        AutoCommitCallback autoCommitCallback = this.mAutoCommitHandler;
        if (autoCommitCallback == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Callback handler is null.");
        } else {
            autoCommitCallback.onAutoCommitWord(word);
        }
    }

    private void observeNuanceFiles(Context context) {
        final File nuanceDir = new File(context.getFilesDir(), DYNAMIC_MODEL_ABSTRACT_DIR);
        // Create it: this is never retried, so without the dir the watcher is absent for the process.
        if (!nuanceDir.isDirectory() && !nuanceDir.mkdirs()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Cannot create nuance dir: " + nuanceDir.getAbsolutePath());
            return;
        }
        final String nuancePath = nuanceDir.getAbsolutePath();
        final int mask = FileObserver.CLOSE_WRITE | FileObserver.CREATE | FileObserver.MOVED_TO;
        // The File overload exists only on API 29+, where the String one is deprecated.
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
        for (String name : DYNAMIC_MODEL_FILE_ARRAY) {
            if (fullPath.endsWith(name)) {
                // FileObserver runs on its own thread; the engine is only called from the engine thread.
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

    /** @param isManaged true for managed (work profile) mode */
    public void setIsManaged(boolean isManaged) {
        enter("setIsManaged");
        synchronized (sMutex) {
            this.mIsManaged = isManaged;
        }
    }

    /** @param isExplicitLearning whether explicit learning is on; remembered and reapplied for Chinese */
    public void setIsExplicitLearning(boolean isExplicitLearning) {
        enter("setIsExplicitLearning");
        synchronized (sMutex) {
            this.mIsExplicitLearning = isExplicitLearning;
            setExplicitLearning(this.mNativeHandle, isExplicitLearning);
        }
    }

    /** @param isPkb true while the physical keypad is the input */
    public void setIsPkb(boolean isPkb) {
        enter("setIsPkb");
        synchronized (sMutex) {
            this.mIsPkb = isPkb;
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
            sSelectionListGeneration++;   // the list just changed identity
            if (BuildConfig.DEBUG) {
                // Recognition output for diffing the owned decoder; gesture results arrive as the inline
                // word (count 0). View: adb logcat -s XT9WORDS:I XT9OWNED:I (deduped)
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

    /**
     * @param text the jamo sequence to decode
     * @param forDisplay engine flag: false from the keypad's per-key decode, true from the flick strip
     */
    public String decodeHangul(String text, boolean forDisplay) {
        enter("decodeHangul");
        String strDecodeHangul;
        synchronized (sMutex) {
            strDecodeHangul = decodeHangul(this.mNativeHandle, text, forDisplay);
        }
        return strDecodeHangul;
    }

    /** @param jamo a compatibility jamo; {@code CODE_NULL} when it has no conjoining form */
    public char compatibilityJamoToJamoTransform(char jamo) {
        enter("compatibilityJamoToJamoTransform");
        char cCharAt;
        synchronized (sMutex) {
            String strCompatibilityJamoToJamo = compatibilityJamoToJamo(Character.toString(jamo));
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
     * The source of each of the first {@code count} words of the last built list
     * ({@link #WORD_SOURCE_LEARNED_MODEL} etc.), which the blob's word read omits; null if unreachable.
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

    /** @param index index into the current selection list */
    public WordInfo getSelectionListWord(int index) {
        enter("getSelectionListWord");
        WordInfo selectionListWord;
        synchronized (sMutex) {
            selectionListWord = getSelectionListWord(this.mNativeHandle, index);
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

    /** @param symbol the typed symbol; decomposed first while an Ethiopic language is primary */
    public boolean processKeyBySymbol(char symbol) {
        enter("processKeyBySymbol");
        boolean zProcessKeyBySymbol;
        synchronized (sMutex) {
            sInputBufferEpoch++;
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "IME:nuanceSDK:processKey" +
                    " char='" + symbol + "'" +
                    " handle=" + this.mNativeHandle +
                    " thread=" + Thread.currentThread().getName());
            }
            final String symbols = this.mEthiopicTranslation ? EthiopicSyllables.decompose(String.valueOf(symbol)) : null;
            if (symbols != null && symbols.length() > 1) {
                zProcessKeyBySymbol = true;
                for (int i = 0; i < symbols.length(); i++) {
                    zProcessKeyBySymbol &= processKeyBySymbol(this.mNativeHandle, symbols.charAt(i));
                }
            } else {
                zProcessKeyBySymbol = processKeyBySymbol(this.mNativeHandle, symbol);
            }
        }
        return zProcessKeyBySymbol;
    }

    /** @param symbol the symbol to add as typed */
    public boolean addExplicitSymb(char symbol) {
        enter("addExplicitSymb");
        boolean zAddExplicitSymb;
        synchronized (sMutex) {
            zAddExplicitSymb = addExplicitSymb(this.mNativeHandle, symbol);
        }
        return zAddExplicitSymb;
    }

    public boolean clearOneSymbol() {
        enter("clearOneSymbol");
        boolean zClearOneSymbol;
        synchronized (sMutex) {
            sInputBufferEpoch++;
            zClearOneSymbol = clearOneSymbol(this.mNativeHandle);
        }
        return zClearOneSymbol;
    }

    public boolean clear() {
        enter("clear");
        boolean zClear;
        synchronized (sMutex) {
            sInputBufferEpoch++;
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

    /**
     * @param isPkb true for the physical keypad, which always passes (0,0)
     * @param width keyboard width
     * @param height keyboard height
     */
    public boolean setKeyboardSize(boolean isPkb, short width, short height) {
        enter("setKeyboardSize");
        synchronized (sMutex) {
            if (isPkb) {
                // PKB: (0,0) means a 1:1 sensor-to-authored mapping. The athena KDB is authored
                // 1080x450 to match the touch surface anchored at the top; any scaling stretches it.
                return setKeyboardSize(this.mNativeHandle, (short) 0, (short) 0);
            }
            return setKeyboardSize(this.mNativeHandle, width, height);
        }
    }

    public boolean setLanguage(Locale[] locales) {
        enter("setLanguage");
        // A long hold (whole LDB + DLM load) on the loader thread; engine-thread ops wait for it.
        synchronized (sMutex) {
            try {
                return setLanguageLocked(locales);
            } finally {
                // On every path, published before setLanguage returns.
                refreshFactsLocked();
            }
        }
    }

    private boolean setLanguageLocked(Locale[] locales) {
        synchronized (sMutex) {
            if (locales != null) {
                if (locales.length > 0) {
                    this.mPrevPrimaryLocale = getPrimaryLanguage();
                    boolean activated = setLanguage(this.mNativeHandle, localeToString(locales));
                    // Native setLanguage turns multithreaded list builds back on (3 to 7 threads per
                    // keystroke, non-deterministic lists), so switch it off after every activation.
                    Et9EngineOptions.setMultiThreading(this.mNativeHandle, false);
                    if (activated) {
                        this.mLanguageActive = true;
                        this.mEthiopicTranslation = EthiopicSyllables.appliesTo(locales[0]);
                        // Re-apply per-engine options: activation may reset them inside the blob.
                        this.mEmojiPredictionApplied = null;
                        // Debug: log whether common words are in the active LDB.
                        if (BuildConfig.DEBUG) {
                            String[] probe = {"hello", "hell", "help", "the", "jello", "he'll", "hero", "teh", "vey", "adn"};
                            StringBuilder sb = new StringBuilder("DICTPROBE lang=").append(java.util.Arrays.toString(localeToString(locales))).append(" ->");
                            for (String w : probe) sb.append(' ').append(w).append('=').append(isSpellingCorrect(this.mNativeHandle, w));
                            Log.i(TAG, sb.toString());
                        }
                        checkAndUpdateLearningStatus();
                        if (!shouldReloadDLMFile()) {
                            return true;
                        }
                        // shouldReloadDLMFile() latches mIsLanguageInit before the load; release it on
                        // failure so the next setLanguage retries instead of running with no learner.
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

    private String[] localeToString(Locale[] locales) {
        String[] strArr = new String[locales.length * 2];
        for (int i = 0; i < locales.length; i++) {
            int i2 = i * 2;
            // The blob's language table holds `in` and `iw`, never `id` or `he`.
            strArr[i2] = LocaleUtils.languageCode(locales[i]);
            strArr[i2 + 1] = locales[i].getCountry();
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

    /** Recomputes {@link #mFacts}. Callers hold sMutex; uses the private natives, never the public wrappers. */
    private void refreshFactsLocked() {
        if (this.mNativeHandle == 0L) {
            this.mFacts = EngineFacts.NONE;
            return;
        }
        Locale[] languages = languageLocked();
        List<Locale> list = Collections.unmodifiableList(java.util.Arrays.asList(languages));
        // Same default as getPrimaryLanguage().
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

    /** @param ldbPath path of the language pack's LDB file */
    public boolean registerLdb(String ldbPath) {
        enter("registerLdb");
        boolean zRegisterLdb;
        synchronized (sMutex) {
            // The pack installer reaches the spell checker's engine via peekSecondary(), which can be
            // disposed between that read and this call.
            if (this.mNativeHandle == 0L) {
                return false;
            }
            zRegisterLdb = registerLdb(this.mNativeHandle, ldbPath);
        }
        return zRegisterLdb;
    }

    /**
     * @param language language code
     * @param country country code, or null
     */
    public void deregisterLdb(String language, String country) {
        enter("deregisterLdb");
        synchronized (sMutex) {
            if (this.mNativeHandle == 0L) {
                return;
            }
            String[] strArr = new String[2];
            strArr[0] = language;
            if (country == null) {
                country = "";
            }
            strArr[1] = country;
            deregisterLdb(this.mNativeHandle, strArr);
        }
    }

    /** @param word the word to check */
    public boolean isSpellingCorrect(String word) {
        enter("isSpellingCorrect");
        boolean zIsSpellingCorrect;
        synchronized (sMutex) {
            zIsSpellingCorrect = isSpellingCorrect(this.mNativeHandle, toEngineForm(word));
        }
        return zIsSpellingCorrect;
    }

    /**
     * Whether the language dictionaries alone know {@code inputWord}: the learned model (which also holds
     * added words and contacts) is switched off for this check on this instance only, then back on.
     * Falls back to the ordinary check if it cannot be switched off.
     */
    public boolean isSpellingCorrectWithoutLearnedWords(String inputWord) {
        enter("isSpellingCorrectWithoutLearnedWords");
        synchronized (sMutex) {
            String word = toEngineForm(inputWord);
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

    /**
     * @param index raw index into the current selection list (check the list generation first)
     * @param isExplicitSelection engine select flag; callers pass true
     * @param spelling the word's spell
     */
    public boolean selectionListSelectWord(int index, boolean isExplicitSelection, String spelling) {
        enter("selectionListSelectWord");
        boolean zSelectionListSelectWord;
        synchronized (sMutex) {
            this.mDlmMutations++;
            zSelectionListSelectWord = selectionListSelectWord(this.mNativeHandle, index, isExplicitSelection, toEngineForm(spelling));
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
            // Logs the loaded KDB geometry (ground truth for the owned resolver). View: adb logcat -s XT9KEYS:I
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

    /** @param enabled whether explicit learning is on */
    public boolean setExplicitLearning(boolean enabled) {
        enter("setExplicitLearning");
        boolean explicitLearning;
        synchronized (sMutex) {
            explicitLearning = setExplicitLearning(this.mNativeHandle, enabled);
        }
        return explicitLearning;
    }

    /** @param text text for the engine to scan and learn from */
    public boolean scanBuffer(String text) {
        enter("scanBuffer");
        boolean zScanBuffer;
        synchronized (sMutex) {
            zScanBuffer = scanBuffer(this.mNativeHandle, toEngineForm(text));
        }
        return zScanBuffer;
    }

    /** @param text word or phrase to add to the learned model; phrases are added word by word */
    public boolean addWord(String text) {
        enter("addWord");
        if (text == null || text.isEmpty()) {
            return false;
        }
        synchronized (sMutex) {
            if (!mIsLanguageInit) {
                return false;
            }
            this.mDlmMutations++;
            text = toEngineForm(text);
            // The DLM has no phrases; every non-empty token is added, single letters included.
            if (text.contains(" ")) {
                boolean anyAdded = false;
                for (String word : WHITESPACE.split(text)) {
                    if (!word.isEmpty()) {
                        anyAdded |= addWord(this.mNativeHandle, word);
                    }
                }
                return anyAdded;
            }
            return addWord(this.mNativeHandle, text);
        }
    }

    /**
     * @param textBeforeCursor the text holding the recorrected word
     * @param cursorPosition the recorrection cursor position in {@code textBeforeCursor}
     * @param originalWord the original word
     * @param currentWord the replacement word
     */
    public boolean wordChanged(String textBeforeCursor, int cursorPosition, String originalWord, String currentWord) {
        enter("wordChanged");
        synchronized (sMutex) {
            this.mDlmMutations++;
            return wordChanged(this.mNativeHandle, toEngineForm(textBeforeCursor), toEnginePosition(textBeforeCursor, cursorPosition), toEngineForm(originalWord), toEngineForm(currentWord));
        }
    }

    public void addContactsWords(String[] names) {
        enter("addContactsWords");
        // A long hold (one native call over every contact name): a loader op.
        synchronized (sMutex) {
            if (!mIsLanguageInit) {
                if (BuildConfig.DEBUG) Log.d(TAG, "DLM not ready, skipping addContactsWords (" + names.length + " words)");
                return;
            }
            this.mDlmMutations++;
            addCategoryWords(this.mNativeHandle, CATEGORY_CONTACTS, toEngineForm(names));
        }
    }

    /**
     * @param names contact names
     * @param isWorkProfile true for work-profile contacts; added only when it matches the managed mode
     */
    public void addContactsWords(String[] names, boolean isWorkProfile) {
        enter("addContactsWords");
        synchronized (sMutex) {
            if (isWorkProfile == this.mIsManaged) {
                addContactsWords(names);
            }
        }
    }

    /**
     * Engine half of "Suggest from contacts": whether words in the contacts category may be offered.
     * Kept and reapplied on every model attach. False when not attached yet or refused (status 93
     * means the model has no contacts category yet).
     */
    public boolean setContactsCategoryState(boolean enabled) {
        enter("setContactsCategoryState");
        // Called on every field focus with the same value: skip the lock when the model already holds it.
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
        // Remembered only when accepted, so a refusal (no contacts category yet) is retried.
        this.mContactsCategoryApplied = ok ? enabled : null;
        if (BuildConfig.DEBUG) Log.d(TAG, "contacts category " + (enabled ? "on" : "off") + " -> " + ok);
        return ok;
    }

    /** @param path path of the changed model file */
    public boolean invalidateKeyboard(String path) {
        enter("invalidateKeyboard");
        synchronized (sMutex) {
            if (this.mNativeHandle == 0L) {
                if (BuildConfig.DEBUG) Log.w(TAG, "invalidateKeyboard called with no native handle; ignoring path=" + path);
                return false;
            }
            return invalidateKeyboard(this.mNativeHandle, path);
        }
    }

    // Stray-touch filter: taps and edge blips produce 0-2 moves. Such contacts are cancelled, not
    // ended, so they never become 1-point gesture records. Both gesture paths funnel through here.
    private static final int MIN_GESTURE_MOVES = 3;

    /** Contacts in flight and their move counts as parallel arrays (no boxing per sample). Under sMutex. */
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

    // Debug: log which KDB key a tap resolves to, mirroring the engine's mapping. Tag: XT9TAPKEY.
    private KeyInfo[] mDbgKeys;
    private String mLastWordsLog;   // dedup for the deflooded XT9WORDS recognition log
    private static boolean sDbgKbZ;         // last syncKeyboardLayout args (to reload the same layout for RE)
    private static String  sDbgKbStr = "";
    private static boolean sDescWatchDone;  // one-shot: caught the NKL+0x4c descriptor writer yet?

    private void logTapKey(float x, float y) {
        KeyInfo[] keys = mDbgKeys;
        if (keys == null || keys.length == 0) { android.util.Log.i("XT9TAPKEY", "no key model yet"); return; }
        // Mirror the engine, do not re-derive it. PKB passes SetKeyboardSize(0,0), so
        // sensor_to_authored skips scaling (1:1); raw coords are logged too since they cannot
        // drift from the engine. VKB passes real dimensions, so it scales proportionally.
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

    /**
     * @param pointerId pointer id
     * @param x x in sensor coordinates
     * @param y y in sensor coordinates
     * @param eventTime event time
     */
    public boolean touchStart(long pointerId, float x, float y, long eventTime) {
        enter("touchStart");
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchStart: pointer=" + pointerId + " x=" + x + " y=" + y + " time=" + eventTime);
        boolean z;
        synchronized (sMutex) {
            startGestureMoves(pointerId);
            if (InputPathDebug.perGesture() && mDbgKeys == null) {
                mDbgKeys = getKeys(this.mNativeHandle);
                if (InputPathDebug.on()) Xt9Trace.dumpNkl(this.mNativeHandle);
            }
            z = touchStart(this.mNativeHandle, pointerId, x, y, eventTime);
        }
        if (InputPathDebug.perGesture()) logTapKey(x, y);   // one line per gesture — cheap
        if (InputPathDebug.on()) Xt9Kdb.logTap(x, y);           // tap calibration: owned resolution + coords (any contact)
        if (InputPathDebug.on()) Xt9Kdb.traceStart(x, y, eventTime);   // mirror to owned scratch trace
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchStart: returned " + z);
        return z;
    }

    /** Parameters as in {@link #touchStart}: pointer id, sensor x, sensor y, event time. */
    public boolean touchMove(long pointerId, float x, float y, long eventTime) {
        enter("touchMove");
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchMove: pointer=" + pointerId + " x=" + x + " y=" + y + " time=" + eventTime);
        boolean z;
        synchronized (sMutex) {
            countGestureMove(pointerId);
            z = touchMove(this.mNativeHandle, pointerId, x, y, eventTime);
        }
        if (InputPathDebug.on()) Xt9Kdb.traceMove(x, y, eventTime);   // mirror to owned scratch trace
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchMove: returned " + z);
        return z;
    }

    /**
     * Finishes pointer {@code pointerId}'s gesture; parameters as in {@link #touchStart}. True means the
     * engine took a gesture and there is a deposit to consume (callers then call
     * {@code noteGestureDeposit()}), not that the native call succeeded: native touchEnd always succeeds.
     */
    public boolean touchEnd(long pointerId, float x, float y, long eventTime) {
        enter("touchEnd");
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchEnd: pointer=" + pointerId + " x=" + x + " y=" + y + " time=" + eventTime);
        boolean z;
        synchronized (sMutex) {
            int n = endGestureMoves(pointerId);
            if (n < MIN_GESTURE_MOVES) {
                // Stray / tap: cancel so it doesn't register as a 1-point gesture record.
                touchCancel(this.mNativeHandle, pointerId);
                // False, not touchCancel's result (always true): nothing was deposited.
                z = false;
                if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchEnd: stray (" + n + " moves) -> touchCancel");
            } else {
                if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchEnd: gesture end after " + n + " move points");
                // Bracket the native call: touchEnd consumes and frees the record, so "pre" shows
                // the in-progress record and "post" the consumed state.
                if (InputPathDebug.on()) Xt9Trace.dumpTraceRec(this.mNativeHandle, "pre");
                z = touchEnd(this.mNativeHandle, pointerId, x, y, eventTime);
                if (InputPathDebug.on()) Xt9Trace.dump(this.mNativeHandle, "touchEnd", 0);
                if (InputPathDebug.on()) Xt9Trace.dumpTraceRec(this.mNativeHandle, "post");
                // Gestures leave the tap symbol buffer (ctx+0x52) empty; their output is the
                // selection list, logged as XT9WORDS in buildSelectionList.
                if (InputPathDebug.on()) Xt9Kdb.traceEnd(x, y, eventTime);   // owned decode + XT9OWNED log
            }
        }
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchEnd: returned " + z);
        return z;
    }

    /** @param pointerId pointer id */
    public boolean touchCancel(long pointerId) {
        enter("touchCancel");
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchCancel: pointer=" + pointerId);
        boolean z;
        synchronized (sMutex) {
            endGestureMoves(pointerId);
            z = touchCancel(this.mNativeHandle, pointerId);
        }
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.touchCancel: returned " + z);
        return z;
    }

    /**
     * Loads and sizes the engine layout. The engine's flag is isPkb, the opposite of the caller's
     * isTouchKb; passing the wrong one silently loads a VKB layout for the keypad (verified by NKL
     * dump: 1080x600/33 keys instead of 1080x450/30), so keypad touches resolve to wrong keys.
     *
     * @param isOnScreenKeyboard true for the on-screen keyboard (isTouchKb)
     * @param locale locale string of the layout
     * @param width keyboard width
     * @param height keyboard height
     */
    public boolean syncKeyboardLayout(boolean isOnScreenKeyboard, String locale, int width, int height) {
        enter("syncKeyboardLayout");
        final boolean isPkb = !isOnScreenKeyboard;   // engine wants isPkb; callers hand us isTouchKb
        // May queue a letter-layout KDB switch on the registration thread, awaited below.
        dev.bbkb.ime.core.layout.LetterLayoutEngineSync.beforeEngineLayoutSync(isPkb);
        sDbgKbZ = isOnScreenKeyboard; sDbgKbStr = locale;   // remember for the debug re-parse trigger
        // Runs on the engine thread, so input posted after it uses the new layout by FIFO order.
        // The KDB registry must be populated before the engine's first SetKdbNum.
        Xt9KdbVariant.awaitReady(2000);
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.syncKeyboardLayout: isTouchKb=" + isOnScreenKeyboard + " locale='" + locale + "' width=" + width + " height=" + height + " kdbVariant='" + Xt9KdbVariant.applied() + "'");
        synchronized (sMutex) {
            return applyKeyboardLayoutLocked(isOnScreenKeyboard, isPkb, locale, width, height);
        }
    }

    /** The native half of {@link #syncKeyboardLayout}; callers hold sMutex. */
    private boolean applyKeyboardLayoutLocked(boolean isOnScreenKeyboard, boolean isPkb, String locale, int width, int height) {
        if (this.mNativeHandle == 0) {
            return false;   // disposed before this op ran
        }
        boolean loadResult = dbgFirstLoad(this.mNativeHandle, isPkb, locale);
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.syncKeyboardLayout: loadKeyboardLayout returned " + loadResult);
        if (!loadResult) {
            return false;
        }
        boolean sizeResult = setKeyboardSize(isPkb, (short) width, (short) height);
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.syncKeyboardLayout: setKeyboardSize returned " + sizeResult);
        if (InputPathDebug.on()) {
            android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "NuanceSDK.syncKeyboardLayout: isPkb=" + isPkb
                + " (isTouchKb=" + isOnScreenKeyboard + ")");
            // Dump the KDB the engine ended up with; the first-touch dump predates this sync.
            Xt9Trace.dumpNkl(this.mNativeHandle);
        }
        return sizeResult;
    }

    /**
     * Loads the physical keyboard's layout when {@code isPkb}, else the on-screen keyboard's.
     * {@code Keyboard.isTouchKeyboard()} is the right argument: it is true for the touch keypad's
     * elements (id 100 and up), not for the on-screen keyboard.
     */
    public boolean loadKeyboardLayout(boolean isPkb) {
        enter("loadKeyboardLayout");
        Xt9KdbVariant.awaitReady(2000);
        boolean zLoadKeyboardLayout;
        synchronized (sMutex) {
            zLoadKeyboardLayout = dbgFirstLoad(this.mNativeHandle, isPkb, "");
        }
        return zLoadKeyboardLayout;
    }

    /** Debug one-shot: the descriptor is computed only on the process's first native loadKeyboardLayout
     *  (then cached), so a watchpoint is armed before that call and its writer PC reported after.
     *  Every loadKeyboardLayout routes through here. View: adb logcat -s XT9WP:I
     *  @param h engine handle
     *  @param isPkb isPkb
     *  @param locale layout locale string */
    private boolean dbgFirstLoad(long h, boolean isPkb, String locale) {
        boolean reArm = BuildConfig.DEBUG && !sDescWatchDone && h != 0;
        if (reArm) { sDescWatchDone = true; Xt9Trace.armDescWatch(h, 0x4c, 1); }
        boolean r = loadKeyboardLayout(h, isPkb, locale);
        if (reArm) Xt9Trace.reportDescWatch(h);
        return r;
    }

    /** @param contextText the text before the cursor, as context for predictions */
    public boolean setContextBuffer(String contextText) {
        enter("setContextBuffer");
        boolean contextBuffer;
        synchronized (sMutex) {
            contextBuffer = setContextBuffer(this.mNativeHandle, toEngineForm(contextText));
        }
        return contextBuffer;
    }

    /** @param enabled whether the engine may predict emoji */
    public boolean setEmojiPredictionEnabled(boolean enabled) {
        enter("setEmojiPredictionEnabled");
        // Called on every field focus: skip the lock when the engine already holds the value.
        Boolean applied = this.mEmojiPredictionApplied;
        if (applied != null && applied == enabled) {
            return true;
        }
        synchronized (sMutex) {
            return applyEmojiPredictionLocked(enabled);
        }
    }

    /** The native half of {@link #setEmojiPredictionEnabled(boolean)}; callers hold sMutex. */
    private boolean applyEmojiPredictionLocked(boolean enabled) {
        if (this.mNativeHandle == 0) {
            return false;
        }
        boolean emojiPredictionEnabled = setEmojiPredictionEnabled(this.mNativeHandle, enabled);
        this.mEmojiPredictionApplied = emojiPredictionEnabled ? enabled : null;
        return emojiPredictionEnabled;
    }

    /** Whether a language has been set and the DLM loaded, so word operations work. */
    public boolean isDLMReady() {
        enter("isDLMReady");
        synchronized (sMutex) {
            return this.mIsLanguageInit;
        }
    }

    /** Undoes {@link #shouldReloadDLMFile()}'s optimistic latch so a failed DLM load is retried. */
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

    /** @param explicitOnly true to count explicitly added words (Et9Probe logs both counts) */
    public int getDLMWordCount(boolean explicitOnly) {
        enter("getDLMWordCount");
        int dLMWordCount;
        synchronized (sMutex) {
            dLMWordCount = getDLMWordCount(this.mNativeHandle, explicitOnly);
        }
        return dLMWordCount;
    }

    /** @param cursorCommand cursor command, not an index: 0 rewinds to the first word, anything else steps (see {@link #getDLMWords}) */
    public String getDLMWord(int cursorCommand) {
        enter("getDLMWord");
        String dLMWord;
        synchronized (sMutex) {
            dLMWord = getDLMWord(this.mNativeHandle, cursorCommand);
        }
        return dLMWord;
    }

    /** The raw engine handle for {@link Et9Probe}'s dlsym'd ET9AW* calls; debug-gated at the call sites. */
    long getNativeHandleForDebug() {
        synchronized (sMutex) {
            return this.mNativeHandle;
        }
    }

    /** @param word the word to delete from the learned model */
    public boolean deleteDLMWord(String word) {
        enter("deleteDLMWord");
        boolean zDeleteDLMWord;
        synchronized (sMutex) {
            this.mDlmMutations++;
            zDeleteDLMWord = deleteDLMWord(this.mNativeHandle, word);
        }
        return zDeleteDLMWord;
    }

    /**
     * Every word in the learned model. The blob's {@code getDLMWord(i)} passes {@code i} to
     * {@code ET9AWDLMGetWord} as a cursor command in an in/out halfword (0 rewinds, else steps;
     * above 64 fails with status 26 since the field doubles as a word length): rewind once, then step.
     */
    public ArrayList<String> getDLMWords() {
        enter("getDLMWords");
        // Up to 12,882 words, one JNI call each (65 to 250 ms), so sMutex is released every chunk
        // to keep keys flowing. Runs on the loader thread, the only enumerator. A model change
        // between chunks can shift the cursor by one; the walk does not restart for it.
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
     * Whether {@link #exportDLM}/{@link #importDLM} work here: the model is attached and the shim
     * recognises the engine build (both do nothing on other builds).
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
     * The learned model as {@code ET9AWDLMExport}'s portable stream (words with counts, languages,
     * categories, quality, next-word pairs; trailing checksum; no process pointers). Null when not
     * attached or unreachable. Parse with {@code LearnedModelStream}.
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
     * Replaces the learned model with an {@link #exportDLM} stream and re-attaches it; returns a
     * DLM_IMPORT_* code or another engine status. The engine resets the model before checking the
     * data, so callers validate first and keep a backup; {@code LearnedModelTransfer} does that and
     * re-attaches the secondary engine.
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
            // The engine rewrote the model in place: re-attach so its caches are rebuilt.
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
     * Full learned-model reset: detach, delete the file, reload an empty model. Per-word deletes
     * only prune enumerable unigrams; bigrams, case variants and usage boosts survive them.
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

    /** @param symbol a Bopomofo symbol */
    public boolean isBpmfUpperCaseSymbol(char symbol) {
        enter("isBpmfUpperCaseSymbol");
        boolean zIsBpmfUpperCaseSymbol;
        synchronized (sMutex) {
            zIsBpmfUpperCaseSymbol = isBpmfUpperCaseSymbol(this.mNativeHandle, symbol);
        }
        return zIsBpmfUpperCaseSymbol;
    }

    /** @param symbol a Bopomofo symbol */
    public String convertBpmfSymbolToLower(char symbol) {
        enter("convertBpmfSymbolToLower");
        String strConvertBpmfSymbolToLower;
        synchronized (sMutex) {
            strConvertBpmfSymbolToLower = convertBpmfSymbolToLower(this.mNativeHandle, symbol);
        }
        return strConvertBpmfSymbolToLower;
    }

    /** Detaches and re-attaches the DLM at {@link #getDLMFilePath()}. */
    private boolean reattachDLM() {
        boolean z;
        synchronized (sMutex) {
            this.mContactsCategoryApplied = null;   // the state lives in the copy being replaced
            this.mDlmMutations++;
            z = detachDLMFile(this.mNativeHandle) && attachDLMFile(this.mNativeHandle, getDLMFilePath());
            if (z) {
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

    /** @param wasManaged the managed mode to restore */
    private void setDLMToPrevState(boolean wasManaged) {
        this.mIsManaged = wasManaged;
        if (wasManaged) {
            return;
        }
        reattachDLM();
    }

    /** @param inputMethodName the engine input method name (Chinese input modes) */
    public void setInputMethod(String inputMethodName) {
        enter("setInputMethod");
        synchronized (sMutex) {
            setInputMethod(this.mNativeHandle, inputMethodName);
            refreshFactsLocked();
        }
    }

    /** @param shiftState the engine's shift state */
    public void setShiftState(int shiftState) {
        enter("setShiftState");
        synchronized (sMutex) {
            setShiftState(this.mNativeHandle, shiftState);
        }
    }
}
