/**
 * The Java side of the prediction-engine boundary.
 *
 * <p>{@code com.blackberry.nuanceshim} is the JNI side and is frozen (it binds to native code by
 * mangled symbol name). This package is everything on the Java side of that line: the process-wide
 * gate to the SDK, the dictionary abstraction the suggestion pipeline talks to, and — under
 * {@code .learning} — the ET9 DLM work.
 *
 * <p>It is deliberately <em>not</em> a "dictionary layer": there is no word list, no trie and no
 * {@code .dict} loading anywhere in the tree. {@code Dictionary} is an abstract type whose only
 * substantial implementation is a bridge to the engine, and {@code DictionaryLoader}'s
 * {@code DICTIONARY_NAMES} is a single-element array. Naming the package for the boundary it
 * guards rather than for a data structure it does not own is the point.
 *
 * <h3>Key classes</h3>
 * <ul>
 *   <li>{@link dev.bbkb.ime.core.engine.NuanceSDKManager} — the gate: engine
 *       lifecycle, initialisation state, and the single entry point every caller goes through.</li>
 *   <li>{@link dev.bbkb.ime.core.engine.Dictionary} — abstract base defining the
 *       suggestion-generation and word-validation API, with four no-op sentinels.</li>
 *   <li>{@link dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge} — the real
 *       implementation: generates candidates, validates words, and handles substitutions across
 *       the JNI layer.</li>
 *   <li>{@link dev.bbkb.ime.core.engine.FallbackDictionary} — hardcoded English word
 *       list used when the engine fails to initialise.</li>
 *   <li>{@link dev.bbkb.ime.core.engine.DictionaryLoader} — async loading, locale
 *       switching and suggestion dispatch; holds the active {@code DictionaryGroup}.</li>
 *   <li>{@link dev.bbkb.ime.core.engine.DictionaryFactory} — picks the
 *       implementation based on engine availability.</li>
 * </ul>
 *
 * <p>The personal dictionary is <em>not</em> here. {@code DictionaryManager}, which used to sit
 * alongside these classes despite being a BASL/personal-dictionary facade, now lives in
 * {@code dev.bbkb.ime.personaldictionary} with the store it fronts.
 *
 * <h3>Threading</h3>
 * <p>Dictionary queries are dispatched from {@code SuggestionWorker}'s background thread. Engine
 * callbacks may arrive on a native thread; existing code posts results to the main thread via
 * {@code UIUpdateHandler}. Any new code in this path must keep that contract.
 *
 * <h3>Caution</h3>
 * <p>{@code NuanceSDKDictionaryBridge} is tightly coupled to the JNI contract. Do not restructure
 * its method signatures without verifying the native side.
 */
package dev.bbkb.ime.core.engine;
