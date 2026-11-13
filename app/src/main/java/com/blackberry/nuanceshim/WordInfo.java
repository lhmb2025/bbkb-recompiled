package com.blackberry.nuanceshim;

/** One prediction result from the engine; the engine blob looks this class up by name. */


public class WordInfo {
    public String word = "";
    public String spell = "";
    public int selectionListIndex = 0;
    public boolean isSpellCorrected = false;
    public boolean shouldAutoAccept = false;

    /**
     * The word came from the learned model, not a language dictionary: "Suggest learned words" off
     * drops it unless it is in the personal dictionary. Set by NuanceSDKDictionaryBridge.
     */
    public boolean fromLearnedModel = false;

    /**
     * The {@link NuanceSDK#getSelectionListGeneration() selection-list generation} that
     * {@link #selectionListIndex} refers to; -1 (not stamped) is unusable for index-based selection.
     */
    public long selectionListGeneration = -1L;

    /* Native deposit sequence this gesture word came from. The commit handler consumes exactly this
     * sequence so a late commit cannot steal the next swipe's pending state. -1 = not a gesture word. */
    public int gestureSeq = -1;

    public String toString() {
        return "WordInfo{word='" + this.word + '\'' + ", spell='" + this.spell + '\'' + ", selectionListIndex=" + this.selectionListIndex + ", isSpellCorrected=" + this.isSpellCorrected + ", shouldAutoAccept=" + this.shouldAutoAccept + ", selectionListGeneration=" + this.selectionListGeneration + '}';
    }
}
