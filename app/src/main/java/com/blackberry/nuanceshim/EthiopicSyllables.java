package com.blackberry.nuanceshim;

import java.util.Locale;

/**
 * Converts Ethiopic syllables to and from the engine's Amharic dictionary form: the first-order
 * letter plus, for orders two to seven, one Latin marker ({@code u i a e ə o}), e.g. ሙ = መu.
 * {@link NuanceSDK} decomposes words going in while an Ethiopic language is primary and composes
 * words coming back. The eighth slot (ሏ, ሟ, labialised and irregular letters) has no marker and
 * passes through unchanged.
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
