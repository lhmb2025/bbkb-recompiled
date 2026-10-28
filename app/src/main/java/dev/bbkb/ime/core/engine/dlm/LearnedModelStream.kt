package dev.bbkb.ime.core.engine.dlm

/**
 * The engine's portable form of the learned model: the byte stream `ET9AWDLMExport` writes and
 * `ET9AWDLMImport` reads back (`NuanceSDK.exportDLM` / `importDLM`). Unlike the model file
 * (`alphadlm.bin`, two megabytes of memory-mapped structures with process pointers inside), the
 * stream is self-contained and checksummed, so it can be saved, carried to another phone and
 * read without the engine — which is what this object does: it reads the stream, so the app can
 * show each word's use count and validate a file before handing it to the engine, and it writes
 * changed use counts back into a stream the engine accepts.
 *
 * ## Layout (version 5, the one this engine writes; recovered from the engine's own reader and writer)
 *
 * All multi-byte values are big-endian.
 *
 * ```
 * u8   version (1..5; the engine refuses anything above 5)
 * u32  update counter (the engine bumps it on every applied event)
 * u16  category count, then per category:
 *      u16 id · 5 bytes float · u32 · 5 bytes float · u32 · u8 · u8 n · n×u16 name · u8 m · m×u16 label
 * u16  word count, then per word:
 *      u16 use count · u16 category · u16 language · u8 n · s8 quality · u8 flags · n×u16 symbols
 * u32  next-word record count, then per record:
 *      u8 · u32 · u16 k · k×(u16, u16)
 * u16  c · u16 · c×u32
 * u32  checksum of everything before it: h = h × 65599 + byte, 32-bit
 * then optional zero padding (the engine writes the stream into a buffer rounded up to a
 * kilobyte, and accepts the padding on import)
 * ```
 *
 * The use count is what the engine adds to on every use of a word and halves across the board
 * when any count would pass 65535, so it is a relative weight, not a lifetime total. Quality is
 * the record's status, a small signed number: 15 for a word the user added on purpose, 3 (or 2)
 * for a dictionary or contact word the model only tracks, 0 (or 1) for a word learned from
 * typing, −1 to −11 for a word typed once and not yet kept (the engine lowers it each time the
 * word is offered and passed over, and drops the word at −11; typing it again raises it to 0),
 * and −15 for a word the user deleted (kept so it stays suppressed) or a record due for cleaning.
 * The engine's own word list shows only qualities 0, 1 and 4 and up. The language is the engine's two-byte language id (0x0B09 for US English, 0x0119 for
 * Russian: the primary language in the low byte). The category is 0 for an ordinary learned word;
 * words in a category above 255 are neither counted up nor aged by the engine.
 */
object LearnedModelStream {

    /** The only version this reader understands: the one the engine writes. */
    const val VERSION: Int = 5

    /** Larger than any model the engine's two-megabyte file can hold. */
    const val MAX_BYTES: Int = 8 * 1024 * 1024
    const val MAX_WORD_LENGTH: Int = 64
    private const val MAX_CATEGORY_NAME: Int = 32
    private const val MAX_CATEGORY_LABEL: Int = 64
    private const val CHECKSUM_BYTES = 4

    /** The data is not a learned model this app can read; [message] says why. */
    class InvalidModelException(message: String) : Exception(message)

    /** One learned-word category, kept opaque beyond its id and names. */
    class Category(val id: Int, val name: String, val label: String)

    /**
     * One learned word. [count] is the engine's use count, [quality] its status (see above),
     * [flags] the record's flag byte; [countOffset] is where the record's count sits in the
     * stream, for [withChanges].
     */
    class Word(
        val text: String,
        val count: Int,
        val language: Int,
        val category: Int,
        val quality: Int,
        val flags: Int,
        internal val countOffset: Int,
    )

    /**
     * A parsed stream. [bytes] is exactly the stream the engine wrote — through the checksum,
     * without padding — so it can be handed back to the engine as it is.
     */
    class Model(
        val version: Int,
        val updateCount: Long,
        val categories: List<Category>,
        val words: List<Word>,
        val nextWordRecords: Int,
        val bytes: ByteArray,
    )

    /** The engine's checksum of `bytes[0, end)`. */
    internal fun checksum(bytes: ByteArray, end: Int): Int {
        var h = 0
        for (i in 0 until end) h = h * 65599 + (bytes[i].toInt() and 0xff)
        return h
    }

    private class Reader(val bytes: ByteArray) {
        var pos = 0
        fun need(n: Int, what: String) {
            if (n < 0 || pos > bytes.size - n) throw InvalidModelException("truncated at $what (offset $pos)")
        }
        fun u8(what: String): Int { need(1, what); return bytes[pos++].toInt() and 0xff }
        fun s8(what: String): Int { need(1, what); return bytes[pos++].toInt() }
        fun u16(what: String): Int { need(2, what); val v = ((bytes[pos].toInt() and 0xff) shl 8) or (bytes[pos + 1].toInt() and 0xff); pos += 2; return v }
        fun u32(what: String): Long {
            need(4, what)
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (bytes[pos + i].toLong() and 0xff)
            pos += 4
            return v
        }
        fun skip(n: Int, what: String) { need(n, what); pos += n }
        fun symbols(n: Int, what: String): String {
            need(2 * n, what)
            val chars = CharArray(n) { i -> (((bytes[pos + 2 * i].toInt() and 0xff) shl 8) or (bytes[pos + 2 * i + 1].toInt() and 0xff)).toChar() }
            pos += 2 * n
            return String(chars)
        }
    }

