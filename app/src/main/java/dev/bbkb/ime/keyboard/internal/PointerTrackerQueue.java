package dev.bbkb.ime.keyboard.internal;

import android.util.Log;

import java.util.ArrayList;
import dev.bbkb.ime.BuildConfig;



public final class PointerTrackerQueue {

    private static final String TAG = "PointerTrackerQueue";

    /**
     * Live pointers, oldest first; also the lock. A release scan compacts survivors in place and
     * trims the tail only after the scan, so a phantom-up callback still sees the pre-release size.
     */
    private final ArrayList<Element> mActivePointers = new ArrayList<>(10);

    /** One queued pointer tracker. */
    public interface Element {
        /** @param eventTime event time */
        void onPhantomUpEvent(long eventTime);

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

    public void add(Element element) {
        synchronized (this.mActivePointers) {
            this.mActivePointers.add(element);
        }
    }

    public void remove(Element element) {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            int kept = 0;
            for (int i = 0; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e != element) {
                    this.mActivePointers.set(kept++, e);
                } else if (kept != i) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Found duplicated element in remove: " + element);
                }
            }
            trimTo(kept);
        }
    }

    /** @param eventTime event time */
    public void releaseAllPointersOlderThan(Element element, long eventTime) {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            int i = 0;
            int kept = 0;
            for (; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e == element) {
                    break;
                }
                if (e.isModifier()) {
                    this.mActivePointers.set(kept++, e);
                } else {
                    e.onPhantomUpEvent(eventTime);
                }
            }
            int pivots = 0;
            for (; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e == element && ++pivots > 1) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Found duplicated element in releaseAllPointersOlderThan: " + element);
                }
                this.mActivePointers.set(kept++, e);
            }
            trimTo(kept);
        }
    }

    /** @param eventTime event time */
    public void releaseAllPointers(long eventTime) {
        releaseAllPointersExcept(null, eventTime);
    }

    /** @param eventTime event time */
    public void releaseAllPointersExcept(Element element, long eventTime) {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            int kept = 0;
            for (int i = 0; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e == element) {
                    if (kept > 0) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Found duplicated element in releaseAllPointersExcept: " + element);
                    }
                    this.mActivePointers.set(kept++, e);
                } else {
                    e.onPhantomUpEvent(eventTime);
                }
            }
            trimTo(kept);
        }
    }

    public boolean hasModifierKeyOlderThan(Element element) {
        synchronized (this.mActivePointers) {
            final int size = this.mActivePointers.size();
            for (int i = 0; i < size; i++) {
                Element e = this.mActivePointers.get(i);
                if (e == element) {
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
