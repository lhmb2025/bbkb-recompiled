package dev.bbkb.ime.core.engine;

import com.blackberry.nuanceshim.NuanceSDK;

import java.util.Locale;

/**
 * Null-safe readers of the published {@link EngineFacts} for the main thread. The facts are null
 * only under JVM-test mocks; readers then answer {@link EngineFacts#NONE} rather than NPE.
 */
public final class PrimaryEngineFacts {

    private PrimaryEngineFacts() {
    }

    /** The primary engine's facts; {@link EngineFacts#NONE} while there is none. */
    public static EngineFacts get() {
        EngineFacts facts = NuanceSDKManager.facts();
        return facts != null ? facts : EngineFacts.NONE;
    }

    /** {@code sdk}'s own facts (the spell checker's engine has its own); NONE for a null engine. */
    public static EngineFacts of(NuanceSDK sdk) {
        EngineFacts facts = sdk != null ? sdk.getFacts() : null;
        return facts != null ? facts : EngineFacts.NONE;
    }

    /** The primary language, English when none is published (never null: the tokenizers reject it). */
    public static Locale primaryLanguage(EngineFacts facts) {
        Locale primary = facts != null ? facts.getPrimaryLanguage() : null;
        return primary != null ? primary : Locale.ENGLISH;
    }

    /** {@link #primaryLanguage(EngineFacts)} of the primary engine. */
    public static Locale primaryLanguage() {
        return primaryLanguage(get());
    }

    /** The separator a word is committed with: none for Chinese, else the engine's default. */
    public static String wordSeparator(EngineFacts facts) {
        if (facts == null) {
            facts = EngineFacts.NONE;
        }
        return facts.isCurrLocaleChinese() ? "" : facts.getDefaultWordSeparator();
    }
}
