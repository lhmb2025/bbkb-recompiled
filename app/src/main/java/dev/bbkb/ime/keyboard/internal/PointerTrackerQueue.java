package dev.bbkb.ime.keyboard.internal;

import android.util.Log;

import java.util.ArrayList;
import dev.bbkb.ime.BuildConfig;



public final class PointerTrackerQueue {

    private static final String TAG = "PointerTrackerQueue";

    /**
     * Live pointers, oldest first; also the lock. A release scan compacts survivors to the front in
     * place while it fires {@link Element#onPhantomUpEvent} and trims the tail only once the scan is
     * over, so a phantom-up callback still observes the pre-release size.
     */
    private final ArrayList<Element> mActivePointers = new ArrayList<>(10);


    /** AOSP: one queued pointer tracker. */
    public interface Element {
        void onPhantomUpEvent(long j);

        boolean isModifier();

        boolean isOnShiftKey();

        void dispatchShiftKeyTap();

        boolean isInSlidingKeyInput();

        void cancelTrackingForAction();
    }

    public int size() {
        synchronized (this.mActivePointers) {
            return this.mActivePointers.size();
        }
    }

    public void add(Element aVar) {
        synchronized (this.mActivePointers) {
            this.mActivePointers.add(aVar);
        }
    }

    public void remove(Element aVar) {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            int kept = 0;
            for (int i = 0; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e != aVar) {
                    this.mActivePointers.set(kept++, e);
                } else if (kept != i) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Found duplicated element in remove: " + aVar);
                }
            }
            trimTo(kept);
        }
    }

    public void releaseAllPointersOlderThan(Element aVar, long j) {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            int i = 0;
            int kept = 0;
            for (; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e == aVar) {
                    break;
                }
                if (e.isModifier()) {
                    this.mActivePointers.set(kept++, e);
                } else {
                    e.onPhantomUpEvent(j);
                }
            }
            int pivots = 0;
            for (; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e == aVar && ++pivots > 1) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Found duplicated element in releaseAllPointersOlderThan: " + aVar);
                }
                this.mActivePointers.set(kept++, e);
            }
            trimTo(kept);
        }
    }

    public void releaseAllPointers(long j) {
        releaseAllPointersExcept(null, j);
    }

    public void releaseAllPointersExcept(Element aVar, long j) {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            int kept = 0;
            for (int i = 0; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e == aVar) {
                    if (kept > 0) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Found duplicated element in releaseAllPointersExcept: " + aVar);
                    }
                    this.mActivePointers.set(kept++, e);
                } else {
                    e.onPhantomUpEvent(j);
                }
            }
            trimTo(kept);
        }
    }

    public boolean hasModifierKeyOlderThan(Element aVar) {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            for (int i = 0; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e == aVar) {
                    return false;
                }
                if (e.isModifier()) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean isAnyInSlidingKeyInput() {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            for (int i = 0; i < size; i++) {
                if (this.mActivePointers.get(i).isInSlidingKeyInput()) {
                    return true;
                }
            }
            return false;
        }
    }

    public void cancelAllPointerTrackers() {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            for (int i = 0; i < size; i++) {
                this.mActivePointers.get(i).cancelTrackingForAction();
            }
        }
    }

    public String toString() {
        synchronized (this.mActivePointers) {
            StringBuilder sb = new StringBuilder();
            final int size = this.mActivePointers.size();
            for (int i = 0; i < size; i++) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(this.mActivePointers.get(i).toString());
            }
            return "[" + sb.toString() + "]";
        }
    }

    public void dispatchHeldShiftKey() {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            for (int i = 0; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e.isOnShiftKey()) {
                    e.dispatchShiftKeyTap();
                    return;
                }
            }
        }
    }

    /** Drop everything from {@code size} on; called once a scan has compacted its survivors. */
    private void trimTo(int size) {
        this.mActivePointers.subList(size, this.mActivePointers.size()).clear();
    }
}
