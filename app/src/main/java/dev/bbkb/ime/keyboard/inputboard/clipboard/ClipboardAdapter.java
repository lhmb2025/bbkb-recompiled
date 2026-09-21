package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.R;
import dev.bbkb.ime.databinding.ClipDataHeaderBinding;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import java.util.List;



public class ClipboardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<ClipboardItem> clipboardItems;

    private ClipboardActionCallback actionCallback;

    private final ClipboardWebImageProvider imageProvider;

    private int expandedItemHeight;

    private final Context context;

    /**
     * Audit IB-4: these were all recomputed on every bind, i.e. on every row of every
     * scroll of the clipboard list. None of them depends on the bound item.
     */
    private final String passwordKeeperAddText;

    private final String passwordKeeperMaskText;

    private final java.util.regex.Matcher webUrlMatcher = Patterns.WEB_URL.matcher("");

    
    interface TextExpansionListener {
        void onTextExpandable(boolean expandable);
    }

    public ClipboardWebImageProvider getImageProvider() {
        return this.imageProvider;
    }

    // Removed ViewHolder type 'd' - menu expansion no longer used

    ClipboardAdapter(Context context, List<ClipboardItem> list) {
        this.clipboardItems = list;
        this.imageProvider = new ClipboardWebImageProvider(context);
        this.context = context;
        this.passwordKeeperAddText = context.getString(R.string.clip_password_keeper_add);
        this.passwordKeeperMaskText = context.getString(R.string.clip_password_keeper_mask);
        initializeItemHeight(context);
    }

    private void initializeItemHeight(Context context) {
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.listPreferredItemHeightLarge, typedValue, true);
        Resources resources = context.getResources();
        this.expandedItemHeight = TypedValue.complexToDimensionPixelSize(typedValue.data, resources.getDisplayMetrics()) + resources.getInteger(R.integer.config_clipboard_extra_header_height);
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        ClipDataHeaderBinding binding = ClipDataHeaderBinding.inflate(
            LayoutInflater.from(viewGroup.getContext()), 
            viewGroup, 
            false
        );
        ClipboardViewHolder holder = new ClipboardViewHolder(binding);
        // The click listeners read holder.boundClipItem, so they survive rebinding
        // and belong here rather than in bindClipboardItem (audit IB-4).
        setupClickListeners(holder);
        return holder;
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int i) {
        bindClipboardItem((ClipboardViewHolder) holder, i);
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public int getItemViewType(int i) {
        return 0; // Only one view type now
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public int getItemCount() {
        return this.clipboardItems.size();
    }

    private void bindClipboardItem(ClipboardViewHolder bVar, int i) {
        String string;
        ClipboardItem aVar = this.clipboardItems.get(i);
        bVar.boundClipItem = aVar;
        bVar.clipImageView.setImageResource(R.drawable.ic_inputboard_clipboard_text);
        String string2 = aVar.toString();
        if (string2 != null) {
            TextView textView = bVar.dataTextView;
            if (ClipboardHistoryManager.hasLabel(aVar.historyItem.mClipData, this.passwordKeeperAddText)) {
                string = this.passwordKeeperMaskText;
            } else {
                string = string2;
            }
            textView.setText(string);
            // Accessibility: describe the row with what it actually shows (so a masked
            // password stays masked). setContentDescription() used to compute this
            // string and discard it.
            bVar.itemView.setContentDescription(string);
            String strTrim = string2.trim();
            if (this.webUrlMatcher.reset(strTrim).matches()) {
                OpenGraphMetadata c1014lM6983a = this.imageProvider.getCachedMetadata(strTrim);
                if (c1014lM6983a != null) {
                    displayOpenGraphData(bVar, c1014lM6983a);
                } else {
                    loadWebImage(bVar, strTrim);
                }
            }
        }
        
        // Apply colors from KeyboardColorManager
        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
            final float density = bVar.itemView.getResources().getDisplayMetrics().density;
            // Remove any drawable background/borders first
            bVar.foreground.setBackground(null);

            if (modernBoards) {
                // M3 card: rounded keyAlt surface floating on the board background,
                // with rounded state-layer press feedback.
                // IB-4: the holder owns these objects; only their colour/radius is per-bind.
                bVar.cardBackground.setColor(KeyboardColorManager.INSTANCE.getKeyColorAlt());
                bVar.cardBackground.setCornerRadius(12 * density);
                bVar.foreground.setBackground(bVar.cardBackground);
                bVar.foreground.setForeground(KeyboardColorManager.pressedHighlight());
                // Round-clip the reveal layer so the swipe actions share the card's
                // corners; its own fill is the board background.
                android.widget.LinearLayout actions = bVar.getBinding().clipboardBackgroundActions;
                // Fill the reveal layer with the SHARE colour, not the board background. Both
                // actions sit at the right end behind a weighted spacer, so the strip the card's
                // rounded corners uncover when the row is fully open is this fill: with the board
                // background it read as a dark notch cut out of the share button (KEY2,
                // 2026-09-15 — mid-drag the corners are over the button itself and look right,
                // only the settled state showed it). Share is the leftmost action, so its colour
                // is the one that should continue underneath the card.
                bVar.revealClip.setColor(KeyboardColorManager.INSTANCE.getAccentColor());
                bVar.revealClip.setCornerRadius(12 * density);
                actions.setBackground(bVar.revealClip);
                // The outline must come from the view's own size, not from the background: the
                // background's radius is clamped to half its bounds, and bind runs before layout
                // (0x0), which produced square corners on the revealed actions.
                actions.setOutlineProvider(bVar.roundedRevealOutline);
                actions.setClipToOutline(true);
                // Mock schema: both actions on the RIGHT, revealed by one left swipe
                // (SwipeToRevealHelper's single-sided mode) — order [spacer, share, delete].
                if (bVar.shareButton != null && actions.indexOfChild(bVar.shareButton) == 0) {
                    actions.removeView(bVar.shareButton);
                    actions.addView(bVar.shareButton, actions.indexOfChild(bVar.deleteButton));
                }
                setSwipeButtonWidth(bVar.shareButton, 72, density);
                setSwipeButtonWidth(bVar.deleteButton, 72, density);
                // Rounded thumbnail
                bVar.clipImageView.setOutlineProvider(bVar.roundedThumbnailOutline);
                bVar.clipImageView.setClipToOutline(true);
            } else {
                // Legacy: flat full-bleed row on the background slot.
                bVar.foreground.setBackgroundColor(
                        KeyboardColorManager.INSTANCE.getBackgroundColor());
                bVar.foreground.setForeground(null);
                android.widget.LinearLayout actions = bVar.getBinding().clipboardBackgroundActions;
                actions.setBackground(null);
                actions.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                actions.setClipToOutline(false);
                // Legacy schema: share on the left, delete on the right.
                if (bVar.shareButton != null && actions.indexOfChild(bVar.shareButton) != 0) {
                    actions.removeView(bVar.shareButton);
                    actions.addView(bVar.shareButton, 0);
                }
                setSwipeButtonWidth(bVar.shareButton, 90, density);
                setSwipeButtonWidth(bVar.deleteButton, 90, density);
                bVar.clipImageView.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                bVar.clipImageView.setClipToOutline(false);
            }

            bVar.dataTextView.setTextColor(
                KeyboardColorManager.INSTANCE.getTextColor()
            );
            // Tint icon
            KeyboardColorManager.INSTANCE.tint(bVar.clipImageView);
            // Themed header divider (legacy only — Material drops the hairline),
            // re-applied per bind so an in-place theme change never leaves a stale
            // color. Sized fill(): the ImageView is wrap_content, so it measures from
            // the drawable's intrinsic width.
            bVar.getBinding().dividerImage.setVisibility(
                    modernBoards ? android.view.View.GONE : android.view.View.VISIBLE);
            if (!modernBoards) {
                bVar.getBinding().dividerImage.setImageDrawable(KeyboardColorManager.fill(
                        KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_DISABLED),
                        Math.max(1, Math.round(density)), Math.round(56 * density)));
            }

            // Swipe-action colors. Delete stays semantic red everywhere; share is the
            // palette accent under Material (glyph in the board background color for
            // contrast) and the legacy Material-blue otherwise (white glyph).
            if (bVar.shareButton != null) {
                bVar.shareButton.setBackgroundColor(modernBoards
                        ? KeyboardColorManager.INSTANCE.getAccentColor()
                        : this.context.getColor(R.color.swipe_background_share));
                if (bVar.shareButton.getDrawable() != null) {
                    androidx.core.graphics.drawable.DrawableCompat.setTint(
                        bVar.shareButton.getDrawable(), modernBoards
                            ? KeyboardColorManager.INSTANCE.getBackgroundColor()
                            : android.graphics.Color.WHITE);
                }
            }
            if (bVar.deleteButton != null && bVar.deleteButton.getDrawable() != null) {
                androidx.core.graphics.drawable.DrawableCompat.setTint(
                    bVar.deleteButton.getDrawable(), android.graphics.Color.WHITE);
            }
        }
        
        // The long-click (expand) handler is the one bind-dependent listener: whether a
        // row is expandable depends on the text just set. It must also be cleared when
        // the recycled row is no longer truncated.
        bindTextExpansion(bVar);
        bVar.foreground.setTag(aVar);
    }

    /**
     * Enables long-press-to-expand only while the bound text is actually truncated,
     * and clears the handler otherwise - a recycled row used to keep the previous
     * item's long-click listener.
     */
    private void bindTextExpansion(final ClipboardViewHolder bVar) {
        checkTextTruncation(bVar, expandable -> {
            if (expandable) {
                bVar.foreground.setOnLongClickListener(view -> {
                    toggleTextExpansion(bVar);
                    return true;
                });
            } else {
                bVar.foreground.setOnLongClickListener(null);
                bVar.foreground.setLongClickable(false);
            }
        });
    }

    private static void setSwipeButtonWidth(android.view.View button, int widthDp, float density) {
        if (button == null) return;
        android.view.ViewGroup.LayoutParams params = button.getLayoutParams();
        int widthPx = (int) (widthDp * density);
        if (params != null && params.width != widthPx) {
            params.width = widthPx;
            button.setLayoutParams(params);
        }
    }

    private void setupClickListeners(final ClipboardViewHolder bVar) {
        // Tap to paste. The listener lives on the card (foreground) rather than the
        // text container so the whole row — thumbnail included — pastes, and so the
        // card's pressed state (Material state layer) actually triggers. The text
        // container must not be clickable or it would swallow the touch without
        // pressing the card.
        bVar.textContainer.setClickable(false);
        bVar.foreground.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                ClipboardAdapter.this.actionCallback.onPasteClip(bVar.boundClipItem);
            }
        });

        // Share button (revealed on swipe)
        if (bVar.shareButton != null) {
            bVar.shareButton.setOnClickListener(v -> {
                final ClipboardItem clipItem = bVar.boundClipItem;
                if (clipItem != null && actionCallback != null) {
                    actionCallback.onShareClip(clipItem);
                }
            });
        }

        // Delete button (revealed on swipe)
        if (bVar.deleteButton != null) {
            bVar.deleteButton.setOnClickListener(v -> {
                final ClipboardItem clipItem = bVar.boundClipItem;
                if (clipItem != null && actionCallback != null) {
                    actionCallback.onDeleteClip(clipItem);
                }
            });
        }
    }

        void toggleTextExpansion(ClipboardViewHolder bVar) {
        ViewGroup.LayoutParams layoutParams = bVar.foreground.getLayoutParams();
        boolean z = layoutParams.height == -2;
        layoutParams.height = z ? this.expandedItemHeight : -2;
        bVar.dataTextView.setMaxLines(z ? 3 : Integer.MAX_VALUE);
        bVar.dataTextView.setEllipsize(z ? TextUtils.TruncateAt.END : null);
        // Don't override background - it's already set in bindClipboardItem with proper color
        // Only this row changed; notifyDataSetChanged() re-ran the whole per-bind pass
        // for every visible row (audit IB-11).
        int position = bVar.getBindingAdapterPosition();
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position);
        }
    }

    private void checkTextTruncation(ClipboardViewHolder bVar, final TextExpansionListener eVar) {
        final TextView textView = bVar.dataTextView;
        textView.post(() -> {
            int lineCount = textView.getLineCount();
            if (lineCount <= 0 || textView.getLayout() == null) {
                return;
            }
            eVar.onTextExpandable(textView.getLayout().getEllipsisCount(lineCount - 1) > 0);
        });
    }

    void setClipboardItems(List<ClipboardItem> list) {
        this.clipboardItems = list;
    }

    public void setActionCallback(ClipboardActionCallback cVar) {
        this.actionCallback = cVar;
    }

        void displayOpenGraphData(ClipboardViewHolder bVar, OpenGraphMetadata c1014l) {
        ImageView imageView = bVar.clipImageView;
        Bitmap bitmapM7076d = c1014l.getImage();
        if (imageView != null && bitmapM7076d != null) {
            imageView.setImageBitmap(bitmapM7076d);
        }
        TextView textView = bVar.dataTextView;
        StringBuilder sb = new StringBuilder();
        if (c1014l.getTitle() != null) {
            sb.append(c1014l.getTitle());
            sb.append("\n");
        }
        sb.append(c1014l.getUrl());
        if (textView != null) {
            textView.setText(sb.toString());
        }
    }

    private void loadWebImage(ClipboardViewHolder bVar, String str) {
        OpenGraphMetadata c1014l = new OpenGraphMetadata();
        c1014l.setUrl(str);
        this.imageProvider.loadPreview(c1014l, createImageLoadCallback(bVar, c1014l));
    }

    private ClipboardImageLoadCallback createImageLoadCallback(final ClipboardViewHolder bVar, final OpenGraphMetadata c1014l) {
        return new ClipboardImageLoadCallback() {
            @Override // dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardImageLoadCallback
            public void onImageReady() {
                ClipboardAdapter.this.displayOpenGraphData(bVar, c1014l);
            }
        };
    }
}
