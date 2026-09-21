package dev.bbkb.ime.keyboard;

import dev.bbkb.ime.core.keyevent.InputSource;


public interface KeyboardActionListenerInterface {

    KeyboardActionListenerInterface EMPTY = new KeyboardActionListenerInterface() {};

    default void onMoreKeysKeyTyped() {}

    default void onCodeInput(int i, int i2, int i3, long j, boolean z) {}

    default void onReleaseKey(int i, boolean z) {}

    default void onTextInput(String str, long j) {}

    default void onPressKey(int i, int i2, boolean z) {}

    default void onEndBatchInput(InputSource enumC0690f) {}

    default boolean onCustomRequest(int i) {
        return false;
    }

    default void onStartBatchInput() {}

    default void onUpdateBatchInput() {}

    default void onCancelBatchInput() {}

    default void onFinishSlidingInput() {}

    default void onCancelInput() {}
}
