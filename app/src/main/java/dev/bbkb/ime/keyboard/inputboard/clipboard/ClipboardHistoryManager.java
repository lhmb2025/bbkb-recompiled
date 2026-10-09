package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.UserManager;

import androidx.annotation.Nullable;

import dev.bbkb.ime.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The clipboard board's model: every clip the user copies, newest first, split into a pinned
 * section and a recent section.
 *
 * <h3>Capture</h3>
 * Registered as a system primary-clip listener from the constructor, so it hears every copy in
 * every app. A clip is captured unless:
 * <ul>
 *   <li>it has no text (only text is kept);</li>
 *   <li>this manager wrote it itself ({@link #copyToSystemClipboard}, or the promotion in
 *       {@link #removeEntry});</li>
 *   <li>the focused field is incognito ({@link #setNoPersonalizedLearning});</li>
 *   <li>its source marked it {@link ClipDescription#EXTRA_IS_SENSITIVE} (API 33+);</li>
 *   <li>the user turned clipboard history off ({@link ClipboardPrefs#KEY_HISTORY_ENABLED}).</li>
 * </ul>
 * Re-copying text that is already in the history refreshes that row instead of adding a second
 * one; a pinned row stays pinned and where it is.
 *
 * <h3>Retention</h3>
 * Unpinned rows older than {@link ClipboardPrefs#KEY_RETENTION} are pruned on capture, when the
 * board is shown, when input starts and on load ({@link #prune}); a timestamp in the future is
 * treated as now. At most {@link #MAX_UNPINNED} unpinned rows are kept, the oldest evicted first.
 * Pinned rows are exempt from both.
 *
 * <h3>Password Keeper</h3>
 * BlackBerry Password Keeper labels the passwords it copies {@code bb.pk}: such a row is kept,
 * shown masked, never persisted, never pinned, and purged as soon as any other clip arrives. A
 * {@code bb.pk.clear} clip is never stored and purges them.
 *
 * <h3>Persistence</h3>
 * Through {@link ClipboardHistoryStore}, which lives in credential-protected storage. It is read
 * lazily, the first time the history is needed once the user has unlocked; clips copied before
 * that are held in memory and merged into what was stored.
 *
 * <p>Not thread-safe: everything here runs on the main thread. The store does its writing on its
 * own thread from immutable snapshots.
 */
public class ClipboardHistoryManager implements ClipboardManager.OnPrimaryClipChangedListener {

    /** Unpinned rows kept at most. Pinned rows do not count against it and are never evicted. */
    static final int MAX_UNPINNED = 25;

    /**
     * Clips longer than this are kept for the session but not written to disk: the file is read
     * on the main thread, and one pasted book should not make that slow.
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
     * Every manager not yet released, so the settings screen's "Clear history" reaches the
     * keyboard's in-memory copy as well as the file. Weak: a manager that was dropped without
     * {@link #release()} must not be kept alive by this.
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

    private long mNextId = 1;

    /**
     * Set before this manager writes the system clipboard so the primary-clip callback that write
     * provokes is skipped instead of re-entering the history. One-shot: {@link #handleClip} clears
     * it whether or not it fired.
     */
    private boolean mInternalUpdate = false;

    /**
     * The focused field set {@code IME_FLAG_NO_PERSONALIZED_LEARNING}. The manager is global and
     * hears every copy in every app, so the session pushes this in rather than the manager
     * reading a field it has no view of; while set, nothing new enters the history.
     */
    private boolean mNoPersonalizedLearning = false;

    public ClipboardHistoryManager(Context context) {
        this(context, ClipboardHistoryStore.forContext(context), System::currentTimeMillis,
                () -> isUserUnlocked(context));
    }

    ClipboardHistoryManager(Context context, ClipboardHistoryStore store, Clock clock,
            UnlockState unlockState) {
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

    private static boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        UserManager userManager = context.getSystemService(UserManager.class);
        return userManager == null || userManager.isUserUnlocked();
    }

    // ── reading ──────────────────────────────────────────────────────────────

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

    // ── capture ──────────────────────────────────────────────────────────────

    @Override // android.content.ClipboardManager.OnPrimaryClipChangedListener
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
        // Announce only a change that happened: a refused clip (no text) leaves the list as it was.
        if (addToHistory(clipData)) {
            persist();
            notifyHistoryChanged();
        }
    }

    public void setNoPersonalizedLearning(boolean z) {
        this.mNoPersonalizedLearning = z;
    }

    /**
     * A clip its source marked {@link ClipDescription#EXTRA_IS_SENSITIVE} (a password manager, an
     * OTP): never stored. The extra is API 33; older sources cannot set it.
     */
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
     * If {@code text} is already in the history, refresh that row's copy time instead of storing
     * a second copy: a recent row moves to the front of its section, a pinned row stays pinned
     * where it is. The original row is kept (its id and label); only its time changes.
     *
     * <p>The comparison is verbatim: case and surrounding whitespace make a different clip.
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

    // ── retention ────────────────────────────────────────────────────────────

    /**
     * Load if this is the first chance since unlock, then drop expired and surplus rows. Called
     * when the board is shown and when input starts; capture does the same itself.
     */
    public void prune() {
        if (refresh(this.mClock.nowMs())) {
            persist();
            notifyHistoryChanged();
        }
    }

    /** @return whether the visible history changed. */
    private boolean refresh(long now) {
        if (!ClipboardPrefs.isHistoryEnabled(this.mContext)) {
            // History is off: hold nothing, and leave nothing on disk. The settings toggle deletes
            // the file itself; this covers a preference that changed some other way (a restored
            // backup). The file is deleted once, not on every prune while history stays off.
            boolean changed = !this.mPinned.isEmpty() || !this.mRecent.isEmpty();
            this.mPinned.clear();
            this.mRecent.clear();
            if ((changed || !this.mLoaded) && this.mUnlockState.isUserUnlocked()) {
                this.mLoaded = true;
                this.mStore.delete();
            }
            return changed;
        }
        boolean changed = false;
        if (!this.mLoaded && this.mUnlockState.isUserUnlocked()) {
            this.mLoaded = true;
            changed = mergeStored(this.mStore.load());
        }
        changed |= removeExpired(now);
        changed |= evictOverCap();
        return changed;
    }

    /**
     * Merge what was stored with what was captured before it could be read (the clips copied
     * while the user was locked), replaying the latter as captures over the former.
     *
     * @return whether there is anything to show (and therefore to write back).
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

    /** Evict the oldest unpinned rows beyond {@link #MAX_UNPINNED}. */
    private boolean evictOverCap() {
        boolean changed = false;
        while (this.mRecent.size() > MAX_UNPINNED) {
            this.mRecent.remove(this.mRecent.size() - 1);
            changed = true;
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

    // ── user actions ─────────────────────────────────────────────────────────

    /**
     * Pin or unpin a row. A pinned row moves to the top of the pinned section; an unpinned one to
     * the top of the recent section, with a fresh retention window. Password Keeper rows cannot be
     * pinned.
     *
     * @return whether anything changed.
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
     * Delete one row. If it was the clip the system is currently holding, the most recently copied
     * remaining row takes its place; if none is left, the system clipboard is blanked instead. A
     * row the system is not holding leaves the system clipboard alone, even if it was the last.
     *
     * @return whether the row was found.
     */
    public boolean removeEntry(long id) {
        ClipEntry removed = removeById(id);
        if (removed == null) {
            return false;
        }
        String live = ClipboardItem.getTextFromClipData(this.mClipboardManager.getPrimaryClip());
        if (removed.getText().equals(live)) {
            ClipEntry next = newestPromotable();
            // Flagged as our own update, so the callback it provokes does not re-enter the history.
            this.mInternalUpdate = true;
            this.mClipboardManager.setPrimaryClip(
                    next != null ? next.toClipData(false) : ClipData.newPlainText("", ""));
        }
        persist();
        notifyHistoryChanged();
        return true;
    }

    /**
     * Put a row back on the system clipboard, without it re-entering the history as a new copy.
     * A Password Keeper row goes back marked sensitive.
     */
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
            // Nothing is left to load: the file is gone.
            this.mLoaded = true;
        }
        if (changed) {
            notifyHistoryChanged();
        }
    }

    /**
     * The settings screen's "Clear history", and what turning history off does: empty every live
     * history and delete the stored file, whether or not the keyboard is running.
     */
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

    // ── persistence and listeners ────────────────────────────────────────────

    /**
     * Write the history, minus what must never reach the disk: Password Keeper rows and clips over
     * {@link #MAX_PERSISTED_CHARS}. With nothing left to write the file is deleted rather than
     * left holding an empty list. Nothing is written before the stored history has been read, or
     * the write would replace it.
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

    public void addHistoryChangedListener(OnHistoryChangedListener cVar) {
        this.mChangeListeners.add(cVar);
    }

    public void removeHistoryChangedListener(OnHistoryChangedListener cVar) {
        this.mChangeListeners.remove(cVar);
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

    public static boolean hasLabel(ClipData clipData, String str) {
        ClipDescription description;
        return clipData != null && (description = clipData.getDescription()) != null
                && str.equals(description.getLabel());
    }
}
