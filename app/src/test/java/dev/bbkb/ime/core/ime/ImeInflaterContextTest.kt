package dev.bbkb.ime.core.ime

import dev.bbkb.ime.core.BlackBerryIME
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * With gesture navigation (API 33+) Android builds the keyboard's own navigation bar with
 * `LayoutInflater.from(service)`. Its Back button, the hide-keyboard chevron, only acts when the
 * button's context is the input method service; given any other context it does nothing, with no
 * error. The service used to override `getSystemService` to answer inflater lookups with a themed
 * wrapper's inflater, which left that button dead.
 *
 * The rule is checked on the class, not by inflating a view: the old override only switched on
 * inside `onCreate`, which a unit test cannot run (it starts the engine), and Robolectric gives a
 * service the application's inflater whatever the service does, so an inflation check here could
 * not tell the two apart. On a device the difference is the chevron working or not.
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
