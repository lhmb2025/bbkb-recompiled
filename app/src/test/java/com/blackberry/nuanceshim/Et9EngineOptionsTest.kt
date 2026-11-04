package com.blackberry.nuanceshim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [Et9EngineOptions] is a JNI binding into libxt9trace.so; the JVM cannot load that library, so
 * these pin the two things that break silently: the fallback when the library is missing (a
 * status, never an UnsatisfiedLinkError on the IME's engine path) and the JNI symbol the C side
 * exports for the native method (a rename on either side only fails at runtime on a device).
 */
class Et9EngineOptionsTest {

    @Test
    fun withoutTheNativeLibraryEveryCallReportsAnUnreachableEngine() {
        assertFalse(Et9EngineOptions.isLoaded())
        assertEquals(Et9EngineOptions.ERR_CONTEXT, Et9EngineOptions.setMultiThreading(0L, false))
        assertEquals(Et9EngineOptions.ERR_CONTEXT, Et9EngineOptions.setMultiThreading(0x1234L, false))
        assertEquals(Et9EngineOptions.ERR_CONTEXT, Et9EngineOptions.setMultiThreading(0x1234L, true))
    }

    @Test
    fun theNativeMethodMatchesTheSymbolEt9probeExports() {
        val method = Et9EngineOptions::class.java.declaredMethods.single { it.name == "nativeSetMultiThreading" }
        assertTrue(java.lang.reflect.Modifier.isNative(method.modifiers))
        assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers))
        assertEquals(listOf(Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), method.parameterTypes.toList())
        assertEquals(Int::class.javaPrimitiveType, method.returnType)

        val src = sequenceOf("src/main/cpp/et9probe.c", "app/src/main/cpp/et9probe.c")
            .map { File(it) }.first { it.isFile }.readText()
        val symbol = "Java_com_blackberry_nuanceshim_Et9EngineOptions_nativeSetMultiThreading"
        assertTrue("et9probe.c must export $symbol", Regex("""JNIEXPORT\s+jint\s+JNICALL\s+$symbol\s*\(\s*JNIEnv\s*\*\s*\w+\s*,\s*jclass\s+\w+\s*,\s*jlong\s+\w+\s*,\s*jboolean""").containsMatchIn(src))
    }
}
