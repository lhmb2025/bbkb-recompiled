package dev.bbkb.ime.core.contacts

import dev.bbkb.ime.core.contacts.ContactsDataProvider.ContactsType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An unchanged address book is not added to the engine again at every process start (P-21). */
class ContactsAddedFingerprintTest {

    private fun fp(vararg batches: Array<String>, learning: Boolean = true) =
        ContactsLearningManager.fingerprintOf(batches.toList(), batches.map { ContactsType.PERSONAL }, learning)

    @Test
    fun sameWordsSameFingerprint() {
        assertEquals(fp(arrayOf("Ann", "Lee"), arrayOf("Bo")), fp(arrayOf("Ann", "Lee"), arrayOf("Bo")))
    }

    @Test
    fun anyChangeChangesTheFingerprint() {
        val base = fp(arrayOf("Ann", "Lee"), arrayOf("Bo"))
        assertNotEquals(base, fp(arrayOf("Ann", "Lea"), arrayOf("Bo")))
        assertNotEquals(base, fp(arrayOf("Ann"), arrayOf("Lee", "Bo")))
        assertNotEquals(base, fp(arrayOf("Lee", "Ann"), arrayOf("Bo")))
        assertNotEquals(base, fp(arrayOf("Ann", "Lee"), arrayOf("Bo"), learning = false))
        assertNotEquals(base, ContactsLearningManager.fingerprintOf(
            listOf(arrayOf("Ann", "Lee"), arrayOf("Bo")), listOf(ContactsType.ALL, ContactsType.PERSONAL), true))
    }

    @Test
    fun addedRecentlyIsSkippedUntilTheReAddInterval() {
        val f = fp(arrayOf("Ann"))
        val at = 1_000_000L
        assertTrue(ContactsLearningManager.isAlreadyAdded(f, f, at, at + 1))
        assertFalse(ContactsLearningManager.isAlreadyAdded(f, f, at, at + ContactsLearningManager.READD_AFTER_MS))
        assertFalse(ContactsLearningManager.isAlreadyAdded(f, null, at, at + 1))
        assertFalse(ContactsLearningManager.isAlreadyAdded(f, fp(arrayOf("Bo")), at, at + 1))
        assertFalse("clock moved back", ContactsLearningManager.isAlreadyAdded(f, f, at, at - 1))
        assertFalse(ContactsLearningManager.isAlreadyAdded(f, f, 0L, 5L))
    }
}
