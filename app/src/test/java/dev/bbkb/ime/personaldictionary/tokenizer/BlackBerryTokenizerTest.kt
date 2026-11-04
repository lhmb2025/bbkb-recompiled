package dev.bbkb.ime.personaldictionary.tokenizer

import android.view.textservice.TextInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Reading the text once per split (P-18) must not change the tokens. The expected strings were
 * captured from the implementation that re-read textInfo.text per token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BlackBerryTokenizerTest {

    private fun dump(locale: Locale, text: String, includeWhitespace: Boolean): String {
        val info = TextInfo(text, 0, text.length, 7, 0)
        val params = BlackBerryTokenizer(locale).split(info, includeWhitespace)
        assertSame(info, params.originalTextInfo)
        return params.items.joinToString("|") { "${it.textInfo.text}@${it.start}+${it.length}#${it.textInfo.cookie}" }
    }

    @Test
    fun english() {
        val text = "Hello, world! It's a test: don't stop... 3.14 e-mail  done."
        assertEquals("""Hello@0+5#7|,@5+1#7|world@7+5#7|!@12+1#7|It's@14+4#7|a@19+1#7|test@21+4#7|:@25+1#7|don't@27+5#7|stop@33+4#7|.@37+1#7|.@38+1#7|.@39+1#7|3.14@41+4#7|e@46+1#7|-@47+1#7|mail@48+4#7|done@54+4#7|.@58+1#7""", dump(Locale.US, text, false))
        assertEquals("""Hello@0+5#7|,@5+1#7| @6+1#7|world@7+5#7|!@12+1#7| @13+1#7|It's@14+4#7| @18+1#7|a@19+1#7| @20+1#7|test@21+4#7|:@25+1#7| @26+1#7|don't@27+5#7| @32+1#7|stop@33+4#7|.@37+1#7|.@38+1#7|.@39+1#7| @40+1#7|3.14@41+4#7| @45+1#7|e@46+1#7|-@47+1#7|mail@48+4#7|  @52+2#7|done@54+4#7|.@58+1#7""", dump(Locale.US, text, true))
    }

    @Test
    fun thai() {
        val text = "สวัสดีครับ ผมชื่อสมชาย วันนี้อากาศดีมาก"
        assertEquals("""สวัสดี@0+6#7|ครับ@6+4#7|ผม@11+2#7|ชื่อ@13+4#7|สมชาย@17+5#7|วัน@23+3#7|นี้@26+3#7|อากาศ@29+5#7|ดี@34+2#7|มาก@36+3#7""", dump(Locale("th"), text, false))
        assertEquals("""สวัสดี@0+6#7|ครับ@6+4#7| @10+1#7|ผม@11+2#7|ชื่อ@13+4#7|สมชาย@17+5#7| @22+1#7|วัน@23+3#7|นี้@26+3#7|อากาศ@29+5#7|ดี@34+2#7|มาก@36+3#7""", dump(Locale("th"), text, true))
    }

    @Test
    fun mixedPunctuation() {
        val text = "  (quoted) \"text\"—em–dash; semi;colon?! #tag @user a/b ¿qué? "
        assertEquals("""(@2+1#7|quoted@3+6#7|)@9+1#7|"@11+1#7|text@12+4#7|"@16+1#7|—@17+1#7|em@18+2#7|–@20+1#7|dash@21+4#7|;@25+1#7|semi@27+4#7|;@31+1#7|colon@32+5#7|?@37+1#7|!@38+1#7|#@40+1#7|tag@41+3#7|@@45+1#7|user@46+4#7|a@51+1#7|/@52+1#7|b@53+1#7|¿@55+1#7|qué@56+3#7|?@59+1#7""", dump(Locale.US, text, false))
        assertEquals("""  @0+2#7|(@2+1#7|quoted@3+6#7|)@9+1#7| @10+1#7|"@11+1#7|text@12+4#7|"@16+1#7|—@17+1#7|em@18+2#7|–@20+1#7|dash@21+4#7|;@25+1#7| @26+1#7|semi@27+4#7|;@31+1#7|colon@32+5#7|?@37+1#7|!@38+1#7| @39+1#7|#@40+1#7|tag@41+3#7| @44+1#7|@@45+1#7|user@46+4#7| @50+1#7|a@51+1#7|/@52+1#7|b@53+1#7| @54+1#7|¿@55+1#7|qué@56+3#7|?@59+1#7| @60+1#7""", dump(Locale.US, text, true))
    }
}
