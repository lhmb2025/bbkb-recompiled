package dev.bbkb.ime.core.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * The loader thread as a coroutine dispatcher. Engine work from coroutines must use it, never
 * Dispatchers.IO, whose threads can exit (see [EngineThreads]).
 */
val engineLoaderDispatcher: CoroutineDispatcher by lazy { EngineThreads.loader().asCoroutineDispatcher() }
