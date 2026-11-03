package dev.bbkb.ime.core.engine.dlm;

import android.os.SystemClock;

import com.blackberry.nuanceshim.NuanceSDK;

import dev.bbkb.ime.core.shared.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The words an engine knows only because the keyboard learned them, read from the engine's own
 * export of the learned model: quality 0/1 (learned) and −11…−1 (typed once and not kept yet).
 * Words added on purpose (15, the personal dictionary) and the usage records of dictionary and
 * contact words (2/3) are not learned-only. Exact spellings: a learned capitalisation of a
 * dictionary word ("BlackBerry") is learned-only, the dictionary word itself is not.
 *
 * <p>"Suggest learned words" (the keyboard) and "Accept learned words" (the spell checker) are
 * both answered from here. Each engine instance has its own cache, since the keyboard's and the
 * spell checker's engines hold separate copies of the model. A read is trusted until the engine's
 * word count changes or {@link #REFRESH_MS} pass, so a word just learned or deleted is seen at
 * the next request without reading the model again on every one. The keyboard keeps learning
 * while a read runs.
 */
public final class LearnedOnlyWords {

    private static final String TAG = "LearnedOnlyWords";

    static final int QUALITY_MIN = -11;
    static final int QUALITY_MAX = 1;

    /** How long a read is trusted when the engine's word count has not changed. */
    static final long REFRESH_MS = 30_000L;

    private static final class Entry {
        final Set<String> words;
        final int count;
        final long readAt;

        Entry(Set<String> words, int count, long readAt) {
            this.words = words;
            this.count = count;
            this.readAt = readAt;
        }
    }

    private static final Map<NuanceSDK, Entry> sCache = new WeakHashMap<>();

    private LearnedOnlyWords() {
    }

    /** Whether {@code word}, spelled exactly so, is a learned-only word of {@code engine}'s model. */
    public static boolean contains(NuanceSDK engine, String word) {
        final Set<String> words = of(engine);
        return words != null && word != null && words.contains(word);
    }

    /**
     * The learned-only words of {@code engine}'s model, or null when the model cannot be read on
     * this phone (then nothing is treated as learned-only). The set is shared: do not modify it.
     */
    public static Set<String> of(NuanceSDK engine) {
        if (engine == null || !engine.isDLMAttached()) {
            return null;
        }
        final long now = SystemClock.elapsedRealtime();
        synchronized (sCache) {
            final Entry cached = sCache.get(engine);
            final int count = engine.getDLMWordCount(false);
            if (cached != null && cached.count == count && now - cached.readAt < REFRESH_MS) {
                return cached.words;
            }
            final Set<String> words = read(engine);
            if (words == null) {
                return cached != null ? cached.words : null;
            }
            sCache.put(engine, new Entry(words, count, now));
            return words;
        }
    }

    private static Set<String> read(NuanceSDK engine) {
        if (!engine.isDLMTransferAvailable()) {
            return null;
        }
        final byte[] bytes = engine.exportDLM();
        if (bytes == null) {
            return null;
        }
        try {
            return learnedOnlyWords(LearnedModelStream.INSTANCE.parse(bytes).getWords());
        } catch (Exception e) {
            // LearnedModelStream.InvalidModelException; Kotlin does not declare it for Java.
            Logger.warn(TAG, "the learned model does not parse: " + e.getMessage());
            return null;
        }
    }

    /** The words of {@code records} known only because the keyboard learned them. */
    static Set<String> learnedOnlyWords(List<LearnedModelStream.Word> records) {
        final Set<String> words = new HashSet<>();
        for (LearnedModelStream.Word record : records) {
            final int quality = record.getQuality();
            if (quality >= QUALITY_MIN && quality <= QUALITY_MAX) {
                words.add(record.getText());
            }
        }
        return words;
    }

    /** Forgets every read. */
    static void clearForTest() {
        synchronized (sCache) {
            sCache.clear();
        }
    }
}
