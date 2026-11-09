package dev.bbkb.ime.core.settings.screens.keyeditor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.widget.Button
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.ResourceConfigManager
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.KeyboardColorManager
import dev.bbkb.ime.keyboard.KeyboardView
import dev.bbkb.ime.keyboard.slideboard.NumericSubpanelController
import dev.bbkb.ime.keyboard.slideboard.NumericSubpanelKeyboardView

/** Which of the keyboard's own boards an editor draws under its palette. */
internal enum class PreviewBoard {
    /** The on-screen keyboard's custom symbol page. */
    VKB_SYMBOLS,

    /** The physical keyboard's custom symbol page, as its Sym board draws it. */
    PKB_SYMBOLS,

    /** The slideboard's number pad: half the keyboard's width. */
    NUMPAD,
}

private const val ELEMENT_CUSTOM_SYMBOLS = 8
private const val ELEMENT_NUMBER_SUB_PANEL = 41
private const val CUSTOM_KEY_STYLE = "customSymbolKeyStyle"

/**
 * The keyboard an editor is editing, built by the keyboard's own [KeyboardBuilder] from the same
 * layout XML, language and size the keyboard uses, so the editor draws exactly what the keyboard
 * will: every theme, key shape and fixed key included.
 *
 * [show] puts the layout being edited on its editable keys; [slotKeys] are then those keys in
 * editor-slot order (reading order, the order of `KeyEditorSpec.load`).
 */
internal class EditorKeyboard private constructor(
    private val board: PreviewBoard,
    val keyboard: Keyboard,
    private val builder: KeyboardBuilder,
    /** What the pad's currency key shows while its slot still means "the currency key". */
    private val currencyLabel: String,
    private val currencyPlaceholders: Set<String>,
) {
    var slotKeys: List<Key> = emptyList()
        private set

    /** Draws [layout] (editor-slot order) on the editable keys. */
    fun show(layout: List<String>) {
        // A key cannot carry an empty label; the blank marker is what the keyboard itself gets.
        val labels = layout.map { it.ifEmpty { EMPTY_SLOT } }
        if (board == PreviewBoard.NUMPAD) {
            val symbols = labels.mapIndexed { position, symbol ->
                val isCurrencyKey = position == NumericSubpanelKeyboardView.CURRENCY_POSITION &&
                    symbol in currencyPlaceholders
                if (isCurrencyKey) currencyLabel else symbol
            }
            NumericSubpanelKeyboardView.applyKeysByPosition(keyboard, symbols, emptySet())
            slotKeys = NumericSubpanelKeyboardView.keysByPosition(keyboard)
        } else {
            builder.applyCustomSymbols(keyboard, labels)
            slotKeys = keyboard.keys.filter { it.outputText == CUSTOM_KEY_STYLE }
        }
    }

    companion object {
        /** The keyboard's theme, on [context]: what its views and its builder are created with. */
        fun themedContext(context: Context): Context =
            ContextThemeWrapper(context, R.style.KeyboardTheme_LXX)

        /**
         * The height the keyboard itself gives [board], capped at [maxHeightPx] so a short window
         * (landscape) still leaves room for the palette.
         */
        fun heightPx(context: Context, board: PreviewBoard, maxHeightPx: Int): Int {
            val resources = context.resources
            val own = if (board == PreviewBoard.NUMPAD) {
                ResourceConfigManager.getKeyboardHeightWithPadding(resources)
            } else {
                ResourceConfigManager.getKeyboardHeight(resources)
            }
            return minOf(own, maxHeightPx)
        }

        /**
         * Builds [board] [widthPx] wide (the pad takes half of it). Call
         * `SubtypeManager.ensureInitialized` first; the XML parse itself can run off the main thread.
         */
        fun build(themed: Context, board: PreviewBoard, widthPx: Int, heightPx: Int): EditorKeyboard {
            val subtypes = SubtypeManager.getInstance()
            if (board == PreviewBoard.NUMPAD) {
                val builder = KeyboardBuilder.Builder(themed, null)
                    .setSubtype(
                        SubtypeFactory.createSubtype(
                            subtypes.currentSubtypeLocale.toString(), "slide_numeric_sub_panel"
                        )
                    )
                    .setKeyboardGeometry(widthPx / 2, heightPx)
                    .build()
                val keyboard = builder.getKeyboardForShift(ELEMENT_NUMBER_SUB_PANEL, false)
                val currency = SettingsManager.getCurrencySymbol(
                    PrefsManager.getPrefs(themed), themed.resources
                )
                NumericSubpanelKeyboardView.updateCurrencyKey(keyboard, currency)
                val currencyLabel = NumericSubpanelKeyboardView.keysByPosition(keyboard)
                    .getOrNull(NumericSubpanelKeyboardView.CURRENCY_POSITION)?.label ?: "$"
                return EditorKeyboard(
                    board, keyboard, builder, currencyLabel,
                    NumericSubpanelController.currencyPlaceholders(currency),
                )
            }
            val builder = KeyboardBuilder.Builder(themed, null)
                .setKeyboardGeometry(widthPx, heightPx)
                .setSubtype(subtypes.currentSubtype)
                .useChosenLetterLayout()
                .setVoiceInputKeyEnabled(true)
                .build()
            val keyboard = builder.getKeyboardInternal(ELEMENT_CUSTOM_SYMBOLS, board == PreviewBoard.PKB_SYMBOLS)
            return EditorKeyboard(board, keyboard, builder, "", emptySet())
        }
    }
}

