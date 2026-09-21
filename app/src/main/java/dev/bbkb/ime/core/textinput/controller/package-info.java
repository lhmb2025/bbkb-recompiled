/**
 * Input controllers extracted from {@code InputLogic} that need none of its internals.
 *
 * <p>The dividing line is access, and it is enforced by the compiler rather than by convention:
 * a controller belongs here if it collaborates with the pipeline only through its own public
 * methods and small callback interfaces. {@code CursorController}, {@code PunctuationController}
 * and {@code SmartPunctuationAnalyzer} qualify.
 *
 * <p>The four sibling controllers in the parent package — {@code CommitController},
 * {@code BackspaceController}, {@code RecorrectionController} and {@code SuggestionCoordinator} —
 * do not, and the naming inconsistency is load-bearing rather than accidental. Between them they
 * touch <strong>24 package-private members of {@code InputLogic}</strong>: thirteen mutable fields
 * ({@code mLastCommittedText}, {@code mCommitType}, {@code mIsAutoCorrectActive},
 * {@code mJustCommitted}, {@code mShouldAppendSpace}, {@code mHasModifiedEvent},
 * {@code mLastCommitFromVoice}, {@code mSuggestionRequestQueue}, {@code mIme},
 * {@code mDictionaryLoader}, {@code mSmartPunctuationAnalyzer}, {@code mMoreKeysController},
 * {@code mSuggestionStripListener}) and eleven methods ({@code commitWord},
 * {@code revertAutoCorrection}, {@code resetComposingAndSelect}, {@code setComposingTextInternal},
 * {@code setComposingTextWithHighlight}, {@code getComposingTextWithIndicator},
 * {@code updateNuanceContext}, {@code flushPendingSuggestions}, {@code getShiftState},
 * {@code isWordSeparator}, {@code isCjkLocale}).
 *
 * <p>Moving them into this package was proposed (§5.3) and attempted; it requires making all 24
 * {@code public}, which publishes the mutable internals of the text pipeline's central class —
 * including one of the documented copies of the current word — to the entire application. That is
 * a worse boundary than the one the split names, so the four stay put. Giving them narrow
 * interfaces onto {@code InputLogic} would let them move and would be an improvement; that is a
 * design change, not a package move.
 */
package dev.bbkb.ime.core.textinput.controller;
