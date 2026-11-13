package dev.bbkb.ime.core.engine

import java.util.Locale

/**
 * What the main thread may know about an engine without calling into it. The engine publishes a new
 * snapshot (volatile, `NuanceSDK.getFacts()`) under its lock before any call that changes one of
 * these returns. Each field is what the like-named NuanceSDK wrapper answers; [primaryLanguage] is
 * null only in [NONE].
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
        /** No engine, or one not yet initialised. */
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