/**
 * The keyboard's own key painter, showing an [EditorKeyboard] in the editor. It types nothing: a
 * tap on an editable key reports that key's slot, and the selected slot gets an outline.
 */
@SuppressLint("ViewConstructor")
internal open class KeyEditorPreviewView(themed: Context) :
    KeyboardView(themed, null, R.attr.mainKeyboardViewStyle) {

    private var slotKeys: List<Key> = emptyList()
    private var pressedSlot = -1
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionRect = RectF()
    private val dp = resources.displayMetrics.density
    private val accessibility = SlotAccessibility()

    /** The slot drawn selected, or -1. */
    var selectedSlot: Int = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
                accessibility.invalidateRoot()
            }
        }

    /** Settings' primary colour, for the selection outline. */
    var selectionColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** What a screen reader says for a key with nothing on it. */
    var emptyKeyDescription: String = ""

    var onSlotTapped: ((Int) -> Unit)? = null

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        // As the keyboard shows its boards: on the physical page these are the letters of the
        // keys each symbol sits on.
        setHintsEnabled(true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Shows [board] as it stands after its last [EditorKeyboard.show]. */
    fun bind(board: EditorKeyboard) {
        // Always: replaced keys are new Key objects, and the per-key draw state is keyed by them.
        setKeyboard(board.keyboard)
        slotKeys = board.slotKeys
        accessibility.invalidateRoot()
    }

    /** The labels on the editable keys, in slot order. */
    fun slotLabels(): List<String> = slotKeys.map { it.label.orEmpty() }

    /** What a tap on slot [slot]'s key does. */
    fun tapSlot(slot: Int) {
        if (slot in slotKeys.indices) onSlotTapped?.invoke(slot)
    }

    /** The symbol pages are the main board, frets and all. */
    override fun drawsFrets(): Boolean = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val key = slotKeys.getOrNull(selectedSlot) ?: return
        val spec = KeyboardColorManager.styleSpec()
        val stroke = 2f * dp
        // Hug the cap the style draws: its inset from the key cell, and its corner.
        val insetH = maxOf(spec.keyInsetHDp * dp, stroke / 2f)
        val insetV = maxOf(spec.keyInsetVDp * dp, stroke / 2f)
        val left = key.drawX + paddingLeft
        val top = key.y + paddingTop
        selectionRect.set(
            left + insetH, top + insetV,
            left + key.drawWidth - insetH, top + key.height - insetV,
        )
        val radius = spec.keyRadiusDp * dp
        selectionPaint.style = Paint.Style.FILL
        selectionPaint.color = ColorUtils.setAlphaComponent(selectionColor, 0x33)
        canvas.drawRoundRect(selectionRect, radius, radius, selectionPaint)
        selectionPaint.style = Paint.Style.STROKE
        selectionPaint.strokeWidth = stroke
        selectionPaint.color = selectionColor
        canvas.drawRoundRect(selectionRect, radius, radius, selectionPaint)
    }

    private fun slotAt(x: Float, y: Float): Int {
        val px = x.toInt() - paddingLeft
        val py = y.toInt() - paddingTop
        val keyboard = keyboard ?: return -1
        // A key's cell includes the gaps around it, as the keyboard's own hit test does.
        return slotKeys.indexOfFirst { key ->
            px >= key.x && px < key.x + key.width + keyboard.mHorizontalGap &&
                py >= key.y && py < key.y + key.height + keyboard.mVerticalGap
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressedSlot = slotAt(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                val slot = slotAt(event.x, event.y)
                if (slot >= 0 && slot == pressedSlot) {
                    playSoundEffect(SoundEffectConstants.CLICK)
                    tapSlot(slot)
                }
                pressedSlot = -1
            }
            MotionEvent.ACTION_CANCEL -> pressedSlot = -1
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** One screen-reader node per editable key: its symbol, whether it is selected, and a tap. */
    private inner class SlotAccessibility : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            slotAt(x, y).let { if (it < 0) INVALID_ID else it }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds.addAll(slotKeys.indices)
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val key = slotKeys.getOrNull(virtualViewId)
            val label = key?.label.orEmpty()
            node.contentDescription =
                if (label.isEmpty() || label == EMPTY_SLOT) emptyKeyDescription else label
            node.className = Button::class.java.name
            node.isSelected = virtualViewId == selectedSlot
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            val left = (key?.x ?: 0) + paddingLeft
            val top = (key?.y ?: 0) + paddingTop
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect(left, top, left + (key?.width ?: 1), top + (key?.height ?: 1)))
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            tapSlot(virtualViewId)
            return true
        }
    }
}

/** The number pad: the slideboard's own ground colour behind the keys, and no frets. */
@SuppressLint("ViewConstructor")
internal class NumpadPreviewView(themed: Context) : KeyEditorPreviewView(themed) {
    override fun drawsFrets(): Boolean = false

    override fun keyboardBackgroundColor(): Int = KeyboardColorManager.slideGroundColor
}
