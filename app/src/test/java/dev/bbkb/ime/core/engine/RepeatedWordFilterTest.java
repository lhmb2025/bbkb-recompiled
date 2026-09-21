package dev.bbkb.ime.core.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The repeated-word suggestion filter.
 *
 * <p>Context: multi-word predictions like "Hello hello hello" come from the DLM's n-gram/context
 * state, and the engine exposes no way to delete them at source — {@code ET9AWDLM*} is entirely
 * word-level, and the {@code ET9CPDLM*} phrase API belongs to the Chinese module. Filtering at
 * presentation is the only per-phrase control available, so this behaviour is load-bearing rather
 * than cosmetic. See docs/2026-07_frozen-suggestions_investigation.md.
 */
public class RepeatedWordFilterTest {

    @Test
    public void stutteringPhrases_areFiltered() {
        assertTrue(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("Hello hello hello"));
        assertTrue(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("hello hello"));
        assertTrue(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("I have have"));
        assertTrue(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("no No no"));
    }

    @Test
    public void caseIsIgnored() {
        assertTrue(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("Hello HELLO"));
        assertTrue(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("THE the"));
    }

    /** The reason the rule tests ADJACENT repeats and not "any repeated token". */
    @Test
    public void legitimatePhrasesThatReuseAWord_areKept() {
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("the more the merrier"));
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("out of sight out of mind"));
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("I think I can"));
    }

    @Test
    public void singleWords_areNeverFiltered() {
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("Hello"));
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("hellohello"));
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("there"));
    }

    @Test
    public void prefixesAreNotTreatedAsRepeats() {
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("hell hello"));
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("hello hell"));
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("in inn"));
    }

    @Test
    public void degenerateInput_isSafe() {
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords(null));
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords(""));
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("   "));
        assertFalse(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("a"));
    }

    /** Irregular spacing must not defeat the match. */
    @Test
    public void extraWhitespace_stillMatches() {
        assertTrue(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords("hello  hello"));
        assertTrue(NuanceSDKDictionaryBridge.hasAdjacentDuplicateWords(" hello hello "));
    }
}
