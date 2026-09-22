package dev.bbkb.ime.core.textinput.connection;

import android.util.Log;

import dev.bbkb.ime.core.settings.util.SpacingAndPunctuation;

import java.text.BreakIterator;
import java.util.Locale;
import dev.bbkb.ime.BuildConfig;



public class TextContextTracker implements RichInputConnection.TextChangeListener {

    private static final String TAG = "TextContextTracker";

    /**
     * The connection this tracker listens to and reads back from.
     *
     * <p>It used to hold an {@code InputLogic} and reach through {@code InputLogic.mRichInputConnection}
     * on every read, which was the only thing making {@code connection} import its own parent
     * package (§5.3). Nothing else about {@code InputLogic} was ever used. Holding the connection
     * directly makes {@code connection} the one package in the tree that can be stated as an
     * ownership boundary — <em>the only holder of an {@code InputConnection}</em> — and it makes
     * this class constructible in a test with a mock connection and nothing else.
     *
     * <p>This is the same object {@code InputLogic} registers the tracker on via
     * {@code addTextChangeListener}, so the listener and the reader can no longer drift apart.
     */
    private final RichInputConnection connection;

    private Locale locale = null;

    private SpacingAndPunctuation spacingAndPunctuation = null;

    private BreakIterator sentenceIterator = null;

    private boolean enabled = false;

    private StringBuilder contextBuffer = new StringBuilder();

    private int contextStartPosition = 0;

    private int cursorPosition = -1;

    private boolean contextReachesEnd = false;

    @Override // dev.bbkb.ime.core.textinput.connection.RichInputConnection.TextChangeListener
    public void onComposingTextUpdated(CharSequence text) {
    }

    public TextContextTracker(RichInputConnection connection) {
        this.connection = connection;
    }