    /**
     * Reads [bytes] as the engine's export stream. Throws [InvalidModelException] when it is not
     * one: wrong version, truncated, a count the engine itself would refuse, a checksum that does
     * not match, or data after the checksum that is not zero padding.
     */
    fun parse(bytes: ByteArray): Model {
        if (bytes.size > MAX_BYTES) throw InvalidModelException("too large to be a learned model (${bytes.size} bytes)")
        val r = Reader(bytes)
        val version = r.u8("version")
        if (version != VERSION) {
            throw InvalidModelException(
                if (version in 1 until VERSION) "learned model version $version is older than this app reads"
                else "not a learned model, or one from a newer engine (version $version)"
            )
        }
        val updateCount = r.u32("update counter")
        val categoryCount = r.u16("category count")
        val categories = ArrayList<Category>(categoryCount)
        repeat(categoryCount) {
            val id = r.u16("category id")
            r.skip(5, "category"); r.u32("category"); r.skip(5, "category"); r.u32("category"); r.u8("category")
            val n = r.u8("category name length")
            if (n > MAX_CATEGORY_NAME) throw InvalidModelException("category name of $n symbols")
            val name = r.symbols(n, "category name")
            val m = r.u8("category label length")
            if (m > MAX_CATEGORY_LABEL) throw InvalidModelException("category label of $m symbols")
            val label = r.symbols(m, "category label")
            categories += Category(id, name, label)
        }
        val wordCount = r.u16("word count")
        val words = ArrayList<Word>(wordCount)
        repeat(wordCount) {
            val countOffset = r.pos
            val count = r.u16("use count")
            val category = r.u16("category")
            val language = r.u16("language")
            val n = r.u8("word length")
            if (n > MAX_WORD_LENGTH) throw InvalidModelException("word of $n symbols")
            val quality = r.s8("quality")
            val flags = r.u8("flags")
            val text = r.symbols(n, "word")
            words += Word(text, count, language, category, quality, flags, countOffset)
        }
        val recordCount = r.u32("next-word record count")
        if (recordCount > Int.MAX_VALUE) throw InvalidModelException("next-word record count $recordCount")
        for (i in 0 until recordCount.toInt()) {
            r.u8("next-word record"); r.u32("next-word record")
            val k = r.u16("next-word record")
            r.skip(4 * k, "next-word pairs")
        }
        val tail = r.u16("tail count")
        r.u16("tail")
        r.skip(4 * tail, "tail")
        val payloadEnd = r.pos
        val stored = r.u32("checksum")
        if (stored.toInt() != checksum(bytes, payloadEnd)) throw InvalidModelException("checksum does not match")
        for (i in r.pos until bytes.size) {
            if (bytes[i].toInt() != 0) throw InvalidModelException("data after the checksum (offset $i)")
        }
        return Model(version, updateCount, categories, words, recordCount.toInt(), bytes.copyOf(payloadEnd + CHECKSUM_BYTES))
    }

    /** Where a word record's quality byte sits after its count: count, category, language, length. */
    private const val QUALITY_OFFSET = 7

    /**
     * [model]'s stream with the use counts in [counts] (word index in [Model.words] to new count,
     * 1..65535) and the qualities in [qualities] (word index to new quality, −15..15) written in
     * place and the checksum recomputed. Everything else is byte-identical, so the engine reads
     * it back as the same model with those records changed.
     */
    fun withChanges(model: Model, counts: Map<Int, Int> = emptyMap(), qualities: Map<Int, Int> = emptyMap()): ByteArray {
        val out = model.bytes.copyOf()
        for ((index, quality) in qualities) {
            val word = model.words.getOrNull(index) ?: throw IllegalArgumentException("no word $index")
            require(quality in -15..15) { "quality $quality for ${word.text}" }
            out[word.countOffset + QUALITY_OFFSET] = quality.toByte()
        }
        for ((index, count) in counts) {
            val word = model.words.getOrNull(index) ?: throw IllegalArgumentException("no word $index")
            require(count in 1..0xffff) { "count $count for ${word.text}" }
            out[word.countOffset] = (count ushr 8).toByte()
            out[word.countOffset + 1] = count.toByte()
        }
        val end = out.size - CHECKSUM_BYTES
        val sum = checksum(out, end)
        out[end] = (sum ushr 24).toByte()
        out[end + 1] = (sum ushr 16).toByte()
        out[end + 2] = (sum ushr 8).toByte()
        out[end + 3] = sum.toByte()
        return out
    }
}
