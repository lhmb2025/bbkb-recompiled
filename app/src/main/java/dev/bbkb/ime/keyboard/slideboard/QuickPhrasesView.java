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
import dev.bbkb.ime.keyboard.KeyboardColorManager;
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

    /** @param defStyleAttr the default style attribute */
    public QuickPhrasesView(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.quickphrases, this);
        this.recyclerView = (RecyclerView) findViewById(R.id.quick_phrase_list);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(context));
        setVisibility(View.GONE);
        this.keyboardSwitcher = KeyboardSwitcher.getInstance();
        this.keyboardHeight = ResourceConfigManager.getKeyboardHeightWithPadding(getContext().getResources());
        applyColors();
    }

    /** Rebinds the tiles and repaints the ground on a theme change (dark mode, wallpaper). */
    private final kotlin.jvm.functions.Function0<kotlin.Unit> colorObserver = () -> {
        applyColors();
        RecyclerView.Adapter<?> adapter = QuickPhrasesView.this.recyclerView.getAdapter();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        return kotlin.Unit.INSTANCE;
    };

    /** The panel ground, a step darker than the tiles. */
    private void applyColors() {
        setBackground(KeyboardColorManager.fill(KeyboardColorManager.INSTANCE.getSlideGroundColor()));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyboardColorManager.INSTANCE.addObserver(this.colorObserver);
        applyColors();
    }

    @Override
    protected void onDetachedFromWindow() {
        KeyboardColorManager.INSTANCE.removeObserver(this.colorObserver);
        super.onDetachedFromWindow();
    }

    /** Rows share the panel height evenly, so every phrase is visible without scrolling. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateTileHeight(h);
    }

    private void updateTileHeight(int panelHeight) {
        QuickPhrasesAdapter adapter = (QuickPhrasesAdapter) this.recyclerView.getAdapter();
        if (adapter == null || panelHeight <= 0) {
            return;
        }
        int count = Math.max(1, adapter.getItemCount());
        int gap = getResources().getDimensionPixelSize(R.dimen.quick_phrase_tile_gap);
        int available = panelHeight - getPaddingTop() - getPaddingBottom() - gap;
        if (adapter.setTileHeight(available / count)) {
            post(adapter::notifyDataSetChanged);
        }
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
        updateTileHeight(getHeight());
    }

    public void setQuickPhrasesListener(OnQuickPhraseSelectedListener listener) {
        this.quickPhraseListener = listener;
    }

    /**
     * @param widthMeasureSpec the width measure spec (ignored)
     * @param heightMeasureSpec the height measure spec (ignored)
     */
    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
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

    @Override
    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    @Override
    public void show() {
        setVisibility(View.VISIBLE);
    }

    @Override
    public void hide() {
        setVisibility(View.GONE);
    }

    @Override
    public int getSlideBoardWidth() {
        return ResourceConfigManager.getScreenWidthPixels(getResources()) / 2;
    }

    /** @param translationX the panel's x translation */
    @Override
    public void setXTranslation(float translationX) {
        setTranslationX(translationX);
    }

    @Override
    public void setListener(SlideboardComponent.Listener listener) {
        this.slideListener = listener;
    }
}
