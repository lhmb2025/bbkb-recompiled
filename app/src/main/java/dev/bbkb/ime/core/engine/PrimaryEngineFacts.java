package dev.bbkb.ime.core.engine;

import com.blackberry.nuanceshim.NuanceSDK;

import java.util.Locale;

/**
 * Null-safe readers of the published engine facts ({@link EngineFacts}) for the typing path, which
 * reads them on the main thread instead of calling the engine (Wave 3 Phase A).
 *
 * <p>{@link NuanceSDKManager#facts()} never returns null in production; under a static mock of
 * the manager (the JVM tests) it does, and so does {@link NuanceSDK#getFacts()} on a mocked
 * engine. Every reader here answers {@link EngineFacts#NONE} then, so a test that mocks the
 * engine without stubbing the facts sees "no language loaded" rather than an NPE.
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

    /**
     * The primary language, English when no language is published: the default
     * {@code NuanceSDK.getPrimaryLanguage()} gave, so a caller switched from the wrapper to the
     * facts sees the same value (and never a null, which the tokenizers do not accept).
     */
    public static Locale primaryLanguage(EngineFacts facts) {
        Locale primary = facts != null ? facts.getPrimaryLanguage() : null;
        return primary != null ? primary : Locale.ENGLISH;
    }

    /** {@link #primaryLanguage(EngineFacts)} of the primary engine. */
    public static Locale primaryLanguage() {
        return primaryLanguage(get());
    }

    /**
     * The separator a word is committed with: none for Chinese, else the engine's default word
     * separator (what {@code NuanceSDKDictionaryBridge.getWordSeparator} asked the engine for).
     */
    public static String wordSeparator(EngineFacts facts) {
        if (facts == null) {
            facts = EngineFacts.NONE;
        }
        return facts.isCurrLocaleChinese() ? "" : facts.getDefaultWordSeparator();
    }
}
