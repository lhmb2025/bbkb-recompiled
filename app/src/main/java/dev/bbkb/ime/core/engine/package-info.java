/**
 * The Java side of the prediction-engine boundary. {@code com.blackberry.nuanceshim} is the JNI side
 * and is frozen (it binds to native code by mangled symbol name). There is no word list or trie
 * here: {@code Dictionary}'s only real implementation is a bridge to the engine.
 *
 * <p>Engine calls run only on {@code EngineThreads}' two threads. {@code NuanceSDKDictionaryBridge}
 * is tightly coupled to the JNI contract; verify the native side before changing its signatures.
 */
package dev.bbkb.ime.core.engine;
