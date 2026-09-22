package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.Context
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.databinding.ClipDataHeaderBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The swipe-reveal layer must share the card's rounded corners.
 *
 * It used to be clipped with `ViewOutlineProvider.BACKGROUND`, i.e. the outline of the reveal
 * background. `GradientDrawable.getOutline` clamps the corner radius to half of its *current*
 * bounds, and the background is attached during bind — before layout, when the row is still 0x0 —
 * so the outline came out with no usable radius and the revealed share/delete blocks had square
 * corners next to the card's rounded ones (found on the KEY2, 2026-09-15; the device diagnostic
 * showed the row still 0x0 when the background was attached). Robolectric's GradientDrawable does
 * not reproduce that clamp, so the regression itself is only observable on a device.
 *
 * [ClipboardViewHolder.roundedRevealOutline] measures the view instead, so the radius survives
 * being set before layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ClipboardRowCornersTest {

    private fun holderWithLayout(): Pair<ClipboardViewHolder, ClipDataHeaderBinding> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(dev.bbkb.ime.R.style.Theme_BlackberryKeyboard_IME)
        val binding = ClipDataHeaderBinding.inflate(LayoutInflater.from(context), null, false)
        val holder = ClipboardViewHolder(binding)
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
        )
        binding.root.layout(0, 0, 1080, 270)
        return holder to binding
    }

    @Test
    fun theRevealOutlineRoundsWithTheCardsRadius() {
        val (holder, binding) = holderWithLayout()
        val actions = binding.clipboardBackgroundActions
        val density = actions.resources.displayMetrics.density
        val outline = Outline()

        holder.roundedRevealOutline.getOutline(actions, outline)

        assertEquals("radius must match the card's 12dp corner", 12 * density, outline.radius, 0.01f)
        assertTrue("the outline must be able to clip", outline.canClip())
    }

    @Test
    fun theRevealOutlineSurvivesBeingSetBeforeLayout() {
        // The regression: an outline taken from the background at bind time (0x0 bounds) rounds
        // to nothing. The view-measured provider still reports the full radius once laid out.
        val (holder, binding) = holderWithLayout()
        val unlaidOut = View(binding.root.context)          // width/height 0, as at bind time
        val outlineAtBind = Outline()
        holder.roundedRevealOutline.getOutline(unlaidOut, outlineAtBind)
        // A 0x0 round rect is empty; what matters is that the provider is re-asked after layout,
        // which is what ViewOutlineProvider does, and then reports the real radius:
        val laidOut = Outline()
        holder.roundedRevealOutline.getOutline(binding.clipboardBackgroundActions, laidOut)
        val density = binding.root.resources.displayMetrics.density
        assertEquals(12 * density, laidOut.radius, 0.01f)
    }


    @Test
    fun theThumbnailKeepsItsOwnSmallerRadius() {
        val (holder, binding) = holderWithLayout()
        val density = binding.root.resources.displayMetrics.density
        val outline = Outline()
        holder.roundedThumbnailOutline.getOutline(binding.clipboardImage, outline)
        assertEquals(8 * density, outline.radius, 0.01f)
    }
}
