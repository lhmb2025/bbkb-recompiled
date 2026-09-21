package dev.bbkb.ime.keyboard.auxbar.suggestions;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;
import dev.bbkb.ime.keyboard.internal.MoreKeysKeyboardView;
import dev.bbkb.ime.BuildConfig;


public final class MoreSuggestionsView extends MoreKeysKeyboardView {

    private static final String TAG = "MoreSuggestionsView";

    
    public static abstract class MoreSuggestionsListener implements KeyboardActionListenerInterface {
        public abstract void onSuggestionSelected(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar);
    }

    public MoreSuggestionsView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.moreKeysKeyboardViewStyle);
    }

    public MoreSuggestionsView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
    }

    @Override // dev.bbkb.ime.keyboard.MoreKeysKeyboardView
    protected int getDefaultCoordX() {
        return ((MoreSuggestionsKeyboard) getKeyboard()).mOccupiedWidth / 2;
    }

    public void applyDefaultRowHeight(int i) {
        applyDefaultKeyStyle(i);
    }

    @Override // dev.bbkb.ime.keyboard.MoreKeysKeyboardView
    protected void onKeyInput(Key key, int i, int i2, long j) {
        if (!(key instanceof MoreSuggestionsKeyboard.MoreSuggestionKey)) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Expected key is MoreSuggestionKey, but found " + key.getClass().getName());
            return;
        }
        Keyboard keyboard = getKeyboard();
        if (!(keyboard instanceof MoreSuggestionsKeyboard)) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Expected keyboard is MoreSuggestions, but found " + keyboard.getClass().getName());
            return;
        }
        SuggestedWords c0666ac = ((MoreSuggestionsKeyboard) keyboard).mSuggestedWords;
        int i3 = ((MoreSuggestionsKeyboard.MoreSuggestionKey) key).mSuggestedWordIndex;
        if (i3 < 0 || i3 >= c0666ac.size()) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Selected suggestion has an illegal index: " + i3);
            return;
        }
        if (!(this.mListener instanceof MoreSuggestionsListener)) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Expected mListener is MoreSuggestionsListener, but found " + this.mListener.getClass().getName());
            return;
        }
        ((MoreSuggestionsListener) this.mListener).onSuggestionSelected(c0666ac.getWordInfo(i3));
    }
}
