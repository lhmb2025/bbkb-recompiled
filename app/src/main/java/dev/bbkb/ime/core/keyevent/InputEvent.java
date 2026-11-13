package dev.bbkb.ime.core.keyevent;

import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.BuildConfig;



public class InputEvent {

    private static final int EVENT_EMPTY           = 0;
    private static final int EVENT_KEY_PRESS       = 1;
    private static final int EVENT_UNKNOWN_2       = 2;
    private static final int EVENT_UNKNOWN_3       = 3;
    private static final int EVENT_GESTURE         = 4;
    private static final int EVENT_SUGGESTION_PICKED = 5;
    private static final int EVENT_TEXT_INPUT      = 6;
    private static final int FLAG_FUNCTIONAL_KEY   = 0x01;
    private static final int FLAG_KEY_REPEAT       = 0x02;
    private static final int FLAG_GESTURE_END      = 0x04;
    private static final int FLAG_MODIFIER_KEY     = 0x10;
    private static final int FLAG_SHIFT_LOCKED     = 0x20;
    private static final int COORD_UNKNOWN         = -1;
    private static final int COORD_VIRTUAL         = -2;
    private static final int COORD_NO_COORDINATES  = -4;
    /**
     * Marker in {@link #mX}/{@link #mY} on the backspace synthesized by swipe-to-delete: the revert path
     * treats it as always revert-eligible, so the swipe undoes an auto-correction whatever precedes the cursor.
     */
    public static final int COORD_SWIPE_DELETE_REVERT = -5;

    public final int mCodePoint;

    public final CharSequence mText;

    public final int mKeyCode;

    public final int mX;

    public final int mY;

    public final SuggestedWords.SuggestedWordInfo mSuggestedWordInfo;

    public final InputEvent mNextEvent;

    public final boolean mIsFromSwitchedKeyboard;

    private final int mEventType;

    private final long mTimestamp;

    private final int mFlags;

    /**
     * @param i event type (EVENT_*)
     * @param i2 code point, or -1 for none
     * @param i3 key code
     * @param i4 x coordinate or a COORD_* marker
     * @param i5 y coordinate or a COORD_* marker
     * @param i6 FLAG_* bits
     * @param c0914a next event in the chain, or null
     * @param j timestamp, or -1
     * @param z true if from a switched keyboard
     */
    private InputEvent(int i, CharSequence charSequence, int i2, int i3, int i4, int i5, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, int i6, InputEvent c0914a, long j, boolean z) {
        this.mEventType = i;
        this.mText = charSequence;
        this.mCodePoint = i2;
        this.mKeyCode = i3;
        this.mX = i4;
        this.mY = i5;
        this.mTimestamp = j;
        this.mSuggestedWordInfo = suggestedWordInfoVar;
        this.mFlags = i6;
        this.mNextEvent = c0914a;
        this.mIsFromSwitchedKeyboard = z;
        if (EVENT_SUGGESTION_PICKED == this.mEventType) {
            if (this.mSuggestedWordInfo == null) {
                throw new RuntimeException("Wrong event: SUGGESTION_PICKED event must have a non-null SuggestedWordInfo");
            }
        } else if (this.mSuggestedWordInfo != null) {
            throw new RuntimeException("Wrong event: only SUGGESTION_PICKED events may have a non-null SuggestedWordInfo");
        }
    }

    /**
     * @param i code point
     * @param i2 key code
     * @param i3 x coordinate
     * @param i4 y coordinate
     * @param j timestamp
     * @param z true for a key repeat
     */
    public static InputEvent createKeyPress(int i, int i2, int i3, int i4, long j, boolean z) {
        return new InputEvent(EVENT_KEY_PRESS, null, i, i2, i3, i4, null, z ? FLAG_KEY_REPEAT : 0, null, j, false);
    }

    /**
     * @param i code point
     * @param i2 key code
     * @param c0914a next event, or null
     * @param z true for a key repeat
     * @param j timestamp
     * @param z2 true if from a switched keyboard
     */
    public static InputEvent createHardwareKeyPressEx(int i, int i2, InputEvent c0914a, boolean z, long j, boolean z2) {
        return new InputEvent(EVENT_KEY_PRESS, null, i, i2, COORD_NO_COORDINATES, COORD_NO_COORDINATES, null, z ? FLAG_KEY_REPEAT : 0, c0914a, j, z2);
    }

