package dev.bbkb.ime.core.engine

import java.util.Locale

/**
 * What the main thread may know about an engine without calling into it. The engine publishes a
 * new snapshot (a volatile write, `NuanceSDK.getFacts()`) under its lock at the end of every call
 * that can change one of these: a language load, an input-method switch, a model attach or reset,
 * and before that call returns. Readers take no lock and never wait for a language load.
 *
 * Each field is what the like-named NuanceSDK wrapper answered at that moment;
 * [primaryLanguage] falls back to English, as `getPrimaryLanguage()` does, and is null only
 * in [NONE].
 */
data class EngineFacts(
    val primaryLanguage: Locale?,
    val languages: List<Locale>,
    val isCurrLocaleChinese: Boolean,
    val isChineseStrokeMode: Boolean,
    val isChineseCangjieMode: Boolean,
    val isChineseBPMFMode: Boolean,
    val defaultWordSeparator: String,
    val isDLMReady: Boolean,
    val isDLMAttached: Boolean,
) {
    companion object {
        /** No engine, or one not initialised yet or already disposed. */
        @JvmField
        val NONE = EngineFacts(
            primaryLanguage = null,
            languages = emptyList(),
            isCurrLocaleChinese = false,
            isChineseStrokeMode = false,
            isChineseCangjieMode = false,
            isChineseBPMFMode = false,
            defaultWordSeparator = " ",
            isDLMReady = false,
            isDLMAttached = false,
        )
    }
}
