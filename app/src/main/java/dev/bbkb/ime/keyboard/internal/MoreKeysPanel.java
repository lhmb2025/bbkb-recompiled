package dev.bbkb.ime.keyboard.internal;

import android.view.View;
import android.view.ViewGroup;

import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;


public interface MoreKeysPanel {

    public static final Controller EMPTY = new Controller() {
        @Override
        public void onCancelMoreKeysPanel() {
        }

        @Override
        public void onShowMoreKeysPanel(MoreKeysPanel interfaceC1071m) {
        }

        @Override
        public void onDismissMoreKeysPanel() {
        }
    };

    
    public interface Controller {
        void onCancelMoreKeysPanel();

        void onShowMoreKeysPanel(MoreKeysPanel interfaceC1071m);

        void onDismissMoreKeysPanel();
    }

    void onDownEvent(int i, int i2, int i3, long j);

    void showMoreKeysPanel(View view, Controller aVar, int i, int i2, KeyboardActionListenerInterface interfaceC0976f);

    void showInParent(ViewGroup viewGroup);

    void dismissMoreKeysPanel();

    void onMoveEvent(int i, int i2, int i3, long j);

    int translateX(int i);

    void removeFromParent();

    void onUpEvent(int i, int i2, int i3, long j);

    int translateY(int i);

    boolean isShowingInParent();
}
