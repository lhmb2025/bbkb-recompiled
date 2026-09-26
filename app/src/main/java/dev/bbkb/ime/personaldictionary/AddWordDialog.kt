package dev.bbkb.ime.personaldictionary

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.UserDictionary
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.BbkbDialogs
import dev.bbkb.ime.core.shared.Logger
import java.util.Locale

/**
 * "Add to dictionary" in BBKB's own colours.
 *
 * The plus on the add-to-dictionary highlight adds the word with one tap ([OneTapAddWord]). When
 * that is refused - the personal dictionary is not loaded yet, or the word is already in it - the
 * keyboard used to hand the word to Android's own add-word dialog, which wears the system's
 * colours, not the keyboard's (owner request 2026-09-26). This is that fallback, themed like every
 * other BBKB dialog: the word, editable, and Add / Cancel. Add goes to the personal dictionary
 * first and to the system user dictionary when that still refuses, so the word is never lost.
 */
class AddWordDialog : Activity() {

    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val word = intent.getStringExtra(EXTRA_WORD)?.trim().orEmpty()
        if (word.isEmpty()) {
            finish()
            return
        }
        val locale = intent.getStringExtra(EXTRA_LOCALE)?.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it.replace('_', '-')) }

        val themed = BbkbDialogs.themedContext(this)
        val field = EditText(themed).apply {
            setText(word)
            setSelection(word.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
        val container = FrameLayout(themed).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        dialog = AlertDialog.Builder(themed)
            .setTitle(R.string.add_word_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.add) { _, _ -> add(field.text.toString().trim(), locale) }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .setOnDismissListener { finish() }
            .create()
            .also { it.show() }
    }

    private fun add(word: String, locale: Locale?) {
        if (word.isEmpty()) return
        val added = OneTapAddWord.add(DictionaryManager.getInstance(), word, locale) || addToSystemDictionary(word, locale)
        Toast.makeText(
            applicationContext,
            if (added) getString(R.string.languages_hub_added, word) else getString(R.string.languages_hub_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun addToSystemDictionary(word: String, locale: Locale?): Boolean = try {
        UserDictionary.Words.addWord(this, word, SYSTEM_FREQUENCY, null, locale)
        true
    } catch (e: Exception) {
        Logger.errorWithException(TAG, e, "system user dictionary refused $word: ")
        false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finish()
    }

    override fun onDestroy() {
        dialog?.takeIf { it.isShowing }?.dismiss()
        dialog = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AddWordDialog"
        private const val EXTRA_WORD = "word"
        private const val EXTRA_LOCALE = "locale"
        /** What Android's own add-word dialog stores. */
        private const val SYSTEM_FREQUENCY = 250

        @JvmStatic
        fun start(context: Context, word: String, locale: Locale?) {
            context.startActivity(
                Intent(context, AddWordDialog::class.java)
                    .putExtra(EXTRA_WORD, word)
                    .putExtra(EXTRA_LOCALE, locale?.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
