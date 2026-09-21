package dev.bbkb.ime.keyboard.slideboard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

import java.util.List;


public class QuickPhrasesView extends RelativeLayout implements QuickPhrasesAdapter.Listener, SlideboardComponent {

    private OnQuickPhraseSelectedListener quickPhraseListener;

    private SlideboardComponent.Listener slideListener;

    private final RecyclerView recyclerView;

    private final KeyboardSwitcher keyboardSwitcher;

    private final int keyboardHeight;

    
    public interface OnQuickPhraseSelectedListener {
        void onQuickPhraseSelected(CharSequence charSequence, Context context);
    }

    @Override
    public void onPhraseClicked(QuickPhrasesAdapter.QuickPhrase phrase) {
        this.quickPhraseListener.onQuickPhraseSelected(phrase.text, getContext());
        this.slideListener.onSlideComplete();
    }

    public QuickPhrasesView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public QuickPhrasesView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        LayoutInflater.from(context).inflate(R.layout.quickphrases, this);
        this.recyclerView = (RecyclerView) findViewById(R.id.quick_phrase_list);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(context));
        setVisibility(View.GONE);
        this.keyboardSwitcher = KeyboardSwitcher.getInstance();
        this.keyboardHeight = ResourceConfigManager.getKeyboardHeightWithPadding(getContext().getResources());
    }

    public void setQuickPhrases(List<QuickPhrasesAdapter.QuickPhrase> list) {
        QuickPhrasesAdapter adapter = (QuickPhrasesAdapter) this.recyclerView.getAdapter();
        if (adapter == null) {
            QuickPhrasesAdapter newAdapter = new QuickPhrasesAdapter(list);
            newAdapter.setListener(this);
            this.recyclerView.setAdapter(newAdapter);
        } else {
            adapter.setPhrases(list);
            adapter.notifyDataSetChanged();
        }
    }

    public void setQuickPhrasesListener(OnQuickPhraseSelectedListener listener) {
        this.quickPhraseListener = listener;
    }

    @Override // android.widget.RelativeLayout, android.view.View
    public void onMeasure(int i, int i2) {
        int i3;
        int iM5585a = ResourceConfigManager.getScreenWidthPixels(getContext().getResources()) / 2;
        Keyboard keyboard = this.keyboardSwitcher.getCurrentKeyboard();
        if (keyboard != null && keyboard.mOccupiedHeight != 0) {
            i3 = keyboard.mOccupiedHeight;
        } else {
            i3 = this.keyboardHeight;
        }
        setMeasuredDimension(iM5585a, i3 + getPaddingTop() + getPaddingBottom());
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(getMeasuredWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(getMeasuredHeight(), View.MeasureSpec.EXACTLY));
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public void show() {
        setVisibility(View.VISIBLE);
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public void hide() {
        setVisibility(View.GONE);
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public int getSlideBoardWidth() {
        return ResourceConfigManager.getScreenWidthPixels(getResources()) / 2;
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public void setXTranslation(float f) {
        setTranslationX(f);
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public void setListener(SlideboardComponent.Listener listener) {
        this.slideListener = listener;
    }
}
