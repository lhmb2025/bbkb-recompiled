package dev.bbkb.ime.core.shared

import android.os.Process
import android.view.inputmethod.InputBinding

/**
 * ProfileDetector
 * Simplified version for basic profile detection
 */
object ProfileDetector {

    /**
     * Check if input is from work profile
     * @param binding InputBinding from the text field
     * @return True if input is from work profile
     */
    @JvmStatic
    fun isInputFromWorkProfile(binding: InputBinding?): Boolean {
        if (binding == null) return false
        val myUid = Process.myUid()
        val bindingUid = binding.uid
        // Check if user ID part of UID matches (simple heuristic)
        // Or if simple UID check works. Original logic:
        // return ((myUid & 0x00FF0000) == 0) && !((bindingUid & 0x00FF0000) == 0);
        // This seems to check if myUid is system/primary user (upper bits 0?) and bindingUid is not.
        return (myUid and 0x00FF0000 == 0) && (bindingUid and 0x00FF0000 != 0)
    }
}