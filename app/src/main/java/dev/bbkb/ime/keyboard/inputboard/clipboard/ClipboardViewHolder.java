package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.databinding.ClipDataHeaderBinding;

/**
 * One clipboard row ({@code clip_data_header.xml}): the card, its leading image, the text, the pin
 * glyph and the overflow button.
 */
public class ClipboardViewHolder extends RecyclerView.ViewHolder {

    private final ClipDataHeaderBinding binding;

    final TextView dataTextView;

    final ImageView clipImageView;

    final LinearLayout textContainer;

    /** The card: takes the paste tap and the menu long-press. It is the row's root view. */
    public final LinearLayout foreground;

    final ImageView pinGlyph;

    final ImageButton overflowButton;

    /** The entry this holder currently shows; null until the first bind. */
    @Nullable
    ClipEntry boundEntry;

    /**
     * IB-4: style-derived objects, allocated once per holder instead of once per bind. The colours
     * and the corner radius come from KeyboardColorManager and are still applied at bind time.
     */
    final GradientDrawable cardBackground = new GradientDrawable();

    /** Rounded-corner outline for a link thumbnail; the radius is fixed for this holder's density. */
    final ViewOutlineProvider roundedThumbnailOutline;

    ClipboardViewHolder(ClipDataHeaderBinding binding) {
        super(binding.getRoot());
        this.binding = binding;
        this.dataTextView = binding.clipboardDataText;
        this.clipImageView = binding.clipboardImage;
        this.textContainer = binding.clipboardTextContainer;
        this.foreground = binding.clipboardForeground;
        this.pinGlyph = binding.clipboardPinGlyph;
        this.overflowButton = binding.clipboardOverflow;

        final float density = binding.getRoot().getResources().getDisplayMetrics().density;
        this.roundedThumbnailOutline = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 8 * density);
            }
        };

        // The paste tap and the menu long-press both belong to the card, so its pressed state
        // (the Material state layer) shows for both. View.onTouchEvent consumes a gesture when a
        // view is clickable OR long-clickable, so the text container must be neither, or it
        // swallows every touch over the text and only the thumbnail pastes (KEY2, 2026-09-15).
        this.textContainer.setClickable(false);
        this.textContainer.setFocusable(false);
        this.textContainer.setLongClickable(false);
    }

    /**
     * Whether the bound text is cut off, i.e. "Show full text" would show more. Both tests are
     * needed: a line ending in a hard newline is not ellipsized even when more lines follow it.
     */
    boolean isTextTruncated() {
        Layout layout = this.dataTextView.getLayout();
        int lines = this.dataTextView.getLineCount();
        if (layout == null || lines <= 0) {
            return false;
        }
        return layout.getEllipsisCount(lines - 1) > 0
                || layout.getLineEnd(lines - 1) < this.dataTextView.getText().length();
    }

    public ClipDataHeaderBinding getBinding() {
        return binding;
    }
}
