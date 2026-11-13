package dev.bbkb.ime.keyboard;

import dev.bbkb.ime.core.keyevent.InputSource;


public interface KeyboardActionListenerInterface {

    KeyboardActionListenerInterface EMPTY = new KeyboardActionListenerInterface() {};

    default void onMoreKeysKeyTyped() {}

    default void onCodeInput(int primaryCode, int x, int y, long eventTime, boolean isKeyRepeat) {}

    default void onReleaseKey(int primaryCode, boolean withSliding) {}

    default void onTextInput(String text, long eventTime) {}

    default void onPressKey(int primaryCode, int repeatCount, boolean isSinglePointer) {}

    default void onEndBatchInput(InputSource inputSource) {}

    default boolean onCustomRequest(int requestCode) {
        return false;
    }

    default void onStartBatchInput() {}

    default void onUpdateBatchInput() {}

    default void onCancelBatchInput() {}

    default void onFinishSlidingInput() {}

    default void onCancelInput() {}
}
