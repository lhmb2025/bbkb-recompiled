package dev.bbkb.ime.core.shared

import android.os.Process
import android.view.inputmethod.InputBinding

object ProfileDetector {
    /** True when the IME runs as the primary user and the bound field belongs to another user (work profile). */
    @JvmStatic
    fun isInputFromWorkProfile(binding: InputBinding?): Boolean {
        if (binding == null) return false
        val myUid = Process.myUid()
        val bindingUid = binding.uid
        // Bits 0x00FF0000 of a UID are the user id.
        return (myUid and 0x00FF0000 == 0) && (bindingUid and 0x00FF0000 != 0)
    }
}