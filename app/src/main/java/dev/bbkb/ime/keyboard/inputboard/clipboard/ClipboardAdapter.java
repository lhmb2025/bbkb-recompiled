package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.databinding.ClipDataHeaderBinding;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.keyboard.KeyboardView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The clipboard board's list: section headers and clip rows, diffed by {@link ClipboardItem}.
 *
 * <p>A tap on a row pastes it. A long-press on the row and a tap on its overflow button open the
 * same {@link ClipboardEntryMenu}; "Show full text" is handled here (it only changes how the row
 * is drawn), every other action goes to the {@link ClipboardActionCallback}.
 *
 * <p>Link previews are bound by entry id: the provider reports a finished preview for an id, and
 * the adapter rebinds whichever row shows that entry now, with {@link #PAYLOAD_PREVIEW} so only
 * the image and text are touched.
 */
public class ClipboardAdapter extends ListAdapter<ClipboardItem, RecyclerView.ViewHolder>
        implements ClipboardWebImageProvider.Listener {

    /** Partial rebind: the row's link preview arrived. */
    static final Object PAYLOAD_PREVIEW = new Object();

    /** How a built row menu is put on screen; replaced in tests, where nothing can be shown. */
    interface MenuLauncher {
        void show(PopupMenu menu);
    }

    private static final DiffUtil.ItemCallback<ClipboardItem> DIFF =
            new DiffUtil.ItemCallback<ClipboardItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull ClipboardItem a, @NonNull ClipboardItem b) {
                    return a.getStableId() == b.getStableId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull ClipboardItem a, @NonNull ClipboardItem b) {
                    return a.equals(b);
                }
            };

    private static final int COLLAPSED_MAX_LINES = 3;

    private final ClipboardWebImageProvider imageProvider;

    /**
     * Audit IB-4: resolved once rather than on every bind of every row of every scroll.
     */
    private final String passwordKeeperAddText;

    private final String passwordKeeperMaskText;

    /** Rows the user expanded with "Show full text". Cleared each time the board opens. */
    private final Set<Long> expandedIds = new HashSet<>();

    private ClipboardActionCallback actionCallback;

    /** The list this adapter is attached to, for deferring a notification out of a layout pass. */
    private RecyclerView attachedList;

    MenuLauncher menuLauncher = PopupMenu::show;

    ClipboardAdapter(Context context, ClipboardWebImageProvider imageProvider) {
        super(DIFF);
        setHasStableIds(true);
        this.imageProvider = imageProvider;
        this.passwordKeeperAddText = context.getString(R.string.clip_password_keeper_add);
        this.passwordKeeperMaskText = context.getString(R.string.clip_password_keeper_mask);
        imageProvider.setListener(this);
    }

    public ClipboardWebImageProvider getImageProvider() {
        return this.imageProvider;
    }

    public void setActionCallback(ClipboardActionCallback cVar) {
        this.actionCallback = cVar;
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).getStableId();
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).viewType;
    }

    boolean isMasked(ClipEntry entry) {
        return entry.hasLabel(this.passwordKeeperAddText);
    }

    /**
     * The board is opening: collapse expanded rows and apply the link-preview setting, rebinding
     * the visible rows if either changes what they show.
     */
    void onBoardShown(boolean linkPreviewsEnabled) {
        boolean changed = !this.expandedIds.isEmpty() || this.imageProvider.isEnabled() != linkPreviewsEnabled;
        this.expandedIds.clear();
        this.imageProvider.setEnabled(linkPreviewsEnabled);
        if (changed) {
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    // ── view holders ─────────────────────────────────────────────────────────

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == ClipboardItem.TYPE_HEADER) {
            return new HeaderViewHolder(
                    inflater.inflate(R.layout.clipboard_section_header, parent, false));
        }
        ClipboardViewHolder holder =
                new ClipboardViewHolder(ClipDataHeaderBinding.inflate(inflater, parent, false));
        // The listeners read holder.boundEntry, so they survive rebinding and are set once here
        // rather than on every bind (audit IB-4).
        holder.foreground.setOnClickListener(view -> {
            ClipEntry entry = holder.boundEntry;
            if (entry != null && this.actionCallback != null) {
                this.actionCallback.onPasteClip(entry);
            }
        });
        holder.foreground.setOnLongClickListener(view -> showMenu(holder));
        holder.overflowButton.setOnClickListener(view -> showMenu(holder));
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ClipboardItem item = getItem(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(item.headerTitle);
        } else if (item.entry != null) {
            bindClip((ClipboardViewHolder) holder, item.entry);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
            @NonNull List<Object> payloads) {
        ClipboardItem item = getItem(position);
        if (payloads.contains(PAYLOAD_PREVIEW) && holder instanceof ClipboardViewHolder
                && item.entry != null) {
            bindPreview((ClipboardViewHolder) holder, item.entry);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    private void bindClip(ClipboardViewHolder holder, ClipEntry entry) {
        holder.boundEntry = entry;
        boolean masked = isMasked(entry);
        boolean expanded = !masked && this.expandedIds.contains(entry.getId());
        String shown = masked ? this.passwordKeeperMaskText : entry.getText();

        TextView text = holder.dataTextView;
        text.setMaxLines(expanded ? Integer.MAX_VALUE : COLLAPSED_MAX_LINES);
        text.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
        // Accessibility: describe the row with what it actually shows, so a masked password stays
        // masked.
        holder.itemView.setContentDescription(shown);
        holder.pinGlyph.setVisibility(entry.isPinned() ? View.VISIBLE : View.GONE);

        applyStyle(holder);
        bindPreview(holder, entry);
    }

    /**
     * The leading image and the text: a link's preview (thumbnail, title over the address) once
     * one has been fetched, otherwise the link or text icon and the clip itself.
     */
    private void bindPreview(ClipboardViewHolder holder, ClipEntry entry) {
        boolean masked = isMasked(entry);
        String previewUrl = ClipboardWebImageProvider.previewUrlFor(entry.getText(), masked);
        OpenGraphMetadata preview = null;
        if (previewUrl != null) {
            // A no-op once the entry has a result (or is being fetched); asked first so a result
            // that is ready immediately is shown by this bind.
            this.imageProvider.request(entry.getId(), previewUrl);
            preview = this.imageProvider.previewFor(entry.getId());
        }

        Bitmap thumbnail = preview != null ? preview.getImage() : null;
        if (thumbnail != null) {
            showThumbnail(holder.clipImageView, thumbnail);
        } else {
            boolean link = !masked && ClipboardWebImageProvider.isWebUrl(entry.getText().trim());
            showIcon(holder.clipImageView, link
                    ? R.drawable.ic_inputboard_clipboard_link
                    : R.drawable.ic_inputboard_clipboard_text);
        }

        if (preview != null && preview.getTitle() != null) {
            holder.dataTextView.setText(preview.getTitle() + "\n" + preview.getUrl());
        } else {
            holder.dataTextView.setText(masked ? this.passwordKeeperMaskText : entry.getText());
        }
    }

    private void showThumbnail(ImageView image, Bitmap bitmap) {
        image.setPadding(0, 0, 0, 0);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageTintList(null);
        image.setImageBitmap(bitmap);
    }

    private void showIcon(ImageView image, int drawable) {
        int pad = Math.round(8 * image.getResources().getDisplayMetrics().density);
        image.setPadding(pad, pad, pad, pad);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageResource(drawable);
        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            KeyboardColorManager.INSTANCE.tint(image);
        }
    }

    /** The modern card or the legacy flat row, from the keyboard palette. */
    private void applyStyle(ClipboardViewHolder holder) {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) {
            return;
        }
        KeyboardColorManager colors = KeyboardColorManager.INSTANCE;
        boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
        float density = holder.itemView.getResources().getDisplayMetrics().density;
        if (modernBoards) {
            // M3 card: a rounded keyAlt surface floating on the board background, with a rounded
            // state layer for press feedback.
            holder.cardBackground.setColor(colors.getKeyColorAlt());
            holder.cardBackground.setCornerRadius(12 * density);
            holder.foreground.setBackground(holder.cardBackground);
            holder.foreground.setForeground(KeyboardColorManager.pressedHighlight());
            holder.clipImageView.setOutlineProvider(holder.roundedThumbnailOutline);
            holder.clipImageView.setClipToOutline(true);
            holder.getBinding().dividerImage.setVisibility(View.GONE);
        } else {
            // Legacy: a flat full-bleed row on the background slot, with the BlackBerry hairline
            // between icon and text. Re-applied per bind so a theme change in place never leaves a
            // stale colour; sized by fill() because the ImageView measures from the drawable.
            holder.foreground.setBackgroundColor(colors.getBackgroundColor());
            holder.foreground.setForeground(null);
            holder.clipImageView.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            holder.clipImageView.setClipToOutline(false);
            holder.getBinding().dividerImage.setVisibility(View.VISIBLE);
            holder.getBinding().dividerImage.setImageDrawable(KeyboardColorManager.fill(
                    colors.getIconColor(KeyboardColorManager.ALPHA_DISABLED),
                    Math.max(1, Math.round(density)), Math.round(40 * density)));
        }
        holder.overflowButton.setBackground(KeyboardColorManager.pressedHighlight());
        holder.dataTextView.setTextColor(colors.getTextColor());
        colors.tint(holder.overflowButton);
        colors.tint(holder.pinGlyph, modernBoards ? colors.getAccentColor() : colors.getIconColor());
    }

    // ── the row menu ─────────────────────────────────────────────────────────

    /** Long-press and overflow both land here, and both anchor the menu to the overflow button. */
    boolean showMenu(ClipboardViewHolder holder) {
        PopupMenu menu = buildMenu(holder);
        if (menu == null) {
            return false;
        }
        this.menuLauncher.show(menu);
        return true;
    }

    PopupMenu buildMenu(ClipboardViewHolder holder) {
        final ClipEntry entry = holder.boundEntry;
        if (entry == null) {
            return null;
        }
        boolean masked = isMasked(entry);
        boolean truncated = !this.expandedIds.contains(entry.getId()) && holder.isTextTruncated();
        return ClipboardEntryMenu.build(holder.overflowButton,
                ClipboardEntryMenu.actionsFor(entry, masked, truncated),
                action -> onMenuAction(entry, action));
    }

    void onMenuAction(ClipEntry entry, int action) {
        if (action == ClipboardEntryMenu.ACTION_SHOW_FULL_TEXT) {
            this.expandedIds.add(entry.getId());
            int position = positionOf(entry.getId());
            if (position != RecyclerView.NO_POSITION) {
                notifyItemChanged(position);
            }
            return;
        }
        ClipboardActionCallback callback = this.actionCallback;
        if (callback == null) {
            return;
        }
        switch (action) {
            case ClipboardEntryMenu.ACTION_PIN:
                callback.onPinClip(entry, true);
                break;
            case ClipboardEntryMenu.ACTION_UNPIN:
                callback.onPinClip(entry, false);
                break;
            case ClipboardEntryMenu.ACTION_COPY:
                callback.onCopyClip(entry);
                break;
            case ClipboardEntryMenu.ACTION_SHARE:
                callback.onShareClip(entry);
                break;
            case ClipboardEntryMenu.ACTION_DELETE:
                callback.onDeleteClip(entry);
                break;
            default:
                break;
        }
    }

    // ── link previews ────────────────────────────────────────────────────────

    @Override
    public void onPreviewReady(long entryId) {
        RecyclerView list = this.attachedList;
        if (list != null && list.isComputingLayout()) {
            // Finished inside a bind (the fetch did not need to suspend): RecyclerView refuses
            // change notifications mid-layout, so announce it once the pass is over.
            list.post(() -> onPreviewReady(entryId));
            return;
        }
        int position = positionOf(entryId);
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position, PAYLOAD_PREVIEW);
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.attachedList = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (this.attachedList == recyclerView) {
            this.attachedList = null;
        }
    }

    private int positionOf(long stableId) {
        List<ClipboardItem> items = getCurrentList();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getStableId() == stableId) {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    /** "Pinned" / "Recent". */
    static final class HeaderViewHolder extends RecyclerView.ViewHolder {

        private final TextView title;

        HeaderViewHolder(View view) {
            super(view);
            this.title = (TextView) view;
            ViewCompat.setAccessibilityHeading(view, true);
        }

        void bind(int titleRes) {
            this.title.setText(titleRes);
            if (KeyboardColorManager.INSTANCE.isInitialized()) {
                KeyboardColorManager colors = KeyboardColorManager.INSTANCE;
                boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
                // M3 list subheaders are primary-coloured; the legacy board keeps its grey.
                this.title.setTextColor(modernBoards
                        ? colors.getAccentColor()
                        : colors.getHintColor(KeyboardColorManager.ALPHA_FULL));
                this.title.setTypeface(KeyboardView.mediumWeightTypeface(this.title.getTypeface()));
            }
        }
    }
}
