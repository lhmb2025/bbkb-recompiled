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
        public void onShowMoreKeysPanel(MoreKeysPanel moreKeysPanel) {
        }

        @Override
        public void onDismissMoreKeysPanel() {
        }
    };

    
    public interface Controller {
        void onCancelMoreKeysPanel();

        void onShowMoreKeysPanel(MoreKeysPanel moreKeysPanel);

        void onDismissMoreKeysPanel();
    }

    void onDownEvent(int x, int y, int pointerId, long eventTime);

    void showMoreKeysPanel(View view, Controller controller, int pointX, int pointY, KeyboardActionListenerInterface listener);

    void showInParent(ViewGroup viewGroup);

    void dismissMoreKeysPanel();

    void onMoveEvent(int x, int y, int pointerId, long eventTime);

    int translateX(int x);

    void removeFromParent();

    void onUpEvent(int x, int y, int pointerId, long eventTime);

    int translateY(int y);

    boolean isShowingInParent();
}
