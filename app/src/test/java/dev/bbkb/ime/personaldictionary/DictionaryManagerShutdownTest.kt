package dev.bbkb.ime.personaldictionary

import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shutting the dictionary down writes what is still unsaved before its scope is cancelled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DictionaryManagerShutdownTest {

    @Test
    fun unsavedChangesAreWrittenBeforeTheTeardown() {
        val pdu = mock(PersonalDictionaryUtil::class.java)
        `when`(pdu.areDataDirty).thenReturn(true)
        val manager = DictionaryManager.getInstance()
        manager.attachLoadedUtilForTest(pdu)
        manager.shutdown()
        verify(pdu).doSave()
    }

    @Test
    fun nothingUnsavedMeansNoSave() {
        val pdu = mock(PersonalDictionaryUtil::class.java)
        `when`(pdu.areDataDirty).thenReturn(false)
        val manager = DictionaryManager.getInstance()
        manager.attachLoadedUtilForTest(pdu)
        manager.shutdown()
        verify(pdu, never()).doSave()
    }
}
