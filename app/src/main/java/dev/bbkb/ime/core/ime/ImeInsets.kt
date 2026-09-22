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

    // Reused scratch rects for the region math (avoid per-call allocation).
    private val tmpRootVisibleRect = Rect()
    private val tmpChildVisibleRect = Rect()
    private val tmpRegionBounds = Rect()

    // Audit CT-4: keyboard_frame was resolved with findViewById on every onComputeInsets — i.e.
    // on every IME-window traversal (every key-press highlight invalidate, every strip swap) —
    // in the one method that was already at pains to avoid per-call allocation. Cached against
    // the root instance so an input-view recreation still re-resolves it.
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
     * Fill [insets] from the ACTUAL on-screen bounds of the visible IME content rather than from a
     * bottom-anchored height sum. This tracks the views regardless of whether nav-bar padding has
     * been applied, so it is correct both when the keyboard sits under the nav bar and when it is
     * lifted above it (see FABLE_VIEW_REPORT §0/§6). keyboard_frame contains the main keyboard,
     * emoji, clipboard, FCC, voice and CJK views; [auxBarView] is the suggestion/UIM strip above.
     *
     * On a PKB device the main keyboard view is GONE (hardware typing handles input); only the
     * aux bar is visible. The legacy code returned early there with an invalid touchableInsets
     * value (a pixel height instead of a TOUCHABLE_INSETS_* mode), so the framework never got a
     * region covering the strip and touches fell through to the app below. We short-circuit only
     * when nothing is actually shown (zero IME height, pass-through window); otherwise the shared
     * region computation already handles the "only the strip is showing" case.
     *
     * [beforeRegion] runs once the pass-through checks are done, before the region is built.
     * Returns the content top when a region was reported, or null for a pass-through window.
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
            return null
        }
        insets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        region.getBounds(tmpRegionBounds)
        val contentTop = tmpRegionBounds.top
        insets.contentTopInsets = contentTop
        insets.visibleTopInsets = contentTop
        if (BuildConfig.DEBUG) Log.w("BBKBdiag", "onComputeInsets: SET region=$tmpRegionBounds contentTop=$contentTop mainKbShowing=$mainKeyboardShowing auxBarShown=$auxBarShown isPkb=$isPkbDevice")
        return contentTop
    }

    /** Nothing is actually shown: report zero IME height and a pass-through window. */
    private fun passThrough(insets: InputMethodService.Insets, height: Int) {
        insets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE
        insets.contentTopInsets = height
        insets.visibleTopInsets = height
    }

    /**
     * Union the on-screen bounds of [v] (translated into root-local coordinates) into [region],
     * if the view is currently shown. Returns true if anything was added. The root's visible
     * rect must already be in [tmpRootVisibleRect].
     */
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
         * Deterministically inset the IME content above the navigation bar.
         *
         * The app targets an edge-to-edge SDK, so the IME window can extend behind the system
         * navigation bar. Previously the only thing lifting content above the nav bar was the
         * declarative `android:fitsSystemWindows="true"` on input_view.xml, whose padding is
         * applied on an unspecified frame relative to the first layout pass — so on some shows
         * the keyboard rendered *under* the nav bar (FABLE_VIEW_REPORT §0). Here we take over
         * inset handling explicitly and idempotently: apply the navigation-bar bottom inset as
         * bottom padding on every inset dispatch, and force a dispatch on attach / show / config
         * change so the result is the same every time. The touch region ([compute]) is derived
         * from real geometry, so it tracks this padding automatically.
         */
        fun installNavBarInsetListener(root: View) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                // Reserve the full height the system occupies at the bottom, identically in gesture
                // and 3-button navigation. Gesture nav reports only the thin "pill" via
                // navigationBars() (e.g. 63px here), while the device still renders side buttons at
                // the full button-bar height (126px) — and the framework's navigation_bar_height
                // resource ALSO shrinks in gesture mode, so flooring at that resource does not help.
                // tappableElement() is the inset for "where the system handles touches" (nav
                // buttons / side buttons), and it correctly reports the full height (126px) in BOTH
                // modes on this device, so max(navigationBars, tappableElement) yields the same
                // offset regardless of nav mode. On a pure-gesture device with no side buttons,
                // tappableElement() is ~0 and the keyboard simply sits above the pill, which is
                // correct there.
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
