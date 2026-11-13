package dev.bbkb.ime.personaldictionary.model

import dev.bbkb.ime.personaldictionary.sync.AudSyncer

/** Base of [PersonalWord] and [WordSubstitution]: word, locale, auto-caps and AUD sync state. */
abstract class DictionaryWord(
    var word: String,
    val locale: String,
    var isAutoCapsEnabled: Boolean
) {
    @Transient
    private var canBeSynchronised: Boolean? = null

    init {
        require(word.isNotEmpty()) { "word must not be empty" }
        require(locale.isNotEmpty()) { "locale must not be empty" }
    }

    fun canBeSynchronised(): Boolean {
        if (canBeSynchronised == null) {
            canBeSynchronised = AudSyncer.Companion.shouldSync(this)
        }
        return canBeSynchronised!!
    }

    fun setCanBeSynchronised(canBeSynchronised: Boolean) {
        this.canBeSynchronised = canBeSynchronised
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DictionaryWord) return false
        return locale == other.locale && word == other.word
    }

    override fun hashCode(): Int {
        return word.hashCode() * 31 + locale.hashCode()
    }
}