    /**
     * @param i code point
     * @param i2 key code
     * @param c0914a next event, or null
     * @param z true for a key repeat
     * @param j timestamp
     * @param z2 true if from a switched keyboard
     */
    public static InputEvent createTextEvent(int i, CharSequence charSequence, int i2, InputEvent c0914a, boolean z, long j, boolean z2) {
        return new InputEvent(EVENT_TEXT_INPUT, charSequence, i, i2, COORD_NO_COORDINATES, COORD_NO_COORDINATES, null, z ? FLAG_KEY_REPEAT : 0, c0914a, j, z2);
    }

    /**
     * @param i code point
     * @param i2 key code
     * @param c0914a next event, or null
     * @param z true for a key repeat
     * @param j timestamp
     */
    public static InputEvent createHardwareKeyPress(int i, int i2, InputEvent c0914a, boolean z, long j) {
        return createHardwareKeyPressEx(i, i2, c0914a, z, j, false);
    }

    /**
     * @param i code point
     * @param i2 key code
     * @param c0914a next event, or null
     * @param z true for a key repeat
     * @param z2 true if Shift is locked
     * @param j timestamp
     */
    public static InputEvent createModifierKeyEvent(int i, int i2, InputEvent c0914a, boolean z, boolean z2, long j) {
        return new InputEvent(EVENT_KEY_PRESS, null, i, i2, COORD_NO_COORDINATES, COORD_NO_COORDINATES, null, (z ? FLAG_KEY_REPEAT : 0) | FLAG_MODIFIER_KEY | (z2 ? FLAG_SHIFT_LOCKED : 0), c0914a, j, false);
    }

    /**
     * @param i code point
     * @param i2 key code
     * @param c0914a next event, or null
     */
    public static InputEvent createFunctionalKeyEvent(int i, int i2, InputEvent c0914a) {
        return new InputEvent(EVENT_KEY_PRESS, null, i, i2, COORD_NO_COORDINATES, COORD_NO_COORDINATES, null, FLAG_FUNCTIONAL_KEY, c0914a, -1L, false);
    }

    /**
     * @param i code point
     * @param i2 key code
     * @param j timestamp
     * @param c0914a next event, or null
     */
    public static InputEvent createHardwareKeyEvent(int i, int i2, long j, InputEvent c0914a) {
        return new InputEvent(EVENT_KEY_PRESS, null, i, i2, COORD_UNKNOWN, COORD_UNKNOWN, null, 0, c0914a, j, false);
    }

    /**
     * @param i code point
     * @param i2 key code
     * @param i3 x coordinate
     * @param i4 y coordinate
     * @param j timestamp
     * @param z true for a key repeat
     * @param z2 true if Shift is locked
     */
    public static InputEvent createModifierKeyPress(int i, int i2, int i3, int i4, long j, boolean z, boolean z2) {
        return new InputEvent(EVENT_KEY_PRESS, null, i, i2, i3, i4, null, (z ? FLAG_KEY_REPEAT : 0) | FLAG_MODIFIER_KEY | (z2 ? FLAG_SHIFT_LOCKED : 0), null, j, false);
    }

    /**
     * @param i code point
     * @param i2 x coordinate
     * @param i3 y coordinate
     * @param z true if from a switched keyboard
     */
    public static InputEvent createTouchEvent(int i, int i2, int i3, boolean z) {
        return new InputEvent(EVENT_KEY_PRESS, null, i, 0, i2, i3, null, 0, null, -1L, z);
    }

    public static InputEvent createForSuggestion(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        return new InputEvent(EVENT_SUGGESTION_PICKED, suggestedWordInfoVar.word, -1, 0, COORD_VIRTUAL, COORD_VIRTUAL, suggestedWordInfoVar, 0, null, -1L, false);
    }

    /** @param i key code */
    public static InputEvent createTextInputEvent(CharSequence charSequence, int i) {
        return new InputEvent(EVENT_TEXT_INPUT, charSequence, -1, i, COORD_UNKNOWN, COORD_UNKNOWN, null, 0, null, -1L, false);
    }

    public static InputEvent createForSuggestionWithFirstChar(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        return new InputEvent(EVENT_SUGGESTION_PICKED, suggestedWordInfoVar.word, suggestedWordInfoVar.word.charAt(0), 0, COORD_VIRTUAL, COORD_VIRTUAL, suggestedWordInfoVar, 0, null, -1L, false);
    }

    /** @param c0914a the event to copy */
    public static InputEvent createGestureEndCopy(InputEvent c0914a) {
        return new InputEvent(c0914a.mEventType, c0914a.mText, c0914a.mCodePoint, c0914a.mKeyCode, c0914a.mX, c0914a.mY, c0914a.mSuggestedWordInfo, c0914a.mFlags | FLAG_GESTURE_END, c0914a.mNextEvent, c0914a.mTimestamp, false);
    }

