package dev.bbkb.ime.core.subtypeswitcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GOLDEN-STRING CHARACTERISATION TEST — READ THIS BEFORE "FIXING" A FAILURE.
 *
 * Every literal in this file was read out of the running code and pasted in. None of them is
 * computed by re-implementing [SubtypeFactory]'s logic. That is deliberate: a test that rebuilt the
 * expected string from the same rules would agree with any future change and would prove nothing.
 *
 * **A failure here is a COMPATIBILITY BREAK, not a bug in the test.**
 *
 * `InputMethodSubtype` identity is the extra-value string together with the locale and mode. The
 * subtype id this factory hands to `InputMethodSubtypeCompat.newInputMethodSubtype` *is*
 * `Arrays.hashCode({locale, "keyboard", extraValue, false, false})`, and that id is what
 * `InputMethodSubtype.hashCode()` returns and what the framework persists in the enabled-subtype
 * list. Change one character of an extra value and every subtype the user had enabled becomes a
 * different subtype on the next boot: their enabled-language selection silently resets. So if a
 * change makes a row below fail, the change is wrong — or it is an intentional, migration-carrying
 * break that has to be signed off, never papered over by editing the golden.
 *
 * Coverage: all 89 `imeSubtypeLocale` values in `res/xml/method.xml`, paired with the keyboard
 * layout set each one actually resolves to. Twenty-one of the manifest subtypes carry no
 * `KeyboardLayoutSet=` in their own extra value and get their layout from
 * `R.array.locale_and_extra_value_to_keyboard_layout_set_map` at runtime; those pairings are the
 * ones marked `[map]` in the tables below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtypeFactoryExtraValueGoldenTest {

    /** locale, layout set, exact extra value, exact subtype id (== `hashCode()`). */
    private data class G(val locale: String, val layout: String, val extraValue: String, val subtypeId: Int)

    /** locale, layout set, exact extra value produced by [SubtypeFactory.createLanguageSubtype]. */
    private data class L(val locale: String, val layout: String, val extraValue: String)

    /** pref spec, subtype count, exact extra value of the first subtype, its id, its nameResId. */
    private data class P(val spec: String, val count: Int, val extraValue: String?, val subtypeId: Int?, val nameResId: Int?)

    @Before
    fun setUp() {
        // reinit(), not init(): Robolectric reuses a sandbox classloader across test classes, and
        // ResourceLocaleUtils.init() is a no-op once any earlier test has set sInitialized.
        ResourceLocaleUtils.reinit(ApplicationProvider.getApplicationContext<Context>())
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 1a — createSubtype(locale, layout): the two-argument path, used by the board factories
    // (number pad, unified input menu, arrow bar, slide numeric sub-panel) and by the spell
    // checker. AsciiCapable = false, EmojiCapable = false.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    private val CREATE_SUBTYPE_GOLDEN = listOf(
        // [map] — layout comes from locale_and_extra_value_to_keyboard_layout_set_map, not method.xml
        G("en_US", "qwerty", "KeyboardLayoutSet=qwerty,UntranslatableReplacementStringInSubtypeName=QWERTY,isAdditionalSubtype", 251056975),
        G("en_GB", "qwerty", "KeyboardLayoutSet=qwerty,UntranslatableReplacementStringInSubtypeName=QWERTY,isAdditionalSubtype", -165450996),
        G("af", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", 2109119710),
        G("ar", "arabic", "KeyboardLayoutSet=arabic,isAdditionalSubtype", -738492034), // [map]
        G("az_AZ", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1759519304),
        G("be", "east_slavic", "KeyboardLayoutSet=east_slavic,isAdditionalSubtype", 1372955062),
        G("bg", "bulgarian", "KeyboardLayoutSet=bulgarian,isAdditionalSubtype", -1460899387),
        G("bn_IN", "bengali", "KeyboardLayoutSet=bengali,isAdditionalSubtype", 1814187875),
        G("bs", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -2145212662),
        G("ca", "spanish", "KeyboardLayoutSet=spanish,isAdditionalSubtype", -1985942693),
        G("cs", "qwertz", "KeyboardLayoutSet=qwertz,isAdditionalSubtype", -871819030), // [map]
        G("cy", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -2111042385),
        G("da", "nordic", "KeyboardLayoutSet=nordic,isAdditionalSubtype", -1901868949), // [map]
        G("de", "qwertz", "KeyboardLayoutSet=qwertz,isAdditionalSubtype", -856119173), // [map]
        G("el", "greek", "KeyboardLayoutSet=greek,isAdditionalSubtype", 174134576),
        G("en_AU", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", 157092291),
        G("en_CA", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", 195880173),
        G("en_IE", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", 371349163),
        G("en_IN", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", 379660852),
        G("en_NZ", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", 533888859),
        G("en_SG", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", 659487715),
        G("en_ZA", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", 854350646),
        G("es", "spanish", "KeyboardLayoutSet=spanish,isAdditionalSubtype", -1912061013), // [map]
        // CHARACTERISED BUG: es_US is an exceptional locale but "spanish" is not a predefined
        // layout, so getKeyboardLayoutSetDisplayName returns null and the literal text "null" is
        // persisted into the extra value. Same for zh_CN_pinyin and zh_HK_cangjie below.
        G("es_US", "spanish", "KeyboardLayoutSet=spanish,UntranslatableReplacementStringInSubtypeName=null,isAdditionalSubtype", -390216163),
        G("es_419", "spanish", "KeyboardLayoutSet=spanish,isAdditionalSubtype", 2032541384),
        G("et_EE", "nordic", "KeyboardLayoutSet=nordic,isAdditionalSubtype", -1968498530),
        G("eu_ES", "spanish", "KeyboardLayoutSet=spanish,isAdditionalSubtype", -268203494),
        G("es_MX", "spanish", "KeyboardLayoutSet=spanish,isAdditionalSubtype", 774793945),
        G("fa", "farsi", "KeyboardLayoutSet=farsi,isAdditionalSubtype", -1662919631),
        G("fi", "nordic", "KeyboardLayoutSet=nordic,isAdditionalSubtype", -1837222479), // [map]
        G("fil", "spanish", "KeyboardLayoutSet=spanish,isAdditionalSubtype", -1472946810),
        G("fr", "azerty", "KeyboardLayoutSet=azerty,isAdditionalSubtype", 634039896), // [map]
        // fr_CA's layout is qwerty, not fr's azerty — a locale whose layout differs from its
        // language code, and one of the four the collisionHack touches.
        G("fr_CA", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", 1064699754), // [map]
        G("fr_BE", "azerty", "KeyboardLayoutSet=azerty,isAdditionalSubtype", -589543134),
        G("nl_BE", "azerty", "KeyboardLayoutSet=azerty,isAdditionalSubtype", 263762928),
        G("de_BE", "azerty", "KeyboardLayoutSet=azerty,isAdditionalSubtype", 1843667917),
        G("ga", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -2018690285),
        G("gl_ES", "spanish", "KeyboardLayoutSet=spanish,isAdditionalSubtype", 1906431045),
        G("hi", "hindi_compact", "KeyboardLayoutSet=hindi_compact,isAdditionalSubtype", -1553356352),
        G("hr", "qwertz", "KeyboardLayoutSet=qwertz,isAdditionalSubtype", -729596796), // [map]
        G("hu", "qwertz", "KeyboardLayoutSet=qwertz,isAdditionalSubtype", -726826233), // [map]
        G("hy_AM", "armenian_phonetic", "KeyboardLayoutSet=armenian_phonetic,isAdditionalSubtype", 1284931153),
        G("in", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1949426210),
        G("is", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1944808605),
        G("it", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1943885084), // [map]
        G("iw", "hebrew", "KeyboardLayoutSet=hebrew,isAdditionalSubtype", -1355155826), // [map]
        G("jv", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1913408891),
        G("ka_GE", "georgian", "KeyboardLayoutSet=georgian,isAdditionalSubtype", 307808088),
        G("km_KH", "khmer", "KeyboardLayoutSet=khmer,isAdditionalSubtype", -1399398144),
        G("kn_IN", "kannada", "KeyboardLayoutSet=kannada,isAdditionalSubtype", 778905170),
        G("ko", "hangul", "KeyboardLayoutSet=hangul,isAdditionalSubtype", -989685938),
        G("ky", "east_slavic", "KeyboardLayoutSet=east_slavic,isAdditionalSubtype", 1649087841),
        G("lo_LA", "lao", "KeyboardLayoutSet=lao,isAdditionalSubtype", -1030430380),
        G("lt", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1857997631),
        G("lv", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1856150589),
        G("mk", "south_slavic", "KeyboardLayoutSet=south_slavic,isAdditionalSubtype", 72233177),
        G("ml_IN", "malayalam", "KeyboardLayoutSet=malayalam,isAdditionalSubtype", -929264075),
        G("mn_MN", "mongolian", "KeyboardLayoutSet=mongolian,isAdditionalSubtype", 888553318),
        G("mr_IN", "marathi", "KeyboardLayoutSet=marathi,isAdditionalSubtype", 662587354),
        G("ms_MY", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -946280546),
        G("nb", "nordic", "KeyboardLayoutSet=nordic,isAdditionalSubtype", -1614653918), // [map]
        G("nl", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1808127497), // [map]
        G("pl", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1750869195), // [map]
        G("pt_BR", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -652251612),
        G("pt_PT", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -249596456),
        G("ro", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1690840330),
        G("ru", "east_slavic", "KeyboardLayoutSet=east_slavic,isAdditionalSubtype", 1845797814), // [map]
        G("sk", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1665905263),
        G("sl", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1664981742),
        G("sq", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1660364137),
        G("sr", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1659440616), // [map]
        G("su", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1656670053),
        G("sv", "nordic", "KeyboardLayoutSet=nordic,isAdditionalSubtype", -1453037743), // [map]
        G("ta_IN", "tamil", "KeyboardLayoutSet=tamil,isAdditionalSubtype", 723549763),
        G("te_IN", "telugu", "KeyboardLayoutSet=telugu,isAdditionalSubtype", -1893693828),
        G("th", "thai", "KeyboardLayoutSet=thai,isAdditionalSubtype", -1563097551),
        G("tr", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1630811465), // [map]
        G("uk", "east_slavic", "KeyboardLayoutSet=east_slavic,isAdditionalSubtype", 1922450057),
        G("uz", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1594794146),
        G("vi", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1581864852),
        G("ja", "romaji", "KeyboardLayoutSet=romaji,isAdditionalSubtype", 2139134312),
        G("zh_CN_pinyin", "pinyin", "KeyboardLayoutSet=pinyin,UntranslatableReplacementStringInSubtypeName=null,isAdditionalSubtype", -1391379513),
        G("zh_CN_stroke", "stroke", "KeyboardLayoutSet=stroke,isAdditionalSubtype", -1656088172),
        G("zh_HK_cangjie", "cangjie", "KeyboardLayoutSet=cangjie,UntranslatableReplacementStringInSubtypeName=null,isAdditionalSubtype", -1534393235),
        G("zh_HK_stroke", "stroke", "KeyboardLayoutSet=stroke,isAdditionalSubtype", 784615932),
        G("zh_TW_zhuyin", "zhuyin", "KeyboardLayoutSet=zhuyin,isAdditionalSubtype", 1926553914),
        G("zh_TW_stroke", "stroke", "KeyboardLayoutSet=stroke,isAdditionalSubtype", -1187227524),
        G("zh_TW_pinyin", "pinyin", "KeyboardLayoutSet=pinyin,isAdditionalSubtype", 1766337566),
        G("zz", "qwerty", "KeyboardLayoutSet=qwerty,isAdditionalSubtype", -1451648391),
    )

    @Test
    fun createSubtype_extraValueAndSubtypeId_areFrozen() {
        assertEquals("manifest locale count changed", 89, CREATE_SUBTYPE_GOLDEN.size)
        val failures = mutableListOf<String>()
        for (g in CREATE_SUBTYPE_GOLDEN) {
            val subtype = SubtypeFactory.createSubtype(g.locale, g.layout)
            if (subtype.extraValue != g.extraValue) {
                failures += "${g.locale}/${g.layout} extraValue\n  expected: ${g.extraValue}\n  actual:   ${subtype.extraValue}"
            }
            if (subtype.hashCode() != g.subtypeId) {
                failures += "${g.locale}/${g.layout} subtypeId expected ${g.subtypeId} but was ${subtype.hashCode()}"
            }
            if (subtype.locale != g.locale) {
                failures += "${g.locale} locale round-trip was ${subtype.locale}"
            }
            if (subtype.mode != "keyboard") {
                failures += "${g.locale} mode expected keyboard but was ${subtype.mode}"
            }
            if (subtype.iconResId != R.drawable.ic_ime_switcher) {
                failures += "${g.locale} iconResId expected ic_ime_switcher but was ${subtype.iconResId}"
            }
        }
        assertTrue(
            "PERSISTED SUBTYPE IDENTITY CHANGED — this is a compatibility break, not a test bug:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * The subtype id is derived from the extra value, so the two can never drift apart: this is the
     * invariant `SubtypeFactory.computeSubtypeHash`'s javadoc describes. Two subtypes must not
     * collide, the same inputs must always give the same id, and none may be 0 (a 0 id makes
     * `InputMethodSubtype` fall back to its own field hash, which is not what was persisted).
     */
    @Test
    fun subtypeId_isStableAndDistinguishesLocales() {
        assertEquals(
            SubtypeFactory.createSubtype("en_US", "qwerty").hashCode(),
            SubtypeFactory.createSubtype("en_US", "qwerty").hashCode()
        )
        assertFalse(
            SubtypeFactory.createSubtype("en_US", "qwerty").hashCode() ==
                SubtypeFactory.createSubtype("en_US", "qwertz").hashCode()
        )
        val ids = CREATE_SUBTYPE_GOLDEN.map { it.subtypeId }
        assertEquals("two manifest locales share a subtype id", ids.size, ids.toSet().size)
        assertTrue("a subtype id of 0 changes what the framework hashes", ids.none { it == 0 })
    }

    /**
     * `isAdditionalSubtype` is on every string this factory builds — including the ones the board
     * factories make for the number pad and the unified input menu, which are not "additional
     * subtypes" in any user-facing sense. Pinned because [SubtypeFactory.isAdditionalSubtype] is
     * what `MultiLanguageRepository` and `SubtypeSwitcherDialog` filter on.
     */
    @Test
    fun everySubtypeThisFactoryBuildsIsFlaggedAdditional() {
        for (g in CREATE_SUBTYPE_GOLDEN) {
            assertTrue(
                "${g.locale} lost isAdditionalSubtype",
                SubtypeFactory.isAdditionalSubtype(SubtypeFactory.createSubtype(g.locale, g.layout))
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 1b — createLanguageSubtype: the multi-language path (MultiLanguageConfig.toSubtype).
    // AsciiCapable = true, EmojiCapable = true, plus the collisionHack suffix and AdditionalLocales.
    //
    // The collisionHack values (1f = 31, 11 = 17, 7) are hex-formatted ints appended to defeat
    // InputMethodSubtype hash collisions between multi-language configurations. They are persisted
    // exactly like everything else here.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    private val LANGUAGE_SUBTYPE_GOLDEN = listOf(
        L("en_US", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,UntranslatableReplacementStringInSubtypeName=QWERTY,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("en_GB", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,UntranslatableReplacementStringInSubtypeName=QWERTY,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("af", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ar", "arabic", "KeyboardLayoutSet=arabic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("az_AZ", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("be", "east_slavic", "KeyboardLayoutSet=east_slavic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("bg", "bulgarian", "KeyboardLayoutSet=bulgarian,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("bn_IN", "bengali", "KeyboardLayoutSet=bengali,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("bs", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=1f,AdditionalLocales=de/es"),
        L("ca", "spanish", "KeyboardLayoutSet=spanish,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("cs", "qwertz", "KeyboardLayoutSet=qwertz,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=7,AdditionalLocales=de/es"),
        L("cy", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("da", "nordic", "KeyboardLayoutSet=nordic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("de", "qwertz", "KeyboardLayoutSet=qwertz,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("el", "greek", "KeyboardLayoutSet=greek,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("en_AU", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("en_CA", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=11,AdditionalLocales=de/es"),
        L("en_IE", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("en_IN", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("en_NZ", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("en_SG", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("en_ZA", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=7,AdditionalLocales=de/es"),
        L("es", "spanish", "KeyboardLayoutSet=spanish,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("es_US", "spanish", "KeyboardLayoutSet=spanish,AsciiCapable,UntranslatableReplacementStringInSubtypeName=null,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("es_419", "spanish", "KeyboardLayoutSet=spanish,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("et_EE", "nordic", "KeyboardLayoutSet=nordic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("eu_ES", "spanish", "KeyboardLayoutSet=spanish,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("es_MX", "spanish", "KeyboardLayoutSet=spanish,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("fa", "farsi", "KeyboardLayoutSet=farsi,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("fi", "nordic", "KeyboardLayoutSet=nordic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("fil", "spanish", "KeyboardLayoutSet=spanish,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("fr", "azerty", "KeyboardLayoutSet=azerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("fr_CA", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=1f,AdditionalLocales=de/es"),
        L("fr_BE", "azerty", "KeyboardLayoutSet=azerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("nl_BE", "azerty", "KeyboardLayoutSet=azerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("de_BE", "azerty", "KeyboardLayoutSet=azerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ga", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("gl_ES", "spanish", "KeyboardLayoutSet=spanish,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=7,AdditionalLocales=de/es"),
        L("hi", "hindi_compact", "KeyboardLayoutSet=hindi_compact,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("hr", "qwertz", "KeyboardLayoutSet=qwertz,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("hu", "qwertz", "KeyboardLayoutSet=qwertz,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("hy_AM", "armenian_phonetic", "KeyboardLayoutSet=armenian_phonetic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("in", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("is", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=7,AdditionalLocales=de/es"),
        L("it", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=11,AdditionalLocales=de/es"),
        L("iw", "hebrew", "KeyboardLayoutSet=hebrew,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("jv", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=7,AdditionalLocales=de/es"),
        L("ka_GE", "georgian", "KeyboardLayoutSet=georgian,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("km_KH", "khmer", "KeyboardLayoutSet=khmer,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("kn_IN", "kannada", "KeyboardLayoutSet=kannada,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ko", "hangul", "KeyboardLayoutSet=hangul,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ky", "east_slavic", "KeyboardLayoutSet=east_slavic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("lo_LA", "lao", "KeyboardLayoutSet=lao,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("lt", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("lv", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("mk", "south_slavic", "KeyboardLayoutSet=south_slavic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ml_IN", "malayalam", "KeyboardLayoutSet=malayalam,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("mn_MN", "mongolian", "KeyboardLayoutSet=mongolian,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("mr_IN", "marathi", "KeyboardLayoutSet=marathi,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ms_MY", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("nb", "nordic", "KeyboardLayoutSet=nordic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("nl", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=1f,AdditionalLocales=de/es"),
        L("pl", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=7,AdditionalLocales=de/es"),
        L("pt_BR", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("pt_PT", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ro", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ru", "east_slavic", "KeyboardLayoutSet=east_slavic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("sk", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("sl", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=11,AdditionalLocales=de/es"),
        L("sq", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("sr", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=1f,AdditionalLocales=de/es"),
        L("su", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("sv", "nordic", "KeyboardLayoutSet=nordic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ta_IN", "tamil", "KeyboardLayoutSet=tamil,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("te_IN", "telugu", "KeyboardLayoutSet=telugu,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("th", "thai", "KeyboardLayoutSet=thai,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("tr", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,collisionHack=11,AdditionalLocales=de/es"),
        L("uk", "east_slavic", "KeyboardLayoutSet=east_slavic,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("uz", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("vi", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("ja", "romaji", "KeyboardLayoutSet=romaji,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("zh_CN_pinyin", "pinyin", "KeyboardLayoutSet=pinyin,AsciiCapable,UntranslatableReplacementStringInSubtypeName=null,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("zh_CN_stroke", "stroke", "KeyboardLayoutSet=stroke,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("zh_HK_cangjie", "cangjie", "KeyboardLayoutSet=cangjie,AsciiCapable,UntranslatableReplacementStringInSubtypeName=null,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("zh_HK_stroke", "stroke", "KeyboardLayoutSet=stroke,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("zh_TW_zhuyin", "zhuyin", "KeyboardLayoutSet=zhuyin,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("zh_TW_stroke", "stroke", "KeyboardLayoutSet=stroke,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("zh_TW_pinyin", "pinyin", "KeyboardLayoutSet=pinyin,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
        L("zz", "qwerty", "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype,AdditionalLocales=de/es"),
    )

    @Test
    fun createLanguageSubtype_extraValue_isFrozen() {
        assertEquals("manifest locale count changed", 89, LANGUAGE_SUBTYPE_GOLDEN.size)
        val additional = arrayListOf("de", "es")
        val failures = mutableListOf<String>()
        for (l in LANGUAGE_SUBTYPE_GOLDEN) {
            val subtype = SubtypeFactory.createLanguageSubtype(l.locale, additional, l.layout)!!
            if (subtype.extraValue != l.extraValue) {
                failures += "${l.locale}/${l.layout}\n  expected: ${l.extraValue}\n  actual:   ${subtype.extraValue}"
            }
            if (subtype.locale != l.locale) {
                failures += "${l.locale} locale round-trip was ${subtype.locale}"
            }
            // Unlike createSubtype, this path builds the subtype by hand and never consults
            // ResourceLocaleUtils.getSubtypeNameResId, so the name res id is always 0.
            if (subtype.nameResId != 0) {
                failures += "${l.locale} nameResId expected 0 but was ${subtype.nameResId}"
            }
            if (subtype.iconResId != R.drawable.ic_ime_switcher) {
                failures += "${l.locale} iconResId was ${subtype.iconResId}"
            }
        }
        assertTrue(
            "PERSISTED MULTI-LANGUAGE SUBTYPE IDENTITY CHANGED — compatibility break:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * The collisionHack table, stated as membership rather than as whole strings, so a rewrite that
     * reorders the if/else-if chain fails here loudly and specifically. The three sets are written
     * out by hand from the literals in `SubtypeFactory.createLanguageSubtype`; the exhaustive
     * 89-row table above is what proves no *other* locale grew a collisionHack.
     */
    @Test
    fun collisionHack_bucketsAreFrozen() {
        val expected = mapOf(
            "1f" to setOf("nl", "fr_CA", "bs", "sr"),
            "11" to setOf("tr", "sl", "en_CA", "it"),
            "7" to setOf("pl", "is", "en_ZA", "jv", "gl_ES", "cs"),
        )
        val actual = mutableMapOf<String, MutableSet<String>>()
        for (l in LANGUAGE_SUBTYPE_GOLDEN) {
            val subtype = SubtypeFactory.createLanguageSubtype(l.locale, arrayListOf("de"), l.layout)!!
            val hack = subtype.getExtraValueOf("collisionHack") ?: continue
            actual.getOrPut(hack) { mutableSetOf() }.add(l.locale)
        }
        assertEquals(expected, actual)
        // 31, 17 and 7 are what the production code hex-formats. Spelled out so that a "tidy"
        // replacing Integer.toHexString(31) with a literal has to keep producing "1f", not "31".
        assertEquals("1f", Integer.toHexString(31))
        assertEquals("11", Integer.toHexString(17))
        assertEquals("7", Integer.toHexString(7))
    }

    @Test
    fun createLanguageSubtype_additionalLocalesAreSlashJoinedInOrder() {
        assertEquals(
            "fr",
            SubtypeFactory.createLanguageSubtype("en_US", arrayListOf("fr"), "qwerty")!!
                .getExtraValueOf("AdditionalLocales")
        )
        assertEquals(
            "fr/de/es",
            SubtypeFactory.createLanguageSubtype("en_US", arrayListOf("fr", "de", "es"), "qwerty")!!
                .getExtraValueOf("AdditionalLocales")
        )
        // collisionHack is emitted BEFORE AdditionalLocales. Token order is part of the string,
        // so it is part of the identity.
        assertEquals(
            "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype," +
                "collisionHack=1f,AdditionalLocales=fr/de",
            SubtypeFactory.createLanguageSubtype("nl", arrayListOf("fr", "de"), "qwerty")!!.extraValue
        )
    }

    @Test
    fun createLanguageSubtype_returnsNullForEmptyLocaleOrNoAdditionalLocales() {
        assertNull(SubtypeFactory.createLanguageSubtype("", arrayListOf("fr"), "qwerty"))
        assertNull(SubtypeFactory.createLanguageSubtype(null, arrayListOf("fr"), "qwerty"))
        assertNull(SubtypeFactory.createLanguageSubtype("en_US", arrayListOf(), "qwerty"))
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // 1c — createSubtypesFromPref: parses the persisted `custom_input_styles` preference and
    // R.array.predefined_builtin_keyboard_subtypes. AsciiCapable comes from the optional third
    // field; EmojiCapable is always set on this path.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    private val PREF_GOLDEN = listOf(
        // The four entries of R.array.predefined_builtin_keyboard_subtypes — these are seeded into
        // the pref on first run by SettingsManager, so their ids are persisted on every install.
        P("be:translit", 1, "KeyboardLayoutSet=translit,EmojiCapable,isAdditionalSubtype", -182254513, R.string.subtype_generic_translit),
        P("ky:translit", 1, "KeyboardLayoutSet=translit,EmojiCapable,isAdditionalSubtype", 93878266, R.string.subtype_generic_translit),
        P("ru:translit", 1, "KeyboardLayoutSet=translit,EmojiCapable,isAdditionalSubtype", 290588239, R.string.subtype_generic_translit),
        P("uk:translit", 1, "KeyboardLayoutSet=translit,EmojiCapable,isAdditionalSubtype", 367240482, R.string.subtype_generic_translit),
        //
        // The nameResIds below were 0 until 2026-09-21 — NOT because the resources were missing,
        // but because ResourceLocaleUtils resolved them with
        // `getIdentifier(name, null, "com.blackberry.keyboard")`, and getIdentifier's defPackage
        // is the *installed* package, which the debug build has never been (it is `.debug`
        // suffixed) and the app no longer is at all (dev.bbkb.ime). Every lookup missed and the
        // subtypes carried nameResId 0, i.e. no display name in the system's subtype list. The
        // literal is now `context.getPackageName()`, so these resolve the way they did in the
        // original APK, whose applicationId happened to equal the literal.
        //
        // This is NOT a compatibility break: the subtype id is
        // hashCode({locale, "keyboard", extraValue, false, false}) and carries no resId, so every
        // id below is unchanged and nobody's enabled-language selection moves. Only the label the
        // framework draws changes — from nothing to the right string.
        //
        // Two fields: AsciiCapable off, EmojiCapable on. en_US and es_US are *exceptional*
        // locales (R.array.subtype_locale_exception_keys), so they name subtype_with_layout_<loc>
        // rather than subtype_generic_<layout>.
        P("en_US:qwerty", 1, "KeyboardLayoutSet=qwerty,UntranslatableReplacementStringInSubtypeName=QWERTY,EmojiCapable,isAdditionalSubtype", -1341559595, R.string.subtype_with_layout_en_US),
        // Three fields, third is exactly "AsciiCapable": the flag goes on, and the id changes.
        P("en_US:qwerty:AsciiCapable", 1, "KeyboardLayoutSet=qwerty,AsciiCapable,UntranslatableReplacementStringInSubtypeName=QWERTY,EmojiCapable,isAdditionalSubtype", -1402186410, R.string.subtype_with_layout_en_US),
        P("fr:azerty:AsciiCapable", 1, "KeyboardLayoutSet=azerty,AsciiCapable,EmojiCapable,isAdditionalSubtype", 953826851, R.string.subtype_generic_azerty),
        P("es_US:spanish:AsciiCapable", 1, "KeyboardLayoutSet=spanish,AsciiCapable,UntranslatableReplacementStringInSubtypeName=null,EmojiCapable,isAdditionalSubtype", -1993250202, R.string.subtype_with_layout_es_US),
        // "zz" is the no-language subtype: the layout key is rewritten to zz_<layout>, so it
        // names subtype_no_language_qwerty, not subtype_generic_qwerty.
        P("zz:qwerty:AsciiCapable", 1, "KeyboardLayoutSet=qwerty,AsciiCapable,EmojiCapable,isAdditionalSubtype", 1433648132, R.string.subtype_no_language_qwerty),
        // CHARACTERISED BUG: any third field that is not literally "AsciiCapable" is swallowed
        // silently — same string, same id as the two-field form. A typo in the persisted pref
        // downgrades the subtype instead of being reported.
        P("en_US:qwerty:Nonsense", 1, "KeyboardLayoutSet=qwerty,UntranslatableReplacementStringInSubtypeName=QWERTY,EmojiCapable,isAdditionalSubtype", -1341559595, R.string.subtype_with_layout_en_US),
        P("de:qwertz", 1, "KeyboardLayoutSet=qwertz,EmojiCapable,isAdditionalSubtype", -1069895133, R.string.subtype_generic_qwertz),
        // CHARACTERISED BUG: createSubtypesFromPref DROPS any spec whose layout has no
        // subtype_generic_<layout> string, because getSubtypeNameResId falls back to
        // R.string.subtype_generic and the loop filters that value out. So a user can never
        // persist a custom input style for Russian/east_slavic or Arabic/arabic, even though
        // createSubtype builds those subtypes happily (see CREATE_SUBTYPE_GOLDEN above).
        // Note this bug survived the getIdentifier fix above unchanged: the fallback is reached
        // because sLayoutToNameId has no entry for these layouts at all, not because the lookup
        // failed — east_slavic and arabic are not in R.array.predefined_layouts.
        P("ru:east_slavic", 0, null, null, null),
        P("ar:arabic", 0, null, null, null),
    )

    @Test
    fun createSubtypesFromPref_isFrozen() {
        val failures = mutableListOf<String>()
        for (p in PREF_GOLDEN) {
            val subtypes = SubtypeFactory.createSubtypesFromPref(p.spec)
            if (subtypes.size != p.count) {
                failures += "${p.spec} count expected ${p.count} but was ${subtypes.size}"
                continue
            }
            val first = subtypes.firstOrNull()
            if (first?.extraValue != p.extraValue) {
                failures += "${p.spec} extraValue\n  expected: ${p.extraValue}\n  actual:   ${first?.extraValue}"
            }
            if (first?.hashCode() != p.subtypeId) {
                failures += "${p.spec} subtypeId expected ${p.subtypeId} but was ${first?.hashCode()}"
            }
            if (first?.nameResId != p.nameResId) {
                failures += "${p.spec} nameResId expected ${p.nameResId} but was ${first?.nameResId}"
            }
        }
        assertTrue(
            "PERSISTED CUSTOM-INPUT-STYLE PARSING CHANGED — compatibility break:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * The malformed-input branch space of the `;`/`:` split. Every one of these is silently
     * skipped (a `Log.w` on debug builds only), never thrown.
     */
    @Test
    fun createSubtypesFromPref_malformedSpecsAreSkipped() {
        assertEquals(0, SubtypeFactory.createSubtypesFromPref(null).size)
        assertEquals(0, SubtypeFactory.createSubtypesFromPref("").size)
        assertEquals(0, SubtypeFactory.createSubtypesFromPref("en_US").size)          // 1 field
        assertEquals(0, SubtypeFactory.createSubtypesFromPref("a:b:c:d").size)        // 4 fields
        assertEquals(0, SubtypeFactory.createSubtypesFromPref(";;").size)
        // A bad entry does not poison its neighbours.
        val mixed = SubtypeFactory.createSubtypesFromPref("en_US;en_GB:qwerty;a:b:c:d")
        assertEquals(1, mixed.size)
        assertEquals("en_GB", mixed[0].locale)
    }

    @Test
    fun createSubtypesFromPref_parsesEveryBuiltinSubtypeInOrder() {
        val subtypes = SubtypeFactory.createSubtypesFromPref("be:translit;ky:translit;ru:translit;uk:translit")
        assertEquals(listOf("be", "ky", "ru", "uk"), subtypes.map { it.locale })
    }

    /** Round-trip: createPrefSubtypes joins with ';', createSubtypesFromPref splits on it. */
    @Test
    fun createPrefSubtypes_isTheInverseJoin() {
        assertEquals("", SubtypeFactory.createPrefSubtypes(null))
        assertEquals("", SubtypeFactory.createPrefSubtypes(arrayOf()))
        assertEquals("en_US:qwerty", SubtypeFactory.createPrefSubtypes(arrayOf("en_US:qwerty")))
        val joined = SubtypeFactory.createPrefSubtypes(arrayOf("be:translit", "ky:translit"))
        assertEquals("be:translit;ky:translit", joined)
        assertEquals(listOf("be", "ky"), SubtypeFactory.createSubtypesFromPref(joined).map { it.locale })
    }

    @Test
    fun isAdditionalSubtype_isFalseForASubtypeThisFactoryDidNotBuild() {
        val hand = android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeLocale("en_US")
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue("KeyboardLayoutSet=qwerty,AsciiCapable")
            .build()
        assertFalse(SubtypeFactory.isAdditionalSubtype(hand))
    }
}
