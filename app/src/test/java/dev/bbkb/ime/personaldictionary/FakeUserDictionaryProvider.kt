package dev.bbkb.ime.personaldictionary

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.UserDictionary

/**
 * An in-memory stand-in for `com.android.providers.userdictionary`, good enough for the
 * `AudWrapper` surface: insert (including through `applyBatch`), delete by the four selections
 * `AudWrapper.DictionaryWordSelectionArgument` can produce, and a full-table query.
 *
 * Every mutation notifies `content://user_dictionary/words`, which is what
 * `AudContentObserver` is registered on.
 */
class FakeUserDictionaryProvider : ContentProvider() {

    /** locale (null == "all languages"), shortcut (null == a plain word), word. */
    data class Row(val locale: String?, val shortcut: String?, val word: String)

    private val rows = mutableListOf<Row>()

    /** Set to make every insert fail the way an invisible provider does. */
    var failInserts = false

    fun snapshot(): List<Row> = synchronized(rows) { rows.toList() }

    fun seed(row: Row) {
        synchronized(rows) { rows.add(row) }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/vnd.google.userword"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (failInserts || values == null) return null
        val row = Row(values.getAsString("locale"), values.getAsString("shortcut"), values.getAsString("word"))
        val id: Int
        synchronized(rows) {
            rows.add(row)
            id = rows.size
        }
        notifyChange()
        return Uri.withAppendedPath(UserDictionary.Words.CONTENT_URI, id.toString())
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val removed: Int
        synchronized(rows) {
            val before = rows.size
            rows.removeAll { matches(it, selection, selectionArgs) }
            removed = before - rows.size
        }
        if (removed > 0) notifyChange()
        return removed
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = projection ?: arrayOf("locale", "shortcut", "word")
        val cursor = MatrixCursor(columns)
        synchronized(rows) {
            for (row in rows) {
                if (!matches(row, selection, selectionArgs)) continue
                cursor.addRow(columns.map { column ->
                    when (column) {
                        "locale" -> row.locale
                        "shortcut" -> row.shortcut
                        "word" -> row.word
                        else -> null
                    }
                })
            }
        }
        return cursor
    }

    private fun notifyChange() {
        context?.contentResolver?.notifyChange(UserDictionary.Words.CONTENT_URI, null)
    }

    /**
     * Only the selections `AudWrapper` builds, plus "everything" for a null selection - an
     * unrecognised selection is a test bug, not a row that silently fails to match.
     */
    private fun matches(row: Row, selection: String?, args: Array<out String>?): Boolean {
        if (selection == null) return true
        val a = args ?: emptyArray()
        return when (selection) {
            AudWrapperSelections.ALL_LOCALE_NO_SHORTCUT ->
                row.word == a[0] && row.locale == null && row.shortcut == null
            AudWrapperSelections.ALL_LOCALE_WITH_SHORTCUT ->
                row.word == a[0] && row.locale == null && row.shortcut == a[1]
            AudWrapperSelections.ONE_LOCALE_NO_SHORTCUT ->
                row.word == a[0] && row.locale == a[1] && row.shortcut == null
            AudWrapperSelections.ONE_LOCALE_WITH_SHORTCUT ->
                row.word == a[0] && row.locale == a[1] && row.shortcut == a[2]
            else -> throw IllegalArgumentException("unexpected selection: $selection")
        }
    }

    /** Mirrors of the AudWrapper constants, so a change to either side breaks a test loudly. */
    object AudWrapperSelections {
        const val ALL_LOCALE_NO_SHORTCUT = "word=? AND locale is null AND shortcut is null"
        const val ALL_LOCALE_WITH_SHORTCUT = "word=? AND locale is null AND shortcut=?"
        const val ONE_LOCALE_NO_SHORTCUT = "word=? AND locale=? AND shortcut is null"
        const val ONE_LOCALE_WITH_SHORTCUT = "word=? AND locale=? AND shortcut=?"
    }

    companion object {
        /** Unused UriMatcher kept out of the way; the provider serves a single table. */
        val MATCHER = UriMatcher(UriMatcher.NO_MATCH)
    }
}
