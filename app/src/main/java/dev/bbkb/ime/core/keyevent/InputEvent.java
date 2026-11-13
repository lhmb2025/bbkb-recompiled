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
     * @param eventType event type (EVENT_*)
     * @param codePoint code point, or -1 for none
     * @param keyCode key code
     * @param x x coordinate or a COORD_* marker
     * @param y y coordinate or a COORD_* marker
     * @param flags FLAG_* bits
     * @param nextEvent next event in the chain, or null
     * @param timestamp timestamp, or -1
     * @param isFromSwitchedKeyboard true if from a switched keyboard
     */
    private InputEvent(int eventType, CharSequence charSequence, int codePoint, int keyCode, int x, int y, SuggestedWords.SuggestedWordInfo suggestedWordInfo, int flags, InputEvent nextEvent, long timestamp, boolean isFromSwitchedKeyboard) {
        this.mEventType = eventType;
        this.mText = charSequence;
        this.mCodePoint = codePoint;
        this.mKeyCode = keyCode;
        this.mX = x;
        this.mY = y;
        this.mTimestamp = timestamp;
        this.mSuggestedWordInfo = suggestedWordInfo;
        this.mFlags = flags;
        this.mNextEvent = nextEvent;
        this.mIsFromSwitchedKeyboard = isFromSwitchedKeyboard;
        if (EVENT_SUGGESTION_PICKED == this.mEventType) {
            if (this.mSuggestedWordInfo == null) {
                throw new RuntimeException("Wrong event: SUGGESTION_PICKED event must have a non-null SuggestedWordInfo");
            }
        } else if (this.mSuggestedWordInfo != null) {
            throw new RuntimeException("Wrong event: only SUGGESTION_PICKED events may have a non-null SuggestedWordInfo");
        }
    }

    /**
     * @param codePoint code point
     * @param keyCode key code
     * @param x x coordinate
     * @param y y coordinate
     * @param timestamp timestamp
     * @param isKeyRepeat true for a key repeat
     */
    public static InputEvent createKeyPress(int codePoint, int keyCode, int x, int y, long timestamp, boolean isKeyRepeat) {
        return new InputEvent(EVENT_KEY_PRESS, null, codePoint, keyCode, x, y, null, isKeyRepeat ? FLAG_KEY_REPEAT : 0, null, timestamp, false);
    }

    /**
     * @param codePoint code point
     * @param keyCode key code
     * @param nextEvent next event, or null
     * @param isKeyRepeat true for a key repeat
     * @param timestamp timestamp
     * @param isFromSwitchedKeyboard true if from a switched keyboard
     */
    public static InputEvent createHardwareKeyPressEx(int codePoint, int keyCode, InputEvent nextEvent, boolean isKeyRepeat, long timestamp, boolean isFromSwitchedKeyboard) {
        return new InputEvent(EVENT_KEY_PRESS, null, codePoint, keyCode, COORD_NO_COORDINATES, COORD_NO_COORDINATES, null, isKeyRepeat ? FLAG_KEY_REPEAT : 0, nextEvent, timestamp, isFromSwitchedKeyboard);
    }

    /**
     * @param codePoint code point
     * @param keyCode key code
     * @param nextEvent next event, or null
     * @param isKeyRepeat true for a key repeat
     * @param timestamp timestamp
     * @param isFromSwitchedKeyboard true if from a switched keyboard
     */
    public static InputEvent createTextEvent(int codePoint, CharSequence charSequence, int keyCode, InputEvent nextEvent, boolean isKeyRepeat, long timestamp, boolean isFromSwitchedKeyboard) {
        return new InputEvent(EVENT_TEXT_INPUT, charSequence, codePoint, keyCode, COORD_NO_COORDINATES, COORD_NO_COORDINATES, null, isKeyRepeat ? FLAG_KEY_REPEAT : 0, nextEvent, timestamp, isFromSwitchedKeyboard);
    }

    /**
     * @param codePoint code point
     * @param keyCode key code
     * @param nextEvent next event, or null
     * @param isKeyRepeat true for a key repeat
     * @param timestamp timestamp
     */
    public static InputEvent createHardwareKeyPress(int codePoint, int keyCode, InputEvent nextEvent, boolean isKeyRepeat, long timestamp) {
        return createHardwareKeyPressEx(codePoint, keyCode, nextEvent, isKeyRepeat, timestamp, false);
    }

    /**
     * @param codePoint code point
     * @param keyCode key code
     * @param nextEvent next event, or null
     * @param isKeyRepeat true for a key repeat
     * @param isShiftLocked true if Shift is locked
     * @param timestamp timestamp
     */
    public static InputEvent createModifierKeyEvent(int codePoint, int keyCode, InputEvent nextEvent, boolean isKeyRepeat, boolean isShiftLocked, long timestamp) {
        return new InputEvent(EVENT_KEY_PRESS, null, codePoint, keyCode, COORD_NO_COORDINATES, COORD_NO_COORDINATES, null, (isKeyRepeat ? FLAG_KEY_REPEAT : 0) | FLAG_MODIFIER_KEY | (isShiftLocked ? FLAG_SHIFT_LOCKED : 0), nextEvent, timestamp, false);
    }

    /**
     * @param codePoint code point
     * @param keyCode key code
     * @param nextEvent next event, or null
     */
    public static InputEvent createFunctionalKeyEvent(int codePoint, int keyCode, InputEvent nextEvent) {
        return new InputEvent(EVENT_KEY_PRESS, null, codePoint, keyCode, COORD_NO_COORDINATES, COORD_NO_COORDINATES, null, FLAG_FUNCTIONAL_KEY, nextEvent, -1L, false);
    }

    /**
     * @param codePoint code point
     * @param keyCode key code
     * @param timestamp timestamp
     * @param nextEvent next event, or null
     */
    public static InputEvent createHardwareKeyEvent(int codePoint, int keyCode, long timestamp, InputEvent nextEvent) {
        return new InputEvent(EVENT_KEY_PRESS, null, codePoint, keyCode, COORD_UNKNOWN, COORD_UNKNOWN, null, 0, nextEvent, timestamp, false);
    }

    /**
     * @param codePoint code point
     * @param keyCode key code
     * @param x x coordinate
     * @param y y coordinate
     * @param timestamp timestamp
     * @param isKeyRepeat true for a key repeat
     * @param isShiftLocked true if Shift is locked
     */
    public static InputEvent createModifierKeyPress(int codePoint, int keyCode, int x, int y, long timestamp, boolean isKeyRepeat, boolean isShiftLocked) {
        return new InputEvent(EVENT_KEY_PRESS, null, codePoint, keyCode, x, y, null, (isKeyRepeat ? FLAG_KEY_REPEAT : 0) | FLAG_MODIFIER_KEY | (isShiftLocked ? FLAG_SHIFT_LOCKED : 0), null, timestamp, false);
    }

    /**
     * @param codePoint code point
     * @param x x coordinate
     * @param y y coordinate
     * @param isFromSwitchedKeyboard true if from a switched keyboard
     */
    public static InputEvent createTouchEvent(int codePoint, int x, int y, boolean isFromSwitchedKeyboard) {
        return new InputEvent(EVENT_KEY_PRESS, null, codePoint, 0, x, y, null, 0, null, -1L, isFromSwitchedKeyboard);
    }

    public static InputEvent createForSuggestion(SuggestedWords.SuggestedWordInfo suggestedWordInfo) {
        return new InputEvent(EVENT_SUGGESTION_PICKED, suggestedWordInfo.word, -1, 0, COORD_VIRTUAL, COORD_VIRTUAL, suggestedWordInfo, 0, null, -1L, false);
    }

    /** @param keyCode key code */
    public static InputEvent createTextInputEvent(CharSequence charSequence, int keyCode) {
        return new InputEvent(EVENT_TEXT_INPUT, charSequence, -1, keyCode, COORD_UNKNOWN, COORD_UNKNOWN, null, 0, null, -1L, false);
    }

    public static InputEvent createForSuggestionWithFirstChar(SuggestedWords.SuggestedWordInfo suggestedWordInfo) {
        return new InputEvent(EVENT_SUGGESTION_PICKED, suggestedWordInfo.word, suggestedWordInfo.word.charAt(0), 0, COORD_VIRTUAL, COORD_VIRTUAL, suggestedWordInfo, 0, null, -1L, false);
    }

    /** @param source the event to copy */
    public static InputEvent createGestureEndCopy(InputEvent source) {
        return new InputEvent(source.mEventType, source.mText, source.mCodePoint, source.mKeyCode, source.mX, source.mY, source.mSuggestedWordInfo, source.mFlags | FLAG_GESTURE_END, source.mNextEvent, source.mTimestamp, false);
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

    /** @param source the event to copy */
    public static InputEvent createFromSwitchedKeyboard(InputEvent source) {
        return new InputEvent(source.mEventType, source.mText, source.mCodePoint, source.mKeyCode, source.mX, source.mY, source.mSuggestedWordInfo, source.mFlags, source.mNextEvent, source.mTimestamp, true);
    }
}
