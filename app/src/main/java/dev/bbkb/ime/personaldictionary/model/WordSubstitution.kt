package dev.bbkb.ime.personaldictionary.model

import androidx.annotation.VisibleForTesting
import com.google.gson.annotations.SerializedName

/**
 * Represents a word substitution (macro/shortcut).
 *
 * When the user types the [key], it expands to the full [word].
 * Supports dynamic content via macro tags (e.g., `%D` for date, `%T` for time).
 *
 * @property key The shortcut text that triggers expansion
 * @property type DEFAULT (built-in) or USER (user-created)
 * @see DictionaryWord
 */
class WordSubstitution : DictionaryWord {
    @SerializedName("key")
    val key: String

    @SerializedName("type")
    val type: Type
    
    // We keep version for compatibility if writing, but we don't strictly need it for logic
    @SerializedName("version")
    val version: Long = 16L

    enum class Type {
        DEFAULT,
        USER
    }

    @VisibleForTesting
    internal constructor(locale: String, key: String, word: String, type: Type, autoCapsEnabled: Boolean) : super(word, locale, autoCapsEnabled) {
        require(locale.isNotEmpty())
        require(key.isNotEmpty())
        require(!key.contains(" "))
        require(word.isNotEmpty())
        this.key = key
        this.type = type
    }

    private constructor(locale: String, key: String, word: String, type: Type) : super(word, locale, false) {
        this.key = key
        this.type = type
    }

    override fun toString(): String {
        return "WordSubstitution{mKey='$key', mWord='$word', mLocale='$locale', mAutoCapsEnabled=$isAutoCapsEnabled, mType=$type}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is WordSubstitution && super.equals(other) && key == other.key
    }

    override fun hashCode(): Int {
        return super.hashCode() * 31 + key.hashCode()
    }

    companion object {
        const val serialVersionUID = 16L

        @JvmStatic
        @VisibleForTesting
        fun createWithoutPreconditions(locale: String, key: String, word: String, type: Type): WordSubstitution {
            return WordSubstitution(locale, key, word, type)
        }

        @JvmStatic
        fun createUserSubstitution(locale: String, key: String, word: String, autoCapsEnabled: Boolean): WordSubstitution {
            return WordSubstitution(locale, key, word, Type.USER, autoCapsEnabled)
        }
    }
}
