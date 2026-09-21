/**
 * Editor communication layer: wraps Android's {@code InputConnection} and tracks text
 * context and editor capabilities for the text editing pipeline.
 *
 * <p>This nested subpackage under {@code core.textinput} owns all direct interaction
 * with the host editor: reading text around the cursor, committing and correcting text,
 * managing batch edits, and analyzing editor field capabilities.
 *
 * <h3>Key classes</h3>
 * <ul>
 *   <li>{@link dev.bbkb.ime.core.textinput.connection.RichInputConnection} —
 *       primary editor interface: wraps {@link android.view.inputmethod.InputConnection}
 *       with batch-edit support, cursor tracking, text-before/after retrieval, composing
 *       region management, and word boundary detection.</li>
 *   <li>{@link dev.bbkb.ime.core.textinput.connection.TextContextTracker} —
 *       tracks the text context around the cursor for dynamic learning; uses
 *       {@link java.text.BreakIterator} for word boundary detection and implements
 *       {@code TextChangeListener} for live update notifications. It takes the
 *       {@code RichInputConnection} it listens to; it used to take an {@code InputLogic} and reach
 *       through {@code InputLogic.mRichInputConnection} for every read (§5.3).</li>
 *   <li>{@link dev.bbkb.ime.core.textinput.connection.CursorWordRange} —
 *       the word-boundary range {@code RichInputConnection} returns; moved down from the parent
 *       package, where it was the other half of the same back-edge.</li>
 *   <li>{@link dev.bbkb.ime.core.textinput.connection.EditorCapabilities} —
 *       analyzes {@link android.view.inputmethod.EditorInfo} at session start to determine
 *       which IME features are enabled for the current field (suggestions, gestures, voice,
 *       auto-correct). Renamed from {@code KeyCodeHandler}.</li>
 * </ul>
 *
 * <h3>Ownership</h3>
 * <p>This package is intended to be the tree's one enforceable ownership boundary: <em>the only
 * holder of an {@code InputConnection}</em>. As of §5.3 it imports exactly one thing from its
 * parent — {@code CapsModeUtils}, a stateless helper that landed in {@code core.textinput} in an
 * earlier step of the same plan. Nothing here reaches back into {@code InputLogic}.
 *
 * <h3>Threading</h3>
 * <p>All methods in this package must be called from the main thread. {@code RichInputConnection}
 * is not thread-safe. Calls from background threads (e.g., the NuanceSDK JNI callback) must
 * be posted to the main thread via {@code UIUpdateHandler}.
 */
package dev.bbkb.ime.core.textinput.connection;
