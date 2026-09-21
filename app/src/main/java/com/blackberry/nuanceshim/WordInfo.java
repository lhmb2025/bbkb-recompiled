package com.blackberry.nuanceshim;

/**
 * WordInfo - Data structure for word prediction results from NuanceSDK
 * Contains predicted word, spelling, selection index, spell correction status, and auto-accept flag.
 * Used to transfer prediction results from native code to Java layer.
 */


public class WordInfo {
    public String word = "";
    public String spell = "";
    public int selectionListIndex = 0;
    public boolean isSpellCorrected = false;
    public boolean shouldAutoAccept = false;

    /**
     * Audit EB-3: the {@link NuanceSDK#getSelectionListGeneration() selection-list generation}
     * this word's {@link #selectionListIndex} refers to. -1 means "not stamped", which the
     * learner treats as unusable for index-based selection.
     */
    public long selectionListGeneration = -1L;

    /* §8.7.11c: the native deposit sequence this gesture word was produced from. The commit
     * handler consumes exactly THIS sequence — never a boolean — so a commit landing after the
     * next swipe's deposit can no longer steal that deposit's pending state. -1 = not a gesture
     * word / legacy payload. */
    public int gestureSeq = -1;

    public String toString() {
        // Plain quote characters. jadx matched these literals to NuanceSDK
        // .ET9CPSYLLABLEDELIMITER, whose value happens to be '\'', which made the toString
        // read as if syllable-delimiter semantics were involved. They are not.
        return "WordInfo{word='" + this.word + '\'' + ", spell='" + this.spell + '\'' + ", selectionListIndex=" + this.selectionListIndex + ", isSpellCorrected=" + this.isSpellCorrected + ", shouldAutoAccept=" + this.shouldAutoAccept + ", selectionListGeneration=" + this.selectionListGeneration + '}';
    }
}
