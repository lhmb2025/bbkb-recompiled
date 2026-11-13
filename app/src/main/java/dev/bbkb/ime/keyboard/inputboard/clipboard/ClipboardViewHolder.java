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

/** One clipboard row ({@code clip_data_header.xml}). */
public class ClipboardViewHolder extends RecyclerView.ViewHolder {

    private final ClipDataHeaderBinding binding;

    final TextView dataTextView;

    final ImageView clipImageView;

    final LinearLayout textContainer;

    /** The card (row root): takes the paste tap and the menu long-press. */
    public final LinearLayout foreground;

    final ImageView pinGlyph;

    final ImageButton overflowButton;

    /** Null until the first bind. */
    @Nullable
    ClipEntry boundEntry;

    /** Allocated once per holder; colours and radius are applied at bind time. */
    final GradientDrawable cardBackground = new GradientDrawable();

    /** Rounded-corner outline for a link thumbnail. */
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

        // Taps belong to the card. View.onTouchEvent consumes a gesture when a view is clickable
        // or long-clickable, so the text container must be neither or it swallows touches.
        this.textContainer.setClickable(false);
        this.textContainer.setFocusable(false);
        this.textContainer.setLongClickable(false);
    }

    /**
     * Whether "Show full text" would show more. Both tests are needed: a line ending in a hard
     * newline is not ellipsized even when more lines follow it.
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
