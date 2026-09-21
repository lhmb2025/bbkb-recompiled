package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import dev.bbkb.ime.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import dev.bbkb.ime.BuildConfig;



public class ClipboardHistoryManager implements ClipboardManager.OnPrimaryClipChangedListener {

    private static final String TAG = "ClipboardHistoryManager";

    private final ClipboardManager mClipboardManager;

    private OnClipEvictedListener mOnClipEvictedListener;

    private final Context mContext;

    /**
     * Set by {@link #setPrimaryClip} so the primary-clip callback that our own write provokes is
     * skipped instead of re-entering the history. One-shot: {@link #handleClip} clears it whether
     * or not it fired.
     */
    private boolean mInternalUpdate = false;

    private static final int MAX_HISTORY_SIZE = 7;

    private final List<ClipEntry> mHistory = new ArrayList();

    private final List<OnHistoryChangedListener> mChangeListeners = new ArrayList();

    
    public interface OnClipEvictedListener {
        void onClipEvicted(ClipData clipData);
    }

    
    public interface OnHistoryChangedListener {
        void onHistoryChanged();
    }

    
    public static class ClipEntry {

        public final ClipData mClipData;

        ClipEntry(ClipData clipData) {
            this.mClipData = clipData;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            ClipEntry aVar = (ClipEntry) obj;
            ClipData clipData = this.mClipData;
            if (clipData != null) {
                return clipData.equals(aVar.mClipData);
            }
            return aVar.mClipData == null;
        }

        public int hashCode() {
            ClipData clipData = this.mClipData;
            return clipData != null ? clipData.hashCode() : 0;
        }
    }

    public ClipboardHistoryManager(Context context) {
        this.mClipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.mClipboardManager.addPrimaryClipChangedListener(this);
        this.mContext = context;
    }

    public List<ClipEntry> getHistory() {
        return this.mHistory;
    }

    @Override // android.content.ClipboardManager.OnPrimaryClipChangedListener
    public void onPrimaryClipChanged() {
        ClipData primaryClip = this.mClipboardManager.getPrimaryClip();
        if (primaryClip == null) {
            return;
        }
        handleClip(primaryClip);
    }

    private void handleClip(ClipData clipData) {
        // Announce only a change that happened: a refused clip (no text) leaves the list as it was.
        if (!this.mInternalUpdate && addToHistory(clipData)) {
            notifyHistoryChanged();
        }
        this.mInternalUpdate = false;
    }

    public void addHistoryChangedListener(OnHistoryChangedListener cVar) {
        this.mChangeListeners.add(cVar);
    }

    public void removeHistoryChangedListener(OnHistoryChangedListener cVar) {
        this.mChangeListeners.remove(cVar);
    }

    public void setOnClipEvictedListener(OnClipEvictedListener bVar) {
        this.mOnClipEvictedListener = bVar;
    }

    private void notifyHistoryChanged() {
        for (OnHistoryChangedListener listener : this.mChangeListeners) {
            listener.onHistoryChanged();
        }
    }

    public void setPrimaryClip(ClipData clipData) {
        this.mInternalUpdate = true;
        this.mClipboardManager.setPrimaryClip(clipData);
    }

    /**
     * Delete one row. If it was the clip the system is currently holding, the next row takes its
     * place; if it was the last row, the system clipboard is blanked instead.
     */
    public void removeEntry(ClipEntry entry) {
        this.mHistory.remove(entry);
        if (this.mHistory.isEmpty()) {
            // Nothing left to promote. Flagged as our own update so the callback it provokes does
            // not put the blank clip straight back into the history.
            this.mInternalUpdate = true;
            this.mClipboardManager.setPrimaryClip(ClipData.newPlainText("", ""));
            return;
        }
        String removed = ClipboardItem.getTextFromClipData(entry.mClipData);
        String live = ClipboardItem.getTextFromClipData(this.mClipboardManager.getPrimaryClip());
        if (removed != null && removed.equals(live)) {
            // Flagged as our own update like the other internal writes: the promoted row is already
            // at the front, so the callback this provokes must not re-enter the history.
            this.mInternalUpdate = true;
            this.mClipboardManager.setPrimaryClip(this.mHistory.get(0).mClipData);
        }
    }

    /** @return whether the history list changed (a move-to-front counts as a change). */
    private boolean addToHistory(ClipData clipData) {
        ClipData.Item itemAt;
        if (clipData == null || (itemAt = clipData.getItemAt(0)) == null || itemAt.getText() == null) {
            return false;
        }
        boolean purged = removePasswordEntries();
        if (isPasswordClearClip(clipData)) {
            return purged;
        }
        int size = this.mHistory.size();
        ClipEntry aVar = new ClipEntry(clipData);
        if (!removeDuplicate(aVar)) {
            if (size >= MAX_HISTORY_SIZE) {
                ClipData evicted = this.mHistory.remove(size - 1).mClipData;
                // Audit IB-14: the manager registers itself as a system clipboard
                // listener from its own constructor, but setOnClipEvictedListener is
                // only called from ClipboardView.initialize(). Any clip arriving in
                // that window used to NPE here once the history filled up.
                if (this.mOnClipEvictedListener != null) {
                    this.mOnClipEvictedListener.onClipEvicted(evicted);
                }
            }
            addToFront(aVar);
        }
        return true;
    }

    public void release() {
        this.mClipboardManager.removePrimaryClipChangedListener(this);
    }

    /**
     * If this clip's text is already in the history, move that entry to the front and report it,
     * so the caller neither stores a second copy nor evicts anything.
     *
     * <p>The comparison is on the extracted text -- {@link ClipData} has no value equality -- and
     * it is verbatim: case and surrounding whitespace make a different clip. Both the null-text
     * and the Password-Keeper tests are properties of the incoming clip alone, so they are decided
     * once rather than re-decided against every row.
     */
    private boolean removeDuplicate(ClipEntry entry) {
        String text = ClipboardItem.getTextFromClipData(entry.mClipData);
        if (text == null || isPasswordAddClip(entry.mClipData)) {
            return false;
        }
        for (int i = 0; i < this.mHistory.size(); i++) {
            if (text.equals(ClipboardItem.getTextFromClipData(this.mHistory.get(i).mClipData))) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Duplicated clip list item: " + text);
                addToFront(this.mHistory.remove(i));
                return true;
            }
        }
        return false;
    }

    private void addToFront(ClipEntry aVar) {
        this.mHistory.add(0, aVar);
    }

    /** @return whether any row was purged. */
    private boolean removePasswordEntries() {
        boolean removed = false;
        Iterator<ClipEntry> it = this.mHistory.iterator();
        while (it.hasNext()) {
            if (isPasswordAddClip(it.next().mClipData)) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    /** A Password Keeper "add" clip: stored, but purged as soon as any other clip arrives. */
    private boolean isPasswordAddClip(ClipData clipData) {
        return hasLabel(clipData, this.mContext.getString(R.string.clip_password_keeper_add));
    }

    /** A Password Keeper "clear" clip: never stored, and it purges the password rows. */
    private boolean isPasswordClearClip(ClipData clipData) {
        return hasLabel(clipData, this.mContext.getString(R.string.clip_password_keeper_clear));
    }

    public static boolean hasLabel(ClipData clipData, String str) {
        ClipDescription description;
        return (clipData == null || (description = clipData.getDescription()) == null || !str.equals(description.getLabel())) ? false : true;
    }
}
