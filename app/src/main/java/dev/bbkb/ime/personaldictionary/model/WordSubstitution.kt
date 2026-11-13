package dev.bbkb.ime.personaldictionary.model

import androidx.annotation.VisibleForTesting

/** A text shortcut: typing [key] expands to [word], which may contain macro tags such as `%D`. */
class WordSubstitution : DictionaryWord {
    val key: String

    val type: Type

    // Written to the on-disk format; never read.
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
