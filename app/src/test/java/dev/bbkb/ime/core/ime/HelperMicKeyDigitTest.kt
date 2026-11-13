package dev.bbkb.ime.core.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that a helper-caught mic key that doubles as a digit (the Q25's 0 key) types the digit in a
 * number, phone or date field instead of starting voice input, as `KeyEventConverter` does on the
 * ordinary key path (`BoardKeyArmingTest`).
 */
class HelperMicKeyDigitTest {

    private val number = InputType.TYPE_CLASS_NUMBER
    private val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    private val phone = InputType.TYPE_CLASS_PHONE
    private val date = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
    private val text = InputType.TYPE_CLASS_TEXT
    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    @Test
    fun aDigitMicKey_typesItsDigitInNumericFields() {
        for (field in listOf(number, decimal, phone, date)) {
            assertTrue("input type $field", micKeyTypesDigit(field, "0"))
        }
    }

    @Test
    fun aDigitMicKey_isTheMicKeyEverywhereElse() {
        for (field in listOf(text, password, InputType.TYPE_NULL)) {
            assertFalse("input type $field", micKeyTypesDigit(field, "0"))
        }
    }

    @Test
    fun aMicKeyWithoutADigit_isUnaffected() {
        // The Minimal Phone's mic key: its Alt character is a full stop.
        for (field in listOf(number, decimal, phone, date, text)) {
            assertFalse("input type $field", micKeyTypesDigit(field, "."))
        }
        assertFalse(micKeyTypesDigit(number, ""))
    }
}
