package dev.bbkb.ime.core.suggestion;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;

import org.junit.Test;

/**
 * {@link SuggestedWords#getSuggestedWordsForDisplay()} feeds the CJK candidate grid. The bridge
 * lists the typed word twice when it is also the engine's first candidate (the Latin strip shows
 * both), and for Chinese stroke input that is the normal case: "一" is what was typed and a real
 * character. The filter used to throw on the second entry and took the keyboard down on the first
 * stroke (KEY2, 2026-09-28).
 */
public class SuggestedWordsDisplayFilterTest {

    private static SuggestedWords.SuggestedWordInfo entry(String word, int kind) {
        return new SuggestedWords.SuggestedWordInfo(word, 100, kind, null, -1, -1, null);
    }

    @Test
    public void aSecondTypedWordEntryIsDroppedNotFatal() {
        ArrayList<SuggestedWords.SuggestedWordInfo> list = new ArrayList<>();
        list.add(entry("一", 0));
        list.add(entry("一", 0));
        list.add(entry("在", 1));
        list.add(entry("與", 1));

        SuggestedWords display = new SuggestedWords(list, false, false, 5).getSuggestedWordsForDisplay();

        assertEquals(2, display.size());
        assertEquals("在", display.getWord(0));
        assertEquals("與", display.getWord(1));
        assertEquals("一", display.mAutoCorrectWord);
    }

    @Test
    public void theFirstTypedWordEntryIsTheOneKept() {
        ArrayList<SuggestedWords.SuggestedWordInfo> list = new ArrayList<>();
        list.add(entry("hello", 0));
        list.add(entry("help", 1));
        list.add(entry("hellr", 0));

        SuggestedWords display = new SuggestedWords(list, false, false, 5).getSuggestedWordsForDisplay();

        assertEquals(1, display.size());
        assertEquals("help", display.getWord(0));
        assertEquals("hello", display.mAutoCorrectWord);
    }

    @Test
    public void noTypedWordEntryMeansNoTypedWord() {
        ArrayList<SuggestedWords.SuggestedWordInfo> list = new ArrayList<>();
        list.add(entry("你", 1));

        SuggestedWords display = new SuggestedWords(list, false, false, 5).getSuggestedWordsForDisplay();

        assertEquals(1, display.size());
        assertEquals(null, display.mAutoCorrectWord);
    }
}
