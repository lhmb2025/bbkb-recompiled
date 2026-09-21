package dev.bbkb.ime.personaldictionary.model

import dev.bbkb.ime.personaldictionary.sync.AudSyncer
import com.google.gson.annotations.SerializedName

/**
 * Abstract base class for dictionary word entries.
 *
 * Defines common properties (word, locale, auto-caps) and AUD synchronization state.
 * Extended by [PersonalWord] and [WordSubstitution].
 *
 * @property word The dictionary word text
 * @property locale The locale identifier for this word
 * @property isAutoCapsEnabled Whether auto-capitalization applies to this word
 */
abstract class DictionaryWord(
    @SerializedName("word") var word: String,
    @SerializedName("locale") val locale: String,
    @SerializedName("autoCapsEnabled") var isAutoCapsEnabled: Boolean
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
