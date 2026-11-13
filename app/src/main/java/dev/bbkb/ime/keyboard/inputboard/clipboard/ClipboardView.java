package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.AudioAndHapticFeedbackManager;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dev.bbkb.ime.keyboard.inputboard.BoardHeightPolicy;


public class ClipboardView extends RelativeLayout implements ClipboardHistoryManager.OnHistoryChangedListener, ClipboardActionCallback {

    private OnPasteListener keyboardDelegate;

    private final RecyclerView recyclerView;

    private ClipboardHistoryManager clipboardHistoryManager;

    private boolean isOpening;

    private Context context;

    private final KeyboardSwitcher keyboardSwitcher;

    private final int clipboardMaxSize;

    private View emptyStateView;

    private ClipboardAdapter adapter;


    public interface OnPasteListener {
        /** Insert {@code text} into the focused field. */
        void onPaste(CharSequence text);
    }

    public ClipboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    /** @param i the default style attribute */
    public ClipboardView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        // Assigned here so every constructor path sets it.
        this.context = context;
        LayoutInflater.from(context).inflate(R.layout.clipboard, this);
        this.recyclerView = (RecyclerView) findViewById(R.id.clipboardView);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(context));
        this.emptyStateView = findViewById(R.id.empty_clipboard);
        
        hide();
        this.keyboardSwitcher = KeyboardSwitcher.getInstance();
        this.clipboardMaxSize = BoardHeightPolicy.fallbackHeight(getContext().getResources());

        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
            setBackgroundColor(modernBoards
                    ? KeyboardColorManager.INSTANCE.getBackgroundColor()
                    : KeyboardColorManager.INSTANCE.getKeyColor());
            if (modernBoards) {
                final float density = getResources().getDisplayMetrics().density;
                final int marginH = (int) (8 * density);
                final int marginV = (int) (3 * density);
                this.recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
                    @Override
                    public void getItemOffsets(android.graphics.Rect outRect, View view,
                            RecyclerView parent, RecyclerView.State state) {
                        outRect.set(marginH, marginV, marginH, marginV);
                    }
                });
            }

            android.view.View emptyClipboardView = findViewById(R.id.empty_clipboard);
            if (emptyClipboardView instanceof android.view.ViewGroup) {
                android.widget.ImageView emptyIcon = emptyClipboardView.findViewById(R.id.empty_clipboard_graphic);
                if (emptyIcon != null) {
                    KeyboardColorManager.INSTANCE.tint(
                        emptyIcon,
                        KeyboardColorManager.INSTANCE.getIconColorAlt()
                    );
                }

                android.view.ViewGroup emptyGroup = (android.view.ViewGroup) emptyClipboardView;
                int textColor = KeyboardColorManager.INSTANCE.getTextColor();
                for (int childIndex = 0; childIndex < emptyGroup.getChildCount(); childIndex++) {
                    android.view.View child = emptyGroup.getChildAt(childIndex);
                    if (child instanceof android.widget.TextView) {
                        ((android.widget.TextView) child).setTextColor(textColor);
                    }
                }

                if (modernBoards && emptyIcon != null) {
                    applyMaterialEmptyState(emptyGroup, emptyIcon);
                }
            }
        }
    }

    /** Modern empty state: a small icon in a tonal circle, the two text lines restacked beneath it. */
    private void applyMaterialEmptyState(android.view.ViewGroup emptyGroup,
            android.widget.ImageView emptyIcon) {
        final float density = getResources().getDisplayMetrics().density;
        int chipSize = (int) (112 * density);
        android.widget.FrameLayout.LayoutParams chipParams =
                new android.widget.FrameLayout.LayoutParams(
                        chipSize, chipSize, android.view.Gravity.CENTER);
        emptyIcon.setLayoutParams(chipParams);
        emptyIcon.setTranslationY(-44 * density);
        int pad = (int) (26 * density);
        emptyIcon.setPadding(pad, pad, pad, pad);
        // The legacy illustration is an ultra-wide 1342x755 canvas whose glyph shrinks to nothing
        // in the chip, so use the same glyph on a square canvas (the tint list carries over).
        emptyIcon.setImageResource(R.drawable.ic_inputboard_clipboard_empty_glyph);
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(KeyboardColorManager.INSTANCE.getKeyColorAlt());
        emptyIcon.setBackground(circle);

        java.util.List<android.widget.TextView> texts = new java.util.ArrayList<>();
        for (int i = 0; i < emptyGroup.getChildCount(); i++) {
            android.view.View child = emptyGroup.getChildAt(i);
            if (child instanceof android.widget.TextView) {
                texts.add((android.widget.TextView) child);
            }
        }
        // XML order: [0] = user hint (bottom line), [1] = headline.
        if (texts.size() >= 2) {
            android.widget.TextView headline = texts.get(1);
            android.widget.TextView hint = texts.get(0);
            styleMaterialEmptyText(headline, 34 * density, 16,
                    KeyboardColorManager.INSTANCE.getTextColor(), true);
            styleMaterialEmptyText(hint, 66 * density, 14,
                    KeyboardColorManager.INSTANCE.getHintColor(KeyboardColorManager.ALPHA_FULL), false);
        }
    }

    private void styleMaterialEmptyText(android.widget.TextView textView,
            float translationY, int textSizeSp, int color, boolean medium) {
        textView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
        textView.setTranslationY(translationY);
        textView.setGravity(android.view.Gravity.CENTER);
        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        textView.setTextColor(color);
        if (medium) {
            textView.setTypeface(dev.bbkb.ime.keyboard.KeyboardView
                    .mediumWeightTypeface(textView.getTypeface()));
        }
    }

    public boolean isVisible() {
        return getVisibility() == View.VISIBLE;
    }

    public boolean isOpening() {
        return this.isOpening;
    }

    /** @param z true while the board is being opened */
    public void setOpening(boolean z) {
        this.isOpening = z;
    }

    /** Open the board; pruning also loads the history the first time after unlock. */
    public void show() {
        setVisibility(View.VISIBLE);
        if (this.adapter != null && this.clipboardHistoryManager != null) {
            this.adapter.onBoardShown(ClipboardPrefs.isLinkPreviewsEnabled(this.context));
            this.clipboardHistoryManager.prune();
        }
        updateEmptyState();
    }

    public void hide() {
        setVisibility(View.GONE);
    }

    public void setListener(OnPasteListener listener) {
        this.keyboardDelegate = listener;
    }

    /**
     * @param i the width measure spec
     * @param i2 the height measure spec
     */
    @Override
    public void onMeasure(int i, int i2) {
        setMeasuredDimension(
                BoardHeightPolicy.measuredWidth(this),
                BoardHeightPolicy.measuredHeight(this, this.keyboardSwitcher, this.clipboardMaxSize));
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(getMeasuredWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(getMeasuredHeight(), View.MeasureSpec.EXACTLY));
    }

    @Override
    public void onHistoryChanged() {
        if (this.adapter == null || this.clipboardHistoryManager == null) {
            return;
        }
        List<ClipboardItem> items = ClipboardItem.build(
                this.clipboardHistoryManager.getPinned(), this.clipboardHistoryManager.getRecent());
        this.adapter.submitList(items);
        // Drop previews of rows that are gone.
        Set<Long> ids = new HashSet<>();
        for (ClipboardItem item : items) {
            if (item.entry != null) {
                ids.add(item.entry.getId());
            }
        }
        this.adapter.getImageProvider().retainOnly(ids);
        updateEmptyState();
    }

    public void initialize(ClipboardHistoryManager historyManager, Context context) {
        this.clipboardHistoryManager = historyManager;
        this.adapter = new ClipboardAdapter(context, ClipboardWebImageProvider.create(context));
        this.adapter.setActionCallback(this);
        this.recyclerView.setAdapter(this.adapter);
        this.clipboardHistoryManager.addHistoryChangedListener(this);
        onHistoryChanged();
    }

    public void unregisteredListener() {
        this.clipboardHistoryManager.removeHistoryChangedListener(this);
    }

    @Override
    public void onPasteClip(ClipEntry entry) {
        if (this.keyboardDelegate != null) {
            this.keyboardDelegate.onPaste(entry.getText());
        }
        AudioAndHapticFeedbackManager.getInstance().performAudioAndHapticFeedback(-1, this);
    }

    @Override
    public void onCopyClip(ClipEntry entry) {
        this.clipboardHistoryManager.copyToSystemClipboard(entry);
        // From Android 13 the system confirms every copy itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this.context, R.string.clipboard_copy_toast_message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPinClip(ClipEntry entry, boolean pin) {
        this.clipboardHistoryManager.setPinned(entry.getId(), pin);
    }

    @Override
    public void onDeleteClip(ClipEntry entry) {
        this.clipboardHistoryManager.removeEntry(entry.getId());
    }

    @Override
    public void onShareClip(ClipEntry entry) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, entry.getText());
        intent.setType("text/plain");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        this.context.startActivity(Intent.createChooser(intent, getResources().getText(R.string.send_to)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void updateEmptyState() {
        // The manager is null until initialize(), yet show() can run before it. The history
        // decides, not the adapter: submitList() diffs asynchronously.
        if (this.emptyStateView == null || this.clipboardHistoryManager == null) {
            return;
        }
        this.emptyStateView.setVisibility(
                this.clipboardHistoryManager.getHistory().isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Cancels link-preview fetches; call when this view is replaced or destroyed. */
    public void release() {
        if (this.adapter != null) {
            this.adapter.getImageProvider().release();
        }
    }
}
