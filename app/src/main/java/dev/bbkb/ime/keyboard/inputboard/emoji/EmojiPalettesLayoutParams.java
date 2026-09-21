package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.res.Resources;
import android.view.View;
import android.widget.LinearLayout;

import androidx.viewpager.widget.ViewPager;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.inputboard.BoardHeightPolicy;

/**
 * Layout parameters calculator for the emoji picker view.
 * 
 * Computes all necessary dimensions for emoji picker layout:
 * - Total emoji palettes height (accounting for suggestion strip)
 * - ViewPager height for emoji pages
 * - Category tab strip height
 * - Individual emoji key dimensions
 * - Padding and gap values (horizontal/vertical)
 * 
 * All dimensions are calculated from device resources and screen metrics
 * to ensure proper emoji picker sizing across different devices.
 */


final class EmojiPalettesLayoutParams {

    public final int emojiPalettesHeight;

    public final int keyboardHeight;

    public final int pagerHeight;

    public final int keyHeight;

    public final int verticalGap;

    private final int categoryTabHeight;

    private final int keyboardBottomPadding;

    private final int horizontalGap;

    private final int keyboardTopPadding;

    public EmojiPalettesLayoutParams(Resources resources) throws Resources.NotFoundException {
        int iM5592b = ResourceConfigManager.getKeyboardHeight(resources);
        int iM5585a = ResourceConfigManager.getScreenWidthPixels(resources);
        this.categoryTabHeight = (int) resources.getDimension(R.dimen.config_emoji_category_page_id_height);
        this.keyboardHeight = BoardHeightPolicy.fallbackHeight(resources);
        this.emojiPalettesHeight = (iM5592b - ResourceConfigManager.getSuggestionsStripHeight(resources)) - this.categoryTabHeight;
        // IB-19: pagerHeight used to be (emojiPalettesHeight - bottomMargin) - 0 with
        // bottomMargin fixed at 0, and that 0 was then applied as a layout bottomMargin below.
        this.pagerHeight = this.emojiPalettesHeight;
        this.keyboardBottomPadding = (int) resources.getFraction(R.fraction.config_keyboard_bottom_padding_holo, iM5592b, iM5592b);
        this.keyboardTopPadding = (int) resources.getFraction(R.fraction.config_keyboard_top_padding_holo, iM5592b, iM5592b);
        this.horizontalGap = (int) resources.getFraction(R.fraction.config_key_horizontal_gap_holo, iM5585a, iM5585a);
        this.verticalGap = (int) resources.getFraction(R.fraction.config_key_vertical_gap_holo, iM5592b, iM5592b);
        int i = this.keyboardBottomPadding;
        int i2 = (iM5592b - i) - this.keyboardTopPadding;
        int i3 = this.verticalGap;
        this.keyHeight = ((i2 + i3) / 4) - ((i3 - i) / 2);
    }

    public void applyToViewPager(ViewPager viewPager) {
        LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) viewPager.getLayoutParams();
        layoutParams.height = this.pagerHeight;
        viewPager.setLayoutParams(layoutParams);
    }
    
    public void applyToPagerContainer(View pagerContainer) {
        LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) pagerContainer.getLayoutParams();
        layoutParams.height = this.pagerHeight;
        pagerContainer.setLayoutParams(layoutParams);
    }

    public void applyToIndicatorView(View view) {
        LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) view.getLayoutParams();
        layoutParams.height = this.categoryTabHeight;
        view.setLayoutParams(layoutParams);
    }
}
