package dev.bbkb.ime.keyboard.internal;

/**
 * Per-key enum that specifies which corner of a key should display a small hint label or icon,
 * or {@link #HIDDEN} if no hint should be shown at all.
 *
 * <p><b>Name note:</b> Despite being called "KeyboardMode", this has nothing to do with the
 * keyboard's overall mode (alphabet / symbol / emoji). It is strictly a rendering hint that
 * controls the corner position of a small overlay drawn on top of an individual key.</p>
 *
 * <hr>
 * <h2>What the user sees</h2>
 * Keys on the keyboard can display a small secondary label or icon in one of their four corners.
 * This overlay serves two distinct visual purposes:
 * <ol>
 *   <li><b>Long-press hint</b> – a small character or icon hinting that a long-press on this key
 *       will open a popup with additional choices (e.g. accent variants). The corner is declared
 *       per-key in the keyboard XML layout and stored in {@code Key.LongPressKeyData}.</li>
 *   <li><b>Secondary icon</b> – a live icon dynamically injected at runtime into the top-right
 *       corner of certain keys (e.g. the UIM toggle icon or the language-switch icon on the
 *       spacebar). See {@code KeyboardSwitcher}.</li>
 * </ol>
 *
 * <hr>
 * <h2>Data flow / logic chain</h2>
 *
 * <h3>1. Declared in XML (static, per-key)</h3>
 * Each {@code <Key>} element in a keyboard layout XML resource may carry a
 * {@code Keyboard_Key} styleable attribute (index 42) whose integer value maps to one of the
 * constants below. When absent the default is {@link #HIDDEN}.
 * <pre>
 *   KeyboardXMLParser.parseKey()
 *     └─ KeyboardMode.fromValue(typedArray.getInt(42, HIDDEN.intValue()))
 *          └─ passed as constructor arg to new Key(...)
 *               └─ Key.LongPressKeyData.longPressKeyboardMode  (field)
 * </pre>
 *
 * <h3>2. Read during rendering (KeyboardView)</h3>
 * {@code KeyboardView} calls two drawing helpers for each key:
 * <pre>
 *   KeyboardView.drawPopupHint(key)
 *     └─ drawHintLabelOrIcon(key, key.getLongPressLabel(),
 *                            key.getLongPressIcon(...),
 *                            key.getLongPressKeyHintPosition(),          // ← returns longPressKeyboardMode
 *                            canvas, paint, formatter)
 *          └─ if mode == HIDDEN  → nothing drawn
 *             else               → compute (hintX, hintY) from corner enum,
 *                                  draw label text or icon drawable at that position
 *
 *   KeyboardView.drawPopupHintLetter(key)           // global "popup available" indicator
 *     └─ drawHintLabelOrIcon(key, popupHintLetter,
 *                            null,
 *                            KeyboardMode.BOTTOM_RIGHT_CORNER,  // always bottom-right
 *                            canvas, paint, formatter)
 * </pre>
 * Corner-to-pixel mapping inside {@code drawHintLabelOrIcon}:
 * <ul>
 *   <li>{@link #TOP_LEFT_CORNER}    / {@link #BOTTOM_LEFT_CORNER}   → hintX = hintLetterPadding (left-aligned)</li>
 *   <li>{@link #TOP_RIGHT_CORNER}   / {@link #BOTTOM_RIGHT_CORNER}  → hintX = keyWidth − padding − textWidth (right-aligned)</li>
 *   <li>{@link #TOP_LEFT_CORNER}    / {@link #TOP_RIGHT_CORNER}     → hintY = popupHintLetterPadding (near top)</li>
 *   <li>{@link #BOTTOM_LEFT_CORNER} / {@link #BOTTOM_RIGHT_CORNER}  → hintY = keyHeight − padding (near bottom)</li>
 * </ul>
 *
 * <h3>3. Dynamic injection at runtime (KeyboardSwitcher)</h3>
 * {@code KeyboardSwitcher.shouldShowSecondaryIcon()} checks live settings to decide whether
 * certain special keys (keycode {@code -23} = UIM toggle, keycode {@code -10} = language switch)
 * need a secondary icon overlay. When the answer is yes, a replacement {@code Key} object is
 * created with {@link #TOP_RIGHT_CORNER} and swapped into the keyboard layout, so the icon
 * appears in the top-right corner of that key.
 *
 * <hr>
 * <h2>Call-site summary</h2>
 * <ul>
 *   <li>{@code KeyboardXMLParser.parseKey()}       – reads value from XML, constructs Key</li>
 *   <li>{@code Key.LongPressKeyData}              – stores the value as {@code longPressKeyboardMode}</li>
 *   <li>{@code Key.getLongPressKeyHintPosition()}                      – accessor that returns the stored value (or HIDDEN)</li>
 *   <li>{@code KeyboardView.drawPopupHint()}       – uses key's stored value to position long-press hint</li>
 *   <li>{@code KeyboardView.drawPopupHintLetter()} – always uses BOTTOM_RIGHT_CORNER for popup indicator</li>
 *   <li>{@code KeyboardSwitcher} (line 255)        – injects TOP_RIGHT_CORNER for UIM / language icons</li>
 *   <li>{@code KeyboardBuilder} (multiple)         – passes HIDDEN when constructing programmatic keys</li>
 *   <li>{@code MoreKeySpec.buildKey()}       – passes HIDDEN for MoreKeys keyboard keys</li>
 *   <li>{@code Key.SpacerKey} / {@code Key.EmptyKey} constructors – always HIDDEN</li>
 * </ul>
 */
public enum KeyHintPosition {
    HIDDEN("hidden", 0),
    TOP_RIGHT_CORNER("topRightCorner", 1),
    TOP_LEFT_CORNER("topLeftCorner", 2),
    BOTTOM_RIGHT_CORNER("bottomRightCorner", 3),
    BOTTOM_LEFT_CORNER("bottomLeftCorner", 4);


    private final String label;

    private final int value;

    KeyHintPosition(String str, int i) {
        this.label = str;
        this.value = i;
    }

    public int getValue() {
        return this.value;
    }

    @Override // java.lang.Enum
    public String toString() {
        return this.label;
    }

    public static KeyHintPosition fromValue(int i) {
        switch (i) {
            case 0:
                return HIDDEN;
            case 1:
                return TOP_RIGHT_CORNER;
            case 2:
                return TOP_LEFT_CORNER;
            case 3:
                return BOTTOM_RIGHT_CORNER;
            case 4:
                return BOTTOM_LEFT_CORNER;
            default:
                throw new IllegalArgumentException("Invalid display style value: " + i);
        }
    }
}
