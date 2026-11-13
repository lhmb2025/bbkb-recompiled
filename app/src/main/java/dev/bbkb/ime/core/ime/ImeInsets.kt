package dev.bbkb.ime.core.ime
import android.graphics.Rect
import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R

/**
 * The IME window's inset geometry: the nav-bar bottom padding applied to the root input view,
 * and the touchable region / content top reported to the framework from [compute].
 */
class ImeInsets {
    // Scratch rects: compute runs on every IME-window traversal.
    private val tmpRootVisibleRect = Rect()
    private val tmpChildVisibleRect = Rect()
    private val tmpRegionBounds = Rect()

    // keyboard_frame cached per root instance, so a recreated input view re-resolves it.
    private var cachedRoot: View? = null
    private var cachedKeyboardFrame: View? = null

    private fun keyboardFrameOf(root: View): View? {
        if (cachedRoot !== root) {
            cachedRoot = root
            cachedKeyboardFrame = root.findViewById(R.id.keyboard_frame)
        }
        return cachedKeyboardFrame
    }

    /**
     * Fill [insets] from the real on-screen bounds of keyboard_frame and [auxBarView], so it holds
     * with or without nav-bar padding. On a PKB device only the aux bar may show; pass through only
     * when nothing is shown. [beforeRegion] runs after the pass-through checks.
     * Returns the content top, or null for a pass-through window.
     */
    fun compute(
        insets: InputMethodService.Insets,
        root: View,
        keyboardView: View,
        auxBarView: View,
        isPkbDevice: Boolean,
        mainKeyboardShowing: Boolean,
        beforeRegion: () -> Unit,
    ): Int? {
        val height = root.height
        val auxBarShown = auxBarView.isShown
        if (BuildConfig.DEBUG) Log.w("BBKBdiag", "onComputeInsets: isPkb=$isPkbDevice mainKbView.vis=${keyboardView.visibility} mainKbView.isShown=${keyboardView.isShown} mainKbShowing=$mainKeyboardShowing auxBarShown=$auxBarShown auxBarH=${auxBarView.height} rootH=$height rootW=${root.width}")
        if (isPkbDevice && keyboardView.visibility == View.GONE && !auxBarShown) {
            if (BuildConfig.DEBUG) Log.w("BBKBdiag", "onComputeInsets: EARLY-RETURN pass-through (PKB, mainKb GONE, aux bar not shown)")
            passThrough(insets, height)
            (root as? InputView)?.setNavBarFillShown(false)
            return null
        }
        beforeRegion()

        val region = insets.touchableRegion
        region.setEmpty()
        root.getGlobalVisibleRect(tmpRootVisibleRect)
        var anyVisible = unionVisibleInRoot(region, keyboardFrameOf(root))
        anyVisible = unionVisibleInRoot(region, auxBarView) || anyVisible

        if (!anyVisible) {
            if (BuildConfig.DEBUG) Log.w("BBKBdiag", "onComputeInsets: no visible IME content - pass-through")
            passThrough(insets, height)
            (root as? InputView)?.setNavBarFillShown(false)
            return null
        }
        (root as? InputView)?.setNavBarFillShown(true)
        insets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        region.getBounds(tmpRegionBounds)
        val contentTop = tmpRegionBounds.top
        insets.contentTopInsets = contentTop
        insets.visibleTopInsets = contentTop
        if (BuildConfig.DEBUG) Log.w("BBKBdiag", "onComputeInsets: SET region=$tmpRegionBounds contentTop=$contentTop mainKbShowing=$mainKeyboardShowing auxBarShown=$auxBarShown isPkb=$isPkbDevice")
        return contentTop
    }

    private fun passThrough(insets: InputMethodService.Insets, height: Int) {
        insets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE
        insets.contentTopInsets = height
        insets.visibleTopInsets = height
    }

    /** Union [v]'s root-local visible bounds into [region]; [tmpRootVisibleRect] must be set. True if added. */
    private fun unionVisibleInRoot(region: Region, v: View?): Boolean {
        if (v != null && v.isShown && v.getGlobalVisibleRect(tmpChildVisibleRect)) {
            tmpChildVisibleRect.offset(-tmpRootVisibleRect.left, -tmpRootVisibleRect.top)
            region.union(tmpChildVisibleRect)
            return true
        }
        return false
    }

    companion object {
        /**
         * Apply the nav-bar bottom inset as padding on every dispatch (edge-to-edge window).
         * Explicit because `fitsSystemWindows` padding lands on an unspecified frame, so the
         * keyboard sometimes drew under the nav bar.
         */
        fun installNavBarInsetListener(root: View) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                // Gesture nav reports only the pill via navigationBars() (and navigation_bar_height
                // shrinks too) while side buttons still draw full height; tappableElement() reports
                // that full height, so the max is the same in both modes (~0 with no side buttons).
                val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val tappableBottom = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
                val effectiveBottom = maxOf(navBottom, tappableBottom)
                if (v.paddingBottom != effectiveBottom) {
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, effectiveBottom)
                }
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }
    }
}
