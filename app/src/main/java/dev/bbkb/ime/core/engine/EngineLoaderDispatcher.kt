package dev.bbkb.ime.core.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * The loader thread as a coroutine dispatcher, for the settings screens, backup and pack install,
 * which already run on coroutines: `withContext(engineLoaderDispatcher) { ... }` runs the block on
 * [EngineThreads.loader]'s one persistent thread rather than on a Dispatchers.IO thread, which can
 * exit and so must never call the engine (see [EngineThreads]). A top-level value rather than an
 * extension, because Kotlin cannot add a static member to a Java class.
 */
val engineLoaderDispatcher: CoroutineDispatcher by lazy { EngineThreads.loader().asCoroutineDispatcher() }