    public static InputEvent createEmptyEvent() {
        return new InputEvent(EVENT_EMPTY, null, -1, 0, COORD_UNKNOWN, COORD_UNKNOWN, null, 0, null, -1L, false);
    }

    public long getTimestamp() {
        return this.mTimestamp;
    }

    /** Copy of the {@code head} chain with {@code tail} appended after its last node. */
    public static InputEvent appendEventToChain(InputEvent head, InputEvent tail) {
        InputEvent rebuiltNext = (head.mNextEvent == null) ? tail : appendEventToChain(head.mNextEvent, tail);
        return withNext(head, rebuiltNext);
    }

    /** {@code event} with its {@code mNextEvent} replaced; every other field is carried over. */
    private static InputEvent withNext(InputEvent event, InputEvent next) {
        return new InputEvent(event.mEventType, event.mText, event.mCodePoint, event.mKeyCode,
                event.mX, event.mY, event.mSuggestedWordInfo, event.mFlags, next, event.mTimestamp,
                event.mIsFromSwitchedKeyboard);
    }

    public boolean isFunctionalKeyEvent() {
        return -1 == this.mCodePoint;
    }

    public boolean isKeyRepeat() {
        return (this.mFlags & FLAG_KEY_REPEAT) != 0;
    }

    public boolean isGestureEnd() {
        return (this.mFlags & FLAG_GESTURE_END) != 0;
    }

    public boolean isGestureEvent() {
        return EVENT_GESTURE == this.mEventType;
    }

    public boolean isKeyPressEvent() {
        return EVENT_KEY_PRESS == this.mEventType;
    }

    public boolean isModifierKey() {
        return (this.mFlags & FLAG_MODIFIER_KEY) != 0;
    }

    public boolean isShiftLocked() {
        return (this.mFlags & FLAG_SHIFT_LOCKED) != 0;
    }

    public boolean isSuggestionPicked() {
        return EVENT_SUGGESTION_PICKED == this.mEventType;
    }

    public boolean hasData() {
        return this.mEventType != EVENT_EMPTY;
    }

    /**
     * Whether this is the user typing: a character (space and Enter included), text, a swipe, or Delete
     * (-5). Not modifiers, other functional keys or a picked suggestion. Brings the strip back over a menu.
     */
    public boolean editsText() {
        if (!hasData() || isModifierKey() || isSuggestionPicked()) {
            return false;
        }
        if (this.mText != null || isGestureEvent() || this.mCodePoint > 0) {
            return true;
        }
        return this.mKeyCode == -5;
    }

    public boolean isFromSwitchedKeyboard() {
        return this.mIsFromSwitchedKeyboard;
    }

    public boolean hasNoCoordinates() {
        return this.mX == COORD_NO_COORDINATES;
    }

    public CharSequence getOutputText() {
        if (isGestureEnd()) {
            return "";
        }
        switch (this.mEventType) {
            case EVENT_EMPTY:
            case EVENT_UNKNOWN_2:
            case EVENT_UNKNOWN_3:
                return "";
            case EVENT_KEY_PRESS:
                if (this.mCodePoint < 0 || !Character.isValidCodePoint(this.mCodePoint)) {
                    if (BuildConfig.DEBUG) {
                    android.util.Log.w("InputEvent", "Invalid Unicode code point: 0x" + 
                        Integer.toHexString(this.mCodePoint) + ", returning empty string");
                    }
                    return "";
                }
                // Avoids the char[] for BMP code points; this runs for every typed character.
                return Character.isBmpCodePoint(this.mCodePoint)
                        ? String.valueOf((char) this.mCodePoint)
                        : new String(Character.toChars(this.mCodePoint));
            case EVENT_GESTURE:
            case EVENT_SUGGESTION_PICKED:
            case EVENT_TEXT_INPUT:
                return this.mText;
            default:
                throw new RuntimeException("Unknown event type: " + this.mEventType);
        }
    }

    /** @param c0914a the event to copy */
    public static InputEvent createFromSwitchedKeyboard(InputEvent c0914a) {
        return new InputEvent(c0914a.mEventType, c0914a.mText, c0914a.mCodePoint, c0914a.mKeyCode, c0914a.mX, c0914a.mY, c0914a.mSuggestedWordInfo, c0914a.mFlags, c0914a.mNextEvent, c0914a.mTimestamp, true);
    }
}
