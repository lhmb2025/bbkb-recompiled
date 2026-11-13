package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

import androidx.annotation.Nullable;
import androidx.core.os.UserManagerCompat;

import dev.bbkb.ime.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The clipboard board's model: every copied clip in every app, newest first, pinned then recent.
 * Not captured: clips without text, our own writes, incognito fields, clips marked
 * {@link ClipDescription#EXTRA_IS_SENSITIVE}, or history off. Re-copying refreshes the existing row.
 * Unpinned rows expire by {@link ClipboardPrefs#KEY_RETENTION} and are capped at {@link #MAX_UNPINNED}.
 * Password Keeper clips ({@code bb.pk}) are masked, never persisted or pinned, and purged by the next
 * clip; {@code bb.pk.clear} purges them. Main thread only, except the one background store read.
 */
public class ClipboardHistoryManager implements ClipboardManager.OnPrimaryClipChangedListener {

    /** Unpinned rows kept at most. Pinned rows do not count against it and are never evicted. */
    static final int MAX_UNPINNED = 25;

    /**
     * Longer clips are kept in memory only (the file is decoded at every process start); of the
     * unpinned ones only the newest is kept ({@link #evictOverCap}).
     */
    static final int MAX_PERSISTED_CHARS = 100_000;

    /** The wall clock; replaced in tests. */
    interface Clock {
        long nowMs();
    }

    /** Whether credential-protected storage is readable yet; replaced in tests. */
    interface UnlockState {
        boolean isUserUnlocked();
    }

    public interface OnHistoryChangedListener {
        void onHistoryChanged();
    }

    /**
     * Unreleased managers, so settings' "Clear history" reaches in-memory copies too. Weak so a
     * manager dropped without {@link #release()} is not kept alive.
     */
    private static final Set<ClipboardHistoryManager> sLiveManagers =
            Collections.newSetFromMap(new WeakHashMap<ClipboardHistoryManager, Boolean>());

    private static final Comparator<ClipEntry> NEWEST_PIN_FIRST =
            (a, b) -> Long.compare(b.getPinnedAtMs(), a.getPinnedAtMs());

    private static final Comparator<ClipEntry> NEWEST_COPY_FIRST =
            (a, b) -> Long.compare(b.getCreatedAtMs(), a.getCreatedAtMs());

    private final Context mContext;

    private final ClipboardManager mClipboardManager;

    private final ClipboardHistoryStore mStore;

    private final Clock mClock;

    private final UnlockState mUnlockState;

    private final String mPasswordAddLabel;

    private final String mPasswordClearLabel;

    /** Newest pin first. */
    private final List<ClipEntry> mPinned = new ArrayList<>();

    /** Newest copy first. The order is kept by insertion, so equal timestamps cannot shuffle it. */
    private final List<ClipEntry> mRecent = new ArrayList<>();

    private final List<OnHistoryChangedListener> mChangeListeners = new ArrayList<>();

    /** The store has been read (or deliberately skipped); from here on every change is written. */
    private boolean mLoaded = false;

    /** Where the stored history is read (the file can be MBs) and where the result is merged; both null to read inline. */
    @Nullable
    private final Executor mLoadExecutor;

    @Nullable
    private final Executor mMainExecutor;

    /** A background read is in flight; see {@link #startLoad}. */
    private boolean mLoading = false;

    /** Bumped when a pending read must be discarded (the history was cleared or deleted). */
    private int mLoadGeneration = 0;

    /** One background thread for the one read per process; daemon so it never holds the process. */
    private static final Executor LOAD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "clipboard-history-load");
        thread.setDaemon(true);
        return thread;
    });

    private long mNextId = 1;

    /**
     * Set before we write the system clipboard so the callback it provokes is skipped. One-shot:
     * {@link #handleClip} clears it whether or not it fired.
     */
    private boolean mInternalUpdate = false;

    /** The focused field set {@code IME_FLAG_NO_PERSONALIZED_LEARNING} (pushed in by the session); nothing is captured while set. */
    private boolean mNoPersonalizedLearning = false;

    public ClipboardHistoryManager(Context context) {
        this(context, ClipboardHistoryStore.forContext(context), System::currentTimeMillis,
                () -> UserManagerCompat.isUserUnlocked(context), LOAD_EXECUTOR,
                new Handler(Looper.getMainLooper())::post);
    }

    /** Reads the stored history inline: a load has landed by the time the call returns. */
    ClipboardHistoryManager(Context context, ClipboardHistoryStore store, Clock clock,
            UnlockState unlockState) {
        this(context, store, clock, unlockState, null, null);
    }

    /**
     * @param loadExecutor where the stored history is read, or null to read it inline
     * @param mainExecutor where the read result is merged (the thread every other call is on)
     */
    ClipboardHistoryManager(Context context, ClipboardHistoryStore store, Clock clock,
            UnlockState unlockState, @Nullable Executor loadExecutor,
            @Nullable Executor mainExecutor) {
        this.mLoadExecutor = loadExecutor;
        this.mMainExecutor = mainExecutor;
        this.mContext = context;
        this.mClipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.mStore = store;
        this.mClock = clock;
        this.mUnlockState = unlockState;
        this.mPasswordAddLabel = context.getString(R.string.clip_password_keeper_add);
        this.mPasswordClearLabel = context.getString(R.string.clip_password_keeper_clear);
        this.mClipboardManager.addPrimaryClipChangedListener(this);
        synchronized (sLiveManagers) {
            sLiveManagers.add(this);
        }
    }

    /** The whole history in display order: the pinned section, then the recent one. */
    public List<ClipEntry> getHistory() {
        List<ClipEntry> all = new ArrayList<>(this.mPinned.size() + this.mRecent.size());
        all.addAll(this.mPinned);
        all.addAll(this.mRecent);
        return Collections.unmodifiableList(all);
    }

    public List<ClipEntry> getPinned() {
        return Collections.unmodifiableList(new ArrayList<>(this.mPinned));
    }

    public List<ClipEntry> getRecent() {
        return Collections.unmodifiableList(new ArrayList<>(this.mRecent));
    }

    /** A Password Keeper row: shown masked, never persisted, never pinned. */
    public boolean isPasswordKeeperEntry(ClipEntry entry) {
        return entry.hasLabel(this.mPasswordAddLabel);
    }

    @Override
    public void onPrimaryClipChanged() {
        ClipData primaryClip = this.mClipboardManager.getPrimaryClip();
        if (primaryClip == null) {
            return;
        }
        handleClip(primaryClip);
    }

    private void handleClip(ClipData clipData) {
        boolean internal = this.mInternalUpdate;
        this.mInternalUpdate = false;
        if (internal || this.mNoPersonalizedLearning || isSensitiveClip(clipData)) {
            return;
        }
        if (addToHistory(clipData)) {
            persist();
            notifyHistoryChanged();
        }
    }

    /** @param noPersonalizedLearning true if the focused field asked for no personalized learning */
    public void setNoPersonalizedLearning(boolean noPersonalizedLearning) {
        this.mNoPersonalizedLearning = noPersonalizedLearning;
    }

    /** Marked {@link ClipDescription#EXTRA_IS_SENSITIVE} (API 33+) by its source: never stored. */
    static boolean isSensitiveClip(ClipData clipData) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || clipData == null) {
            return false;
        }
        ClipDescription description = clipData.getDescription();
        PersistableBundle extras = description != null ? description.getExtras() : null;
        return extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false);
    }

    /** @return whether the history changed (a refreshed duplicate counts as a change). */
    private boolean addToHistory(ClipData clipData) {
        long now = this.mClock.nowMs();
        ClipEntry incoming = ClipEntry.fromClipData(0, clipData, now);
        if (incoming == null) {
            return false;
        }
        boolean changed = refresh(now);
        if (!ClipboardPrefs.isHistoryEnabled(this.mContext)) {
            return changed;
        }
        changed |= removePasswordEntries();
        if (incoming.hasLabel(this.mPasswordClearLabel)) {
            return changed;
        }
        // A Password Keeper clip is never merged with an existing row: it is purged on its own.
        if (!incoming.hasLabel(this.mPasswordAddLabel) && refreshDuplicate(incoming.getText(), now)) {
            return true;
        }
        this.mRecent.add(0, incoming.withId(this.mNextId++));
        evictOverCap();
        return true;
    }

    /**
     * If {@code text} (compared verbatim) is already stored, refresh that row's time instead: a
     * recent row moves to the front, a pinned row stays where it is. Id and label are kept.
     */
    private boolean refreshDuplicate(String text, long now) {
        for (int i = 0; i < this.mPinned.size(); i++) {
            ClipEntry entry = this.mPinned.get(i);
            if (text.equals(entry.getText())) {
                this.mPinned.set(i, entry.withCreatedAt(now));
                return true;
            }
        }
        for (int i = 0; i < this.mRecent.size(); i++) {
            ClipEntry entry = this.mRecent.get(i);
            if (text.equals(entry.getText())) {
                this.mRecent.remove(i);
                this.mRecent.add(0, entry.withCreatedAt(now));
                return true;
            }
        }
        return false;
    }

    /** Load if this is the first chance since unlock, then drop expired and surplus rows. */
    public void prune() {
        if (refresh(this.mClock.nowMs())) {
            persist();
            notifyHistoryChanged();
        }
    }

    /** @return whether the visible history changed. */
    private boolean refresh(long now) {
        if (!ClipboardPrefs.isHistoryEnabled(this.mContext)) {
            // History off: hold nothing. Covers a pref changed outside the toggle (a restored
            // backup); the file is deleted once, not on every prune.
            boolean changed = !this.mPinned.isEmpty() || !this.mRecent.isEmpty();
            this.mPinned.clear();
            this.mRecent.clear();
            if ((changed || !this.mLoaded) && this.mUnlockState.isUserUnlocked()) {
                discardPendingLoad();
                this.mLoaded = true;
                this.mStore.delete();
            }
            return changed;
        }
        boolean changed = false;
        if (!this.mLoaded && this.mUnlockState.isUserUnlocked()) {
            if (this.mLoadExecutor == null || this.mMainExecutor == null) {
                this.mLoaded = true;
                changed = mergeStored(this.mStore.load());
            } else {
                startLoad();
            }
        }
        changed |= removeExpired(now);
        changed |= evictOverCap();
        return changed;
    }

    /**
     * Read the stored history off the main thread, then merge on it. Until then nothing is written
     * ({@link #persist} waits for {@link #mLoaded}) and the merge replays captures made meanwhile.
     */
    private void startLoad() {
        if (this.mLoading) {
            return;
        }
        this.mLoading = true;
        final int generation = this.mLoadGeneration;
        final Executor main = this.mMainExecutor;
        this.mLoadExecutor.execute(() -> {
            List<ClipEntry> stored = this.mStore.load();
            main.execute(() -> onStoredLoaded(generation, stored));
        });
    }

    private void onStoredLoaded(int generation, List<ClipEntry> stored) {
        if (generation != this.mLoadGeneration) {
            // Cleared while the read was in flight; a newer read, if any, is the one that counts.
            return;
        }
        this.mLoading = false;
        if (this.mLoaded || !ClipboardPrefs.isHistoryEnabled(this.mContext)) {
            // Settled another way meanwhile (history turned off: the next refresh deletes).
            return;
        }
        this.mLoaded = true;
        boolean changed = mergeStored(stored);
        long now = this.mClock.nowMs();
        changed |= removeExpired(now);
        changed |= evictOverCap();
        if (changed) {
            persist();
            notifyHistoryChanged();
        }
    }

    /** Drops a read in flight: its result must not be merged over a history that was emptied. */
    private void discardPendingLoad() {
        if (this.mLoading) {
            this.mLoading = false;
            this.mLoadGeneration++;
        }
    }

    /**
     * Replay clips captured before the read (e.g. while locked) as captures over the stored rows.
     *
     * @return whether there is anything to show (and therefore to write back)
     */
    private boolean mergeStored(List<ClipEntry> stored) {
        List<ClipEntry> pending = new ArrayList<>(this.mPinned);
        pending.addAll(this.mRecent);
        this.mPinned.clear();
        this.mRecent.clear();

        // Ids only have to be unique within this process; a damaged file gets fresh ones.
        boolean idsUsable = true;
        Set<Long> seen = new HashSet<>();
        for (ClipEntry entry : stored) {
            if (entry.getId() <= 0 || !seen.add(entry.getId())) {
                idsUsable = false;
                break;
            }
        }
        long maxId = 0;
        for (ClipEntry entry : stored) {
            ClipEntry kept = idsUsable ? entry : entry.withId(maxId + 1);
            maxId = Math.max(maxId, kept.getId());
            (kept.isPinned() ? this.mPinned : this.mRecent).add(kept);
        }
        this.mNextId = Math.max(this.mNextId, maxId + 1);

        // Replay oldest first, so the newest pending clip ends up in front.
        for (int i = pending.size() - 1; i >= 0; i--) {
            ClipEntry clip = pending.get(i);
            if (isPasswordKeeperEntry(clip) || !refreshDuplicate(clip.getText(), clip.getCreatedAtMs())) {
                this.mRecent.add(0, clip.withId(this.mNextId++));
            }
        }
        Collections.sort(this.mPinned, NEWEST_PIN_FIRST);
        Collections.sort(this.mRecent, NEWEST_COPY_FIRST);
        return !this.mPinned.isEmpty() || !this.mRecent.isEmpty();
    }

    /** Clamp future timestamps, then drop unpinned rows older than the retention setting. */
    private boolean removeExpired(long now) {
        boolean changed = clampFutureTimestamps(now);
        long retention = ClipboardPrefs.retentionMillis(this.mContext);
        if (retention == ClipboardPrefs.NO_EXPIRY) {
            return changed;
        }
        long oldestKept = now - retention;
        Iterator<ClipEntry> it = this.mRecent.iterator();
        while (it.hasNext()) {
            if (it.next().getCreatedAtMs() < oldestKept) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    private boolean clampFutureTimestamps(long now) {
        boolean changed = clampAll(this.mPinned, now);
        if (changed) {
            Collections.sort(this.mPinned, NEWEST_PIN_FIRST);
        }
        boolean recentChanged = clampAll(this.mRecent, now);
        if (recentChanged) {
            Collections.sort(this.mRecent, NEWEST_COPY_FIRST);
        }
        return changed || recentChanged;
    }

    private static boolean clampAll(List<ClipEntry> entries, long now) {
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            ClipEntry entry = entries.get(i);
            ClipEntry clamped = entry.clampedTo(now);
            if (clamped != entry) {
                entries.set(i, clamped);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Evict unpinned rows beyond {@link #MAX_UNPINNED}, and every unpinned row over
     * {@link #MAX_PERSISTED_CHARS} except the newest (memory-only rows would otherwise be unbounded).
     */
    private boolean evictOverCap() {
        boolean changed = false;
        while (this.mRecent.size() > MAX_UNPINNED) {
            this.mRecent.remove(this.mRecent.size() - 1);
            changed = true;
        }
        boolean keptOne = false;
        for (int i = 0; i < this.mRecent.size(); i++) {   // newest first
            if (this.mRecent.get(i).getText().length() <= MAX_PERSISTED_CHARS) {
                continue;
            }
            if (!keptOne) {
                keptOne = true;
            } else {
                this.mRecent.remove(i--);
                changed = true;
            }
        }
        return changed;
    }

    /** @return whether any row was purged. */
    private boolean removePasswordEntries() {
        boolean removed = false;
        Iterator<ClipEntry> it = this.mRecent.iterator();
        while (it.hasNext()) {
            if (isPasswordKeeperEntry(it.next())) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    /**
     * Pin or unpin a row; it moves to the top of its new section (unpinning restarts retention).
     * Password Keeper rows cannot be pinned. Returns whether anything changed.
     */
    public boolean setPinned(long id, boolean pinned) {
        long now = this.mClock.nowMs();
        if (pinned) {
            int index = indexOf(this.mRecent, id);
            if (index < 0 || isPasswordKeeperEntry(this.mRecent.get(index))) {
                return false;
            }
            this.mPinned.add(0, this.mRecent.remove(index).pinnedAt(now));
        } else {
            int index = indexOf(this.mPinned, id);
            if (index < 0) {
                return false;
            }
            this.mRecent.add(0, this.mPinned.remove(index).unpinnedAt(now));
            evictOverCap();
        }
        persist();
        notifyHistoryChanged();
        return true;
    }

    /**
     * Delete one row. If the system clipboard holds it, the newest remaining row replaces it (or the
     * clipboard is blanked). Returns whether the row was found.
     */
    public boolean removeEntry(long id) {
        ClipEntry removed = removeById(id);
        if (removed == null) {
            return false;
        }
        String live = ClipboardItem.getTextFromClipData(this.mClipboardManager.getPrimaryClip());
        if (removed.getText().equals(live)) {
            ClipEntry next = newestPromotable();
            this.mInternalUpdate = true;
            this.mClipboardManager.setPrimaryClip(
                    next != null ? next.toClipData(false) : ClipData.newPlainText("", ""));
        }
        persist();
        notifyHistoryChanged();
        return true;
    }

    /** Put a row back on the system clipboard without re-capturing it; Password Keeper rows go back marked sensitive. */
    public void copyToSystemClipboard(ClipEntry entry) {
        this.mInternalUpdate = true;
        this.mClipboardManager.setPrimaryClip(entry.toClipData(isPasswordKeeperEntry(entry)));
    }

    /** Forget every row, pinned ones included, and delete the stored file. */
    public void clearHistory() {
        boolean changed = !this.mPinned.isEmpty() || !this.mRecent.isEmpty();
        this.mPinned.clear();
        this.mRecent.clear();
        this.mStore.delete();
        if (this.mUnlockState.isUserUnlocked()) {
            // Nothing is left to load: the file is gone, and a read in flight read the old one.
            discardPendingLoad();
            this.mLoaded = true;
        }
        if (changed) {
            notifyHistoryChanged();
        }
    }

    /** Empty every live history and delete the stored file, whether or not the keyboard is running. */
    public static void clearHistory(Context context) {
        List<ClipboardHistoryManager> live;
        synchronized (sLiveManagers) {
            live = new ArrayList<>(sLiveManagers);
        }
        for (ClipboardHistoryManager manager : live) {
            manager.clearHistory();
        }
        ClipboardHistoryStore.forContext(context).delete();
    }

    @Nullable
    private ClipEntry removeById(long id) {
        int index = indexOf(this.mPinned, id);
        if (index >= 0) {
            return this.mPinned.remove(index);
        }
        index = indexOf(this.mRecent, id);
        return index >= 0 ? this.mRecent.remove(index) : null;
    }

    /** The most recently copied row that may go back on the clipboard (never a password). */
    @Nullable
    private ClipEntry newestPromotable() {
        ClipEntry newest = null;
        for (ClipEntry entry : getHistory()) {
            if (!isPasswordKeeperEntry(entry)
                    && (newest == null || entry.getCreatedAtMs() > newest.getCreatedAtMs())) {
                newest = entry;
            }
        }
        return newest;
    }

    private static int indexOf(List<ClipEntry> entries, long id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Write the history minus Password Keeper rows and clips over {@link #MAX_PERSISTED_CHARS}
     * (empty deletes the file). Never before the stored history is read, or it would be replaced.
     */
    private void persist() {
        if (!this.mLoaded) {
            return;
        }
        List<ClipEntry> persistable = new ArrayList<>();
        for (ClipEntry entry : getHistory()) {
            if (!isPasswordKeeperEntry(entry) && entry.getText().length() <= MAX_PERSISTED_CHARS) {
                persistable.add(entry);
            }
        }
        if (persistable.isEmpty()) {
            this.mStore.delete();
        } else {
            this.mStore.save(persistable);
        }
    }

    /** @param onHistoryChangedListener the listener to add */
    public void addHistoryChangedListener(OnHistoryChangedListener onHistoryChangedListener) {
        this.mChangeListeners.add(onHistoryChangedListener);
    }

    /** @param onHistoryChangedListener the listener to remove */
    public void removeHistoryChangedListener(OnHistoryChangedListener onHistoryChangedListener) {
        this.mChangeListeners.remove(onHistoryChangedListener);
    }

    private void notifyHistoryChanged() {
        for (OnHistoryChangedListener listener : new ArrayList<>(this.mChangeListeners)) {
            listener.onHistoryChanged();
        }
    }

    public void release() {
        this.mClipboardManager.removePrimaryClipChangedListener(this);
        synchronized (sLiveManagers) {
            sLiveManagers.remove(this);
        }
    }

    /** @param label the label to match against the clip description's label */
    public static boolean hasLabel(ClipData clipData, String label) {
        ClipDescription description;
        return clipData != null && (description = clipData.getDescription()) != null
                && label.equals(description.getLabel());
    }
}
