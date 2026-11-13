package dev.bbkb.ime.personaldictionary.util

import android.util.Log
import dev.bbkb.ime.BuildConfig

/**
 * Personal dictionary logging. Only `d` (per-word sync chatter) is debug-only: milestones and
 * failures of the background AUD bridge must survive a release build.
 */
object LogUtil {
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
