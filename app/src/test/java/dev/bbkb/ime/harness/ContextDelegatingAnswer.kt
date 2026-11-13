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
 * Default answer that makes the mocked `BlackBerryIME` behave like a real [Context] (e.g.
 * `SuggestionSpan` walks resources and styled attributes). Only methods declared on the Context
 * family go to Robolectric's application; `BlackBerryIME` and `InputMethodService` methods keep
 * Mockito defaults, so no production logic runs on a half-built object.
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
