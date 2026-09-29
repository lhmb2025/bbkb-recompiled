package com.blackberry.nuanceshim;

import java.util.Locale;

/**
 * Translates between the Ethiopic syllables the keyboard types and shows and the form the
 * engine's Amharic dictionary stores them in.
 *
 * <p>The XT9 Amharic language pack does not contain precomposed syllables. Each syllable is its
 * series' first-order letter followed, for orders two to seven, by one Latin marker letter:
 * {@code u i a e ə o}. Typed into the KEY2 with the precomposed layout (2026-09-28) the engine
 * offered "ሰለመu" for ሰለሙ and predicted "ነወə" (ነው) and "ለaየə" (ላይ). The engine has no notion of
 * the composition, so {@link NuanceSDK} decomposes every word it hands the engine while an
 * Ethiopic language is primary, and composes every word the engine hands back.
 *
 * <p>Only the seven regular orders take part. The eighth slot of a series (ሏ, ሟ, ቧ, the
 * labialised forms and the few irregular letters such as ሇ) has no marker and is passed through
 * untouched in both directions, as are the labiovelar series whose missing orders are unassigned
 * code points.
 */
public final class EthiopicSyllables {

    private EthiopicSyllables() {
    }

    private static final int FIRST_SYLLABLE = 0x1200;
    private static final int LAST_SYLLABLE = 0x135A;

    /** Index = order (1..6); the first order is the bare base letter. */
    private static final char[] MARKERS = {0, 'u', 'i', 'a', 'e', 'ə', 'o'};

    /** Whether the engine needs this translation for the given primary language. */
    public static boolean appliesTo(Locale locale) {
        if (locale == null) {
            return false;
        }
        final String language = locale.getLanguage();
        return "am".equals(language) || "ti".equals(language);
    }

    /**
     * The order of an Ethiopic syllable within its series: 0 for the base letter, 1 to 6 for the
     * orders the markers stand for, 7 for the eighth slot, and -1 for anything that is not an
     * assigned syllable of the main Ethiopic block.
     */
    static int order(int codePoint) {
        if (codePoint < FIRST_SYLLABLE || codePoint > LAST_SYLLABLE
                || Character.getType(codePoint) != Character.OTHER_LETTER) {
            return -1;
        }
        return (codePoint - FIRST_SYLLABLE) & 7;
    }

    private static int markerOrder(char c) {
        for (int order = 1; order < MARKERS.length; order++) {
            if (MARKERS[order] == c) {
                return order;
            }
        }
        return 0;
    }

    /** Syllables of orders two to seven become base letter plus marker; everything else is kept. */
    public static String decompose(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final int order = order(c);
            if (order >= 1 && order <= 6) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 8).append(text, 0, i);
                }
                out.append((char) (c - order)).append(MARKERS[order]);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }

    /** A base letter followed by a marker becomes the syllable of that order; everything else is kept. */
    public static String compose(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (i + 1 < text.length() && order(c) == 0) {
                final int order = markerOrder(text.charAt(i + 1));
                if (order > 0 && order(c + order) == order) {
                    if (out == null) {
                        out = new StringBuilder(text.length()).append(text, 0, i);
                    }
                    out.append((char) (c + order));
                    i++;
                    continue;
                }
            }
            if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }
}
