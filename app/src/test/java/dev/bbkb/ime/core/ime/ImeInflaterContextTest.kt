package dev.bbkb.ime.core.ime

import dev.bbkb.ime.core.BlackBerryIME
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * With gesture navigation (API 33+) the IME nav bar is inflated via `LayoutInflater.from(service)`
 * and its hide chevron works only if its context is the service itself, so the service must not
 * override `getSystemService`. Checked by reflection: Robolectric's inflater cannot tell the
 * difference and `onCreate` starts the engine.
 */
class ImeInflaterContextTest {

    @Test
    fun theServiceDoesNotAnswerSystemServiceLookupsItself() {
        val declared = BlackBerryIME::class.java.declaredMethods.map { it.name }

        assertFalse("BlackBerryIME must not override getSystemService: the system inflates its " +
            "navigation bar through it and needs the service itself as the context. A view that " +
            "needs a theme wraps its own context instead",
            "getSystemService" in declared)
    }
}
