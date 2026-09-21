package dev.bbkb.ime.keyboard.internal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import dev.bbkb.ime.core.shared.CoordinateUtils;
import dev.bbkb.ime.core.shared.ViewLayoutUtils;
import dev.bbkb.ime.keyboard.Key;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;



public final class KeyPreviewChoreographer {

    private static final String TAG = "KeyPreviewChoreographer";

    private final ArrayDeque<KeyPreviewView> mFreeKeyPreviewViews = new ArrayDeque<>();

    private final HashMap<Key, KeyPreviewView> mShowingKeyPreviewViews = new HashMap<>();

    private final KeyPreviewDrawParams mParams;

    public KeyPreviewChoreographer(KeyPreviewDrawParams c1063v) {
        this.mParams = c1063v;
    }

    public KeyPreviewView getKeyPreviewView(Key key, ViewGroup viewGroup) {
        KeyPreviewView c1064wRemove = this.mShowingKeyPreviewViews.remove(key);
        if (c1064wRemove != null) {
            return c1064wRemove;
        }
        KeyPreviewView c1064wPoll = this.mFreeKeyPreviewViews.poll();
        if (c1064wPoll != null) {
            return c1064wPoll;
        }
        KeyPreviewView c1064w = new KeyPreviewView(viewGroup.getContext(), null);
        viewGroup.addView(c1064w, ViewLayoutUtils.newLayoutParam(viewGroup, 0, 0));
        return c1064w;
    }

    public void dismissAllKeyPreviews() {
        Iterator it = new HashSet(this.mShowingKeyPreviewViews.keySet()).iterator();
        while (it.hasNext()) {
            dismissKeyPreview((Key) it.next(), false);
        }
    }

    public void dismissKeyPreview(Key key, boolean z) {
        KeyPreviewView c1064w;
        if (key == null || (c1064w = this.mShowingKeyPreviewViews.get(key)) == null) {
            return;
        }
        Object tag = c1064w.getTag();
        if (z && (tag instanceof KeyPreviewAnimators)) {
            ((KeyPreviewAnimators) tag).startDismiss();
            return;
        }
        this.mShowingKeyPreviewViews.remove(key);
        if (tag instanceof Animator) {
            ((Animator) tag).cancel();
        }
        c1064w.setTag(null);
        c1064w.setVisibility(View.INVISIBLE);
        this.mFreeKeyPreviewViews.add(c1064w);
    }

    public void placeAndShowKeyPreview(Key key, KeyboardIconSet c1024ag, KeyDrawParams c1061t, int i, int[] iArr, ViewGroup viewGroup, boolean z) {
        KeyPreviewView c1064wM7424a = getKeyPreviewView(key, viewGroup);
        placeKeyPreview(key, c1064wM7424a, c1024ag, c1061t, i, iArr);
        showKeyPreview(key, c1064wM7424a, z);
    }

    private void placeKeyPreview(Key key, KeyPreviewView c1064w, KeyboardIconSet c1024ag, KeyDrawParams c1061t, int i, int[] iArr) {
        int i2;
        c1064w.setPreviewVisual(key, c1024ag, c1061t);
        
        // Set preview dimensions: width = key width, height = 2× key height
        int keyWidth = key.getWidth();
        int keyHeight = key.getHeight();
        c1064w.setPreviewDimensions(keyWidth, keyHeight * 2);
        
        c1064w.measure(-2, -2);
        int measuredWidth = c1064w.getMeasuredWidth();
        int measuredHeight = c1064w.getMeasuredHeight();
        this.mParams.setGeometry(c1064w);
        int i3 = this.mParams.mPreviewHeight;
        int i4 = 2;
        int iM6218ad = (key.getDrawX() - ((measuredWidth - key.getDrawWidth()) / 2)) + CoordinateUtils.x(iArr);
        if (iM6218ad < 0) {
            i2 = 0;
            i4 = 1;
        } else {
            i2 = i - measuredWidth;
            if (iM6218ad <= i2) {
                i2 = iM6218ad;
                i4 = 0;
            }
        }
        c1064w.setPreviewBackground(key.getMoreKeys() != null, i4);
        // Position preview so its bottom aligns with the bottom of the key
        int yPos = (key.getY() + key.getHeight() - measuredHeight) + CoordinateUtils.y(iArr);
        ViewLayoutUtils.placeViewAt(c1064w, i2, yPos, measuredWidth, measuredHeight);
        c1064w.setPivotX(measuredWidth / 2.0f);
        c1064w.setPivotY(measuredHeight);
    }

    public void showKeyPreview(Key key, KeyPreviewView c1064w, boolean z) {
        if (!z) {
            c1064w.setVisibility(View.VISIBLE);
            this.mShowingKeyPreviewViews.put(key, c1064w);
        } else {
            KeyPreviewAnimators aVar = new KeyPreviewAnimators(createShowUpAnimator(key, c1064w), createDismissAnimator(key, c1064w));
            c1064w.setTag(aVar);
            aVar.startShowUp();
        }
    }

    public Animator createShowUpAnimator(final Key key, final KeyPreviewView c1064w) throws Resources.NotFoundException {
        Animator animatorM7436b = this.mParams.createShowUpAnimator(c1064w);
        animatorM7436b.addListener(new AnimatorListenerAdapter() {
            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationStart(Animator animator) {
                KeyPreviewChoreographer.this.showKeyPreview(key, c1064w, false);
            }
        });
        return animatorM7436b;
    }

    private Animator createDismissAnimator(final Key key, KeyPreviewView c1064w) throws Resources.NotFoundException {
        Animator animatorM7438c = this.mParams.createDismissAnimator(c1064w);
        animatorM7438c.addListener(new AnimatorListenerAdapter() {
            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationEnd(Animator animator) {
                KeyPreviewChoreographer.this.dismissKeyPreview(key, false);
            }
        });
        return animatorM7438c;
    }

    private static class KeyPreviewAnimators extends AnimatorListenerAdapter {

        private final Animator mShowUpAnimator;

        private final Animator mDismissAnimator;

        public KeyPreviewAnimators(Animator animator, Animator animator2) {
            this.mShowUpAnimator = animator;
            this.mDismissAnimator = animator2;
        }

        public void startShowUp() {
            this.mShowUpAnimator.start();
        }

        public void startDismiss() {
            if (this.mShowUpAnimator.isRunning()) {
                this.mShowUpAnimator.addListener(this);
            } else {
                this.mDismissAnimator.start();
            }
        }

        @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
        public void onAnimationEnd(Animator animator) {
            this.mDismissAnimator.start();
        }
    }
}
