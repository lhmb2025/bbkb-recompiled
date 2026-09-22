package dev.bbkb.ime.harness

import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.InvocationTargetException

/**
 * Default answer for the mocked `BlackBerryIME` that makes it behave like a real [Context].
 *
 * `BlackBerryIME` is an `InputMethodService`, so it IS a Context, and pipeline code reaches
 * through it for resources, themes and styled attributes — the auto-correct indicator, for one,
 * builds a `SuggestionSpan` whose constructor walks `getResources()` and
 * `obtainStyledAttributes()`. On a plain mock every one of those returns null and the test dies
 * on an NPE deep inside the framework, several frames before the thing being asserted. Stubbing
 * them one NPE at a time is a treadmill; delegating the whole Context surface once is not.
 *
 * Only methods **declared on** the Context family are forwarded, to Robolectric's real
 * application context. Methods declared on `BlackBerryIME` itself are left to normal Mockito
 * defaults (null/0/false) even though `BlackBerryIME` is itself a Context subtype — forwarding
 * those would silently run production logic against a half-built object. `InputMethodService`
 * methods are likewise not forwarded: an `Application` has no implementation of them.
 */
class ContextDelegatingAnswer : Answer<Any?> {

    private val contextFamily = setOf<Class<*>>(
        Context::class.java,
        ContextWrapper::class.java,
        ContextThemeWrapper::class.java,
    )

    override fun answer(invocation: InvocationOnMock): Any? {
        val method = invocation.method
        if (method.declaringClass !in contextFamily) {
            return Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        return try {
            method.invoke(RuntimeEnvironment.getApplication(), *invocation.arguments)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }
}
