package dev.bbkb.ime.core.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/** Which languages get the "cannot type at all" wording rather than "no suggestions yet". */
public class DictionaryAvailabilityNoticeTest {

    @Test
    public void engineComposedScriptsNeedTheDictionaryToTypeAtAll() {
        assertTrue(DictionaryAvailabilityNotice.needsDictionaryToType(new Locale("ko")));
        assertTrue(DictionaryAvailabilityNotice.needsDictionaryToType(new Locale("ja")));
        assertTrue(DictionaryAvailabilityNotice.needsDictionaryToType(new Locale("zh", "TW", "stroke")));
        assertTrue(DictionaryAvailabilityNotice.needsDictionaryToType(new Locale("zh", "CN", "pinyin")));
    }

    @Test
    public void everyOtherLanguageOnlyLosesSuggestions() {
        assertFalse(DictionaryAvailabilityNotice.needsDictionaryToType(new Locale("af")));
        assertFalse(DictionaryAvailabilityNotice.needsDictionaryToType(new Locale("ar")));
        assertFalse(DictionaryAvailabilityNotice.needsDictionaryToType(new Locale("vi")));
        assertFalse(DictionaryAvailabilityNotice.needsDictionaryToType(new Locale("en", "US")));
    }
}
