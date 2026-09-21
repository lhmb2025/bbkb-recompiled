package dev.bbkb.ime.personaldictionary.util

import android.util.Log
import dev.bbkb.ime.BuildConfig

/**
 * Standardized logging utility for the Personal Dictionary subsystem.
 *
 * Wraps Android Log methods with consistent API.
 *
 * `d` stays gated on [BuildConfig.DEBUG] - it is the per-word chatter of the sync loops.
 * `i`, `w` and `e` do not: the AUD bridge is a background subsystem whose failures are otherwise
 * completely invisible, and the 2026-09-21 KEY2 investigation had nothing to read because every
 * failure on the path was either DEBUG or debug-build-only. Milestones (observer registered, a
 * sync completed and what it moved) and failures must survive a release build.
 */
object LogUtil {
    // Standardizing methods to d, i, w, e to match typical Android logging usage
    // Replaces the old obfuscated log helpers

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    fun w(tag: String, tr: Throwable) {
        Log.w(tag, Log.getStackTraceString(tr))
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    fun e(tag: String, tr: Throwable) {
        Log.e(tag, Log.getStackTraceString(tr))
    }
}