    @Override // dev.bbkb.ime.core.textinput.connection.RichInputConnection.TextChangeListener
    public void onTextChanged(int start, int length, CharSequence text, RichInputConnection.TextChangeType type) {
        int i3;
        if (isEnabled() && hasValidContext()) {
            if (text == null) {
                text = "";
            }
            int i4 = start + length;
            int i5 = this.contextStartPosition;
            if (i4 < i5 || (i4 == i5 && length > 0)) {
                this.contextStartPosition += text.length() - length;
                return;
            }
            if (start < this.contextStartPosition) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Error replacing text beyond current context. Invalidate context.");
                clearContext();
                return;
            }
            extendContextForward(start, length);
            if (hasValidContext()) {
                this.cursorPosition = start - this.contextStartPosition;
                if (length < 0 || (i3 = this.cursorPosition) < 0 || i3 + length > this.contextBuffer.length()) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Bad argument values. Invalidate context.");
                    clearContext();
                    return;
                }
                StringBuilder sb = this.contextBuffer;
                int i6 = this.cursorPosition;
                sb.replace(i6, length + i6, text.toString());
                this.cursorPosition += text.length();
            }
        }
    }

    private void extendContextForward(int i, int i2) {
        int i3 = this.contextStartPosition;
        int i4 = i - i3;
        if (i < i3 || i4 > this.contextBuffer.length() || i2 <= 0 || i4 + i2 <= this.contextBuffer.length() || this.contextReachesEnd) {
            return;
        }
        CharSequence charSequenceM5830b = this.connection.getTextAfterCursor(512, 0);
        if (charSequenceM5830b == null) {
            clearContext();
            return;
        }
        String string = charSequenceM5830b.toString();
        boolean z = string.length() < 512;
        int i5 = (this.cursorPosition + this.contextStartPosition) - i;
        if (i5 > 0) {
            string = this.contextBuffer.substring(i4, i5 + i4) + string;
        } else if (i5 < 0) {
            string = string.substring(-i5);
        }
        if (i2 > string.length()) {
            if (z) {
                StringBuilder sb = this.contextBuffer;
                sb.append(string.substring(sb.length() - i4));
                this.contextReachesEnd = true;
                return;
            }
            clearContext();
            return;
        }
        this.sentenceIterator.setText(string);
        int iFollowing = this.sentenceIterator.following(i2);
        if (iFollowing == string.length() || iFollowing == -1) {
            iFollowing = z ? string.length() : -1;
        }
        if (iFollowing != -1) {
            StringBuilder sb2 = this.contextBuffer;
            sb2.append(string.substring(sb2.length() - i4, iFollowing));
            this.contextReachesEnd = z;
            return;
        }
        clearContext();
    }

    @Override // dev.bbkb.ime.core.textinput.connection.RichInputConnection.TextChangeListener
    public void onTextDeleted(int position, int length) {
        if (isEnabled() && hasValidContext()) {
            int i3 = this.contextStartPosition;
            if (position < i3) {
                this.contextStartPosition = i3 - length;
                return;
            }
            if (position > i3 + this.contextBuffer.length()) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Error deleting text beyond current context. Invalidate context.");
                clearContext();
                return;
            }
            extendContextBackward(position, length);
            if (hasValidContext()) {
                this.cursorPosition = position - this.contextStartPosition;
                int i4 = this.cursorPosition;
                if (length <= i4) {
                    i4 = length;
                }
                StringBuilder sb = this.contextBuffer;
                int i5 = this.cursorPosition;
                sb.delete(i5 - i4, i5);
                this.cursorPosition -= i4;
            }
        }
    }

    private void extendContextBackward(int i, int i2) {
        int i3 = this.contextStartPosition;
        int i4 = i - i3;
        if (i4 < 0 || i2 <= i4 || i2 <= 0 || i3 == 0) {
            return;
        }
        CharSequence charSequenceM5815a = this.connection.getTextBeforeCursor(512, 0);
        if (charSequenceM5815a == null) {
            clearContext();
            return;
        }
        if (i4 != this.cursorPosition) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Cursor positions not in sync: cursorPosition = " + i4 + ", mCursorPosition = " + this.cursorPosition);
        }
        String string = charSequenceM5815a.toString();
        int length = i - string.length();
        if (i2 >= string.length()) {
            if (length == 0) {
                try {
                    this.contextBuffer.insert(0, string.substring(0, string.length() - i4));
                    this.contextStartPosition = 0;
                    this.cursorPosition = string.length();
                    return;
                } catch (StringIndexOutOfBoundsException e) {
                    if (BuildConfig.DEBUG) Log.e(TAG, e.getMessage());
                    clearContext();
                    return;
                }
            }
            clearContext();
            return;
        }
        this.sentenceIterator.setText(string);
        int iPreceding = this.sentenceIterator.preceding(string.length() - i2);
        if (iPreceding == 0 || iPreceding == -1) {
            iPreceding = length == 0 ? 0 : -1;
        }
        if (iPreceding != -1) {
            try {
                this.contextBuffer.insert(0, string.substring(iPreceding, string.length() - i4));
                this.contextStartPosition = length + iPreceding;
                this.cursorPosition = string.length() - iPreceding;
                return;
            } catch (StringIndexOutOfBoundsException e2) {
                if (BuildConfig.DEBUG) Log.e(TAG, e2.getMessage());
                clearContext();
                return;
            }
        }
        clearContext();
    }

    @Override // dev.bbkb.ime.core.textinput.connection.RichInputConnection.TextChangeListener
    public void onCursorPositionChanged(int position) {
        int i2;
        if (isEnabled() && hasValidContext() && (i2 = position - this.contextStartPosition) >= 0 && i2 <= this.contextBuffer.length()) {
            CharSequence charSequenceM5815a = this.connection.getTextBeforeCursor(i2, 0);
            CharSequence charSequenceM5830b = this.connection.getTextAfterCursor(this.contextBuffer.length() - i2, 0);
            if (charSequenceM5815a == null || charSequenceM5830b == null) {
                clearContext();
                return;
            }
            // TI-38: this used to be
            // `contextBuffer.toString().equals(before.toString() + after.toString())` - three
            // String materialisations plus a concatenation of the full context, on every selection
            // change while dynamic learning is on. Compare in place, and reject on length first.
            if (matchesContextBuffer(charSequenceM5815a, charSequenceM5830b)) {
                this.cursorPosition = i2;
                return;
            }
            clearContext();
        }
        rebuildContext(position);
    }

    public void invalidate() {
        clearContext();
    }

    private void rebuildContext(int i) {
        // Initialize variables properly to prevent type inference issues
        boolean needsPrecedingBoundary = false;
        boolean needsFollowingBoundary = false;
        
        if (isEnabled()) {
            if (i < 0) {
                clearContext();
                return;
            }
            
            // Get text before and after cursor
            CharSequence charSequenceM5815a = this.connection.getTextBeforeCursor(512, 0);
            CharSequence charSequenceM5830b = this.connection.getTextAfterCursor(512, 0);
            
            if (charSequenceM5815a == null || charSequenceM5830b == null) {
                clearContext();
                return;
            }
            
            // Handle edge cases for cursor position
            if (charSequenceM5815a.length() == 0 && i != 0) {
                i = 0;
            }
            
            if (i == 0 && charSequenceM5815a.length() > 0) {
                if (charSequenceM5815a.length() == 512) {
                    clearContext();
                    return;
                }
                i = charSequenceM5815a.length();
            }
            
            // Build the complete text context
            this.contextBuffer.setLength(0);
            StringBuilder sb = this.contextBuffer;
            sb.append(charSequenceM5815a);
            sb.append(charSequenceM5830b);
            
            // Initialize break iterator with complete text
            this.sentenceIterator.setText(this.contextBuffer.toString());
            this.contextStartPosition = i - charSequenceM5815a.length();
            this.cursorPosition = i - this.contextStartPosition;
            this.contextReachesEnd = charSequenceM5830b.length() < 512;
            
            // Check if cursor is at a word boundary
            if (this.sentenceIterator.isBoundary(this.cursorPosition)) {
                int cursorPos = this.cursorPosition;
                
                if (cursorPos == 0) {
                    // At start of text - check character after cursor
                    if (charSequenceM5830b.length() > 0) {
                        needsFollowingBoundary = this.spacingAndPunctuation.isLetterOrConnector(Character.codePointAt(charSequenceM5830b, 0));
                        needsPrecedingBoundary = false;
                    } else {
                        needsPrecedingBoundary = false;
                        needsFollowingBoundary = false;
                    }
                } else if (cursorPos == this.contextBuffer.length() && charSequenceM5815a.length() > 0) {
                    // At end of text - check character before cursor
                    needsPrecedingBoundary = this.spacingAndPunctuation.isLetterOrConnector(Character.codePointAt(charSequenceM5815a, charSequenceM5815a.length() - 1));
                    needsFollowingBoundary = false;
                }
            } else {
                // Not at boundary - need to find word boundaries
                needsPrecedingBoundary = true;
            }
            
            // Find preceding word boundary
            int iPreceding = needsPrecedingBoundary ? this.sentenceIterator.preceding(this.cursorPosition) : this.cursorPosition;
            if (iPreceding == 0 || iPreceding == -1) {
                iPreceding = this.contextStartPosition == 0 ? 0 : -1;
            }
            
            // Find following word boundary
            int iFollowing = needsFollowingBoundary ? this.sentenceIterator.following(this.cursorPosition) : this.cursorPosition;
            if (iFollowing == this.contextBuffer.length() || iFollowing == -1) {
                iFollowing = this.contextReachesEnd ? this.contextBuffer.length() : -1;
            }
            
            // Validate boundaries
            if (iPreceding == -1 || iFollowing == -1) {
                clearContext();
                return;
            }
            
            // Extract the word and update context
            this.cursorPosition -= iPreceding;
            this.contextStartPosition += iPreceding;
            String strSubstring = this.contextBuffer.substring(iPreceding, iFollowing);
            this.contextBuffer.setLength(0);
            this.contextBuffer.append(strSubstring);
        }
    }

    public void setEnabled(boolean z) {
        this.enabled = z;
    }

    /**
     * TI-24: this also requires the {@link #updateLocale} state to exist.
     * {@code InputLogic.setDynamicLearningEnabled(boolean)} calls {@link #setEnabled(boolean)}
     * WITHOUT {@code updateLocale}, and it is invoked from {@code UIUpdateHandler} with the user's
     * live preference. If that lands before {@code updateDynamicLearningState()} has ever run its
     * {@code updateLocale} branch, {@code sentenceIterator} is still null and {@code rebuildContext}
     * - reached from {@code onCursorPositionChanged}, which fires on every field focus - NPEs.
     */
    public boolean isEnabled() {
        return this.enabled && this.sentenceIterator != null && this.spacingAndPunctuation != null;
    }

    public void updateLocale(Locale locale, SpacingAndPunctuation c0806f) {
        if (c0806f != null) {
            this.spacingAndPunctuation = c0806f;
        }
        if (locale != null) {
            Locale locale2 = this.locale;
            if (locale2 == null || !locale.equals(locale2)) {
                this.locale = locale;
                this.sentenceIterator = BreakIterator.getSentenceInstance(this.locale);
            }
        }
    }

    private void clearContext() {
        this.contextBuffer.setLength(0);
        this.cursorPosition = -1;
    }

    private boolean hasValidContext() {
        return this.cursorPosition != -1;
    }

    /**
     * TI-38: {@code true} when {@code contextBuffer} equals {@code before} followed by
     * {@code after}, compared character by character with no intermediate Strings.
     */
    private boolean matchesContextBuffer(CharSequence before, CharSequence after) {
        int bufferLength = this.contextBuffer.length();
        if (bufferLength != before.length() + after.length()) {
            return false;
        }
        for (int k = 0; k < before.length(); k++) {
            if (this.contextBuffer.charAt(k) != before.charAt(k)) {
                return false;
            }
        }
        int offset = before.length();
        for (int k = 0; k < after.length(); k++) {
            if (this.contextBuffer.charAt(offset + k) != after.charAt(k)) {
                return false;
            }
        }
        return true;
    }
}
