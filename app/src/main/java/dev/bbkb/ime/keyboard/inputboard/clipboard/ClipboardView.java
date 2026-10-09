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

    /**
     * Audit IB-27: updateEmptyState() ran findViewById on every clipboard mutation,
     * although the view is already resolved in the constructor.
     */
    private View emptyStateView;

    private ClipboardAdapter adapter;


    public interface OnPasteListener {
        /** Insert {@code text} into the focused field. */
        void onPaste(CharSequence text);
    }

    public ClipboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public ClipboardView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        // Assigned here, not in the 2-arg constructor: that one delegates first, so a
        // direct 3-arg construction used to leave `context` null and NPE in
        // onShareClip (audit IB-27).
        this.context = context;
        LayoutInflater.from(context).inflate(R.layout.clipboard, this);
        this.recyclerView = (RecyclerView) findViewById(R.id.clipboardView);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(context));
        this.emptyStateView = findViewById(R.id.empty_clipboard);
        
        hide();
        this.keyboardSwitcher = KeyboardSwitcher.getInstance();
        this.clipboardMaxSize = BoardHeightPolicy.fallbackHeight(getContext().getResources());
        
        // Apply keyBackgroundColor from KeyboardColorManager to match keyboard keys
        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
            // Modern boards share the keyboard's background surface; legacy keyColor.
            setBackgroundColor(modernBoards
                    ? KeyboardColorManager.INSTANCE.getBackgroundColor()
                    : KeyboardColorManager.INSTANCE.getKeyColor());
            if (modernBoards) {
                // Card spacing: 8dp side margins, 6dp gaps between cards.
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
            
            // Apply colors to empty clipboard view
            android.view.View emptyClipboardView = findViewById(R.id.empty_clipboard);
            if (emptyClipboardView instanceof android.view.ViewGroup) {
                // Tint the empty clipboard icon with iconColorAlt
                android.widget.ImageView emptyIcon = emptyClipboardView.findViewById(R.id.empty_clipboard_graphic);
                if (emptyIcon != null) {
                    KeyboardColorManager.INSTANCE.tint(
                        emptyIcon,
                        KeyboardColorManager.INSTANCE.getIconColorAlt()
                    );
                }

                // Set text color for all TextViews to textColor
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

    /**
     * M3 empty state: the full-bleed illustration becomes a small icon in a tonal
     * circle centered on the board, with the two lines of text (XML order: user hint
     * first, headline second) restacked beneath it. Positioning uses translationY on
     * gravity-centered views so the legacy layout params stay untouched in spirit.
     */
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
        // The legacy full-bleed illustration is an ultra-wide 1342x755 canvas whose
        // glyph shrinks to nothing inside the chip — swap in the same glyph
        // re-centered on a square 48dp canvas (the view's tint list carries over).
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

    public void setOpening(boolean z) {
        this.isOpening = z;
    }

    /**
     * Open the board: apply the link-preview setting, collapse rows expanded last time, and prune
     * the history (which also loads it, the first time after unlock).
     */
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

    @Override // android.widget.RelativeLayout, android.view.View
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
        // Previews of rows that are gone (deleted, expired, evicted) are dropped with them.
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
        // From Android 13 the system confirms every copy itself; a toast as well would be a
        // second notice for the same thing.
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
        // The manager is null until initialize() has run, and show() is reachable from
        // ClipboardController.showClipboard() which only checks hasView() (audit IB-27). The
        // history, not the adapter, decides: submitList() diffs asynchronously, so the adapter's
        // count can still be the previous list's.
        if (this.emptyStateView == null || this.clipboardHistoryManager == null) {
            return;
        }
        this.emptyStateView.setVisibility(
                this.clipboardHistoryManager.getHistory().isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * Cancels in-flight link-preview fetches and drops their results. Called when the controller
     * binds a new board view in place of this one, and from {@link ClipboardController#destroy()}.
     */
    public void release() {
        if (this.adapter != null) {
            this.adapter.getImageProvider().release();
        }
    }
}
