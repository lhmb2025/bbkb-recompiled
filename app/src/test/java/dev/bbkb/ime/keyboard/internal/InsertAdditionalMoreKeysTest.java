package dev.bbkb.ime.keyboard.internal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pins {@code KeySpecParser.insertAdditionalMoreKeys}, which merges a locale's
 * {@code additional_morekeys_*} entries into a key's {@code %}-marked more-keys slots.
 *
 * <p>The overflow branch — more additional keys than {@code %} markers, which is the only reason
 * that branch exists — declared a loop variable and then indexed with the loop-INVARIANT
 * {@code additionalIndex}, so the tail was appended as N copies of the same character. On a locale
 * with surplus additional more-keys the accent popup showed one character repeated instead of the
 * distinct remainder.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class InsertAdditionalMoreKeysTest {

    /** The overflow branch: one marker, three additional keys. The tail must be distinct. */
    @Test
    public void surplusAdditionalKeysAreAppendedDistinctly() {
        assertArrayEquals(
                new String[] {"a", "x", "y", "z"},
                KeySpecParser.insertAdditionalMoreKeys(
                        new String[] {"a", "%"}, new String[] {"x", "y", "z"}));
    }

    /** Two markers, four additional keys: two substituted in place, two appended. */
    @Test
    public void surplusAfterMultipleMarkers() {
        assertArrayEquals(
                new String[] {"p", "x", "q", "y", "z", "w"},
                KeySpecParser.insertAdditionalMoreKeys(
                        new String[] {"p", "%", "q", "%"}, new String[] {"x", "y", "z", "w"}));
    }

    /** Exactly as many markers as additional keys: pure in-place substitution, no tail. */
    @Test
    public void markersAndAdditionalKeysBalance() {
        assertArrayEquals(
                new String[] {"a", "x", "b", "y"},
                KeySpecParser.insertAdditionalMoreKeys(
                        new String[] {"a", "%", "b", "%"}, new String[] {"x", "y"}));
    }

    /** No marker at all: the additional keys go to the head of the list. */
    @Test
    public void withoutMarkersAdditionalKeysGoFirst() {
        assertArrayEquals(
                new String[] {"x", "y", "a", "b"},
                KeySpecParser.insertAdditionalMoreKeys(
                        new String[] {"a", "b"}, new String[] {"x", "y"}));
    }

    /** Excess markers are filtered out rather than emitted as literal "%". */
    @Test
    public void excessMarkersAreDropped() {
        assertArrayEquals(
                new String[] {"a", "x", "b"},
                KeySpecParser.insertAdditionalMoreKeys(
                        new String[] {"a", "%", "b", "%"}, new String[] {"x"}));
    }

    @Test
    public void nothingToMergeYieldsNull() {
        assertNull(KeySpecParser.insertAdditionalMoreKeys(null, null));
    }
}
