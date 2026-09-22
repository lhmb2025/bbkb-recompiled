package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.drawable.DrawableCompat;

import dev.bbkb.ime.databinding.ClipDataHeaderBinding;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.BuildConfig;

/**
 * ViewHolder for clipboard items in the RecyclerView.
 * Uses ViewBinding for type-safe view access.
 * Manages the view state for each clipboard entry, including swipe-to-reveal functionality.
 */
public class ClipboardViewHolder extends RecyclerView.ViewHolder {

    // ViewBinding instance for type-safe view access
    private final ClipDataHeaderBinding binding;

    // Direct references for frequently accessed views (for convenience)
    final TextView dataTextView;
    final ImageView clipImageView;
    final LinearLayout textContainer;
    public final RelativeLayout foreground;
    public final ImageButton shareButton;
    public final ImageButton deleteButton;

    // Current clipboard item bound to this ViewHolder
    public ClipboardItem boundClipItem;

    /**
     * IB-4: style-derived objects, allocated once per holder instead of once per bind.
     *
     * <p>{@code onBindViewHolder} used to {@code new} both {@link GradientDrawable}s and an
     * anonymous {@link ViewOutlineProvider} on every scroll of the clipboard list — exactly the
     * per-frame allocation recycling exists to avoid. The colours and the corner radius are still
     * applied at bind time (they come from KeyboardColorManager); only the objects are reused.
     */
    final GradientDrawable cardBackground = new GradientDrawable();

    /** Round-clip for the swipe-reveal layer, so it shares the card's corners. */
    final GradientDrawable revealClip = new GradientDrawable();

    /** Rounded-corner outline for the thumbnail; the radius is fixed for this holder's density. */
    final ViewOutlineProvider roundedThumbnailOutline;

    /**
     * Rounded-corner outline for the swipe-reveal layer, measured from the view at outline time.
     *
     * <p>Not ViewOutlineProvider.BACKGROUND: that asks the background GradientDrawable for its
     * outline, and GradientDrawable clamps the corner radius to half the SHORTER SIDE of its
     * current bounds. The background is attached during bind, when the row has not been laid out
     * and its bounds are still 0x0, so the outline came out a 0-radius rectangle and the revealed
     * share/delete blocks had square corners over the card's rounded ones (KEY2, 2026-09-15).
     */
    final ViewOutlineProvider roundedRevealOutline;

    /**
     * Create a ViewHolder using ViewBinding
     * @param binding The ViewBinding for clip_data_header.xml
     */
    ClipboardViewHolder(ClipDataHeaderBinding binding) {
        super(binding.getRoot());
        this.binding = binding;
        
        // Cache view references for performance
        this.dataTextView = binding.clipboardDataText;
        this.clipImageView = binding.clipboardImage;
        this.textContainer = binding.clipboardTextContainer;
        this.foreground = binding.clipboardForeground;
        this.shareButton = binding.buttonShare;
        this.deleteButton = binding.buttonDelete;

        final float density = binding.getRoot().getResources().getDisplayMetrics().density;
        this.roundedThumbnailOutline = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 8 * density);
            }
        };
        this.roundedRevealOutline = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * density);
            }
        };

        // Ensure buttons are clickable and can receive touch events
        if (this.shareButton != null) {
            this.shareButton.setClickable(true);
            this.shareButton.setFocusable(true);
        }
        if (this.deleteButton != null) {
            this.deleteButton.setClickable(true);
            this.deleteButton.setFocusable(true);
        }

        // Apply white color to share/delete buttons for proper contrast on colored backgrounds
        // Share button has blue background (#1E88E5), delete button has red background (#E53935)
        // White provides optimal contrast on both Material Design colors regardless of theme
        int white = android.graphics.Color.WHITE;
        if (this.shareButton != null && this.shareButton.getDrawable() != null) {
            DrawableCompat.setTint(this.shareButton.getDrawable(), white);
        }
        if (this.deleteButton != null && this.deleteButton.getDrawable() != null) {
            DrawableCompat.setTint(this.deleteButton.getDrawable(), white);
        }

        // Paste taps are handled by the foreground card itself (see
        // ClipboardAdapter.setupClickListeners); children stay non-clickable so
        // touches reach the card and trigger its pressed state.
        //
        // setLongClickable(false) is as load-bearing as setClickable(false):
        // View.onTouchEvent consumes the gesture when clickable OR longClickable is set, and the
        // layout used to declare android:longClickable="true" here. The text container therefore
        // swallowed every tap over the text, so only the thumbnail area — the one child with
        // neither flag — let the touch reach the card, and pasting only worked there
        // (KEY2, 2026-09-15). The long-press that expands a row lives on the foreground too.
        this.textContainer.setClickable(false);
        this.textContainer.setFocusable(false);
        this.textContainer.setLongClickable(false);

        if (BuildConfig.DEBUG) android.util.Log.d("ClipboardSwipe", "ViewHolder created - buttons use native onClick listeners");
    }

    /**
     * Control the clickability of the foreground layer.
     * 
     * <p>This method is crucial for the swipe-to-reveal functionality. When an item
     * is revealed, the foreground must be non-clickable so that touches pass through
     * to the share/delete buttons underneath.
     * 
     * <p><b>When to call:</b>
     * <ul>
     *   <li>After revealing (swipe complete): {@code setForegroundClickable(false)}</li>
     *   <li>After closing (swipe back or tap outside): {@code setForegroundClickable(true)}</li>
     * </ul>
     * 
     * <p><b>Implementation:</b> Sets clickable and focusable on:
     * <ol>
     *   <li>The foreground RelativeLayout (main container)</li>
     *   <li>The textContainer (for tap-to-paste)</li>
     * </ol>
     * 
     * @param clickable true to enable clicks (item closed), false to disable (item revealed)
     */
    void setForegroundClickable(boolean clickable) {
        if (BuildConfig.DEBUG) android.util.Log.d("ClipboardSwipe", "setForegroundClickable(" + clickable + ")");

        // Make the entire foreground layer non-clickable when revealed
        // This allows touches to pass through to the buttons underneath.
        // Children are never clickable (paste lives on the foreground card itself),
        // so only the card's own clickability needs toggling.
        foreground.setClickable(clickable);
        foreground.setFocusable(clickable);
    }

    /**
     * Get the ViewBinding instance
     * @return The ClipDataHeaderBinding for this ViewHolder
     */
    public ClipDataHeaderBinding getBinding() {
        return binding;
    }
}
