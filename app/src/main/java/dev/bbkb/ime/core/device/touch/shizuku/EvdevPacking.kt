package dev.bbkb.ime.core.device.touch.shizuku

/**
 * The wire format of [IEvdevCallback.onEvents]: every evdev record as two longs.
 *
 *  - word 0: the event's timestamp in CLOCK_MONOTONIC microseconds (see [TouchStreamDecoder]
 *    for why that clock, and how it becomes an uptimeMillis-based time);
 *  - word 1: `type` in bits 63..48, `code` in bits 47..32, `value` (two's complement) in 31..0.
 *
 * A long[] crosses binder as one flat array with no per-event objects, and both halves of the
 * format are pure Kotlin so they are tested on the JVM rather than in native code.
 */
object EvdevPacking {
    const val WORDS_PER_EVENT = 2

    @JvmStatic
    fun word(type: Int, code: Int, value: Int): Long =
        ((type.toLong() and 0xffff) shl 48) or
            ((code.toLong() and 0xffff) shl 32) or
            (value.toLong() and 0xffff_ffffL)

    @JvmStatic fun type(word: Long): Int = ((word ushr 48) and 0xffff).toInt()
    @JvmStatic fun code(word: Long): Int = ((word ushr 32) and 0xffff).toInt()
    @JvmStatic fun value(word: Long): Int = word.toInt()

    @JvmStatic fun eventCount(packed: LongArray): Int = packed.size / WORDS_PER_EVENT
}

/**
 * Collects packed records on the reader thread and hands them out a whole frame at a time.
 *
 * [takeCompleteFrames] returns everything up to and including the most recent SYN_REPORT, so
 * a batch never ends mid-frame and the tail of a read that stopped mid-frame waits for the next
 * read. If a device streams more than [maxPendingEvents] records without a SYN_REPORT (broken
 * firmware), the backlog is flushed anyway: [TouchStreamDecoder] is stream-oriented and copes
 * with a frame split across batches, while an unbounded buffer would not be coped with at all.
 */
class PackedEventBuffer(initialEvents: Int = 64, private val maxPendingEvents: Int = 1024) {

    private var words = LongArray(initialEvents.coerceAtLeast(1) * EvdevPacking.WORDS_PER_EVENT)
    private var size = 0
    /** Index just past the last SYN_REPORT's words; 0 when no complete frame is buffered. */
    private var frameEnd = 0

    val pendingEvents: Int get() = size / EvdevPacking.WORDS_PER_EVENT

    fun add(timeUs: Long, type: Int, code: Int, value: Int) {
        if (size + EvdevPacking.WORDS_PER_EVENT > words.size) words = words.copyOf(words.size * 2)
        words[size] = timeUs
        words[size + 1] = EvdevPacking.word(type, code, value)
        size += EvdevPacking.WORDS_PER_EVENT
        if (type == EvdevCodes.EV_SYN && code == EvdevCodes.SYN_REPORT) frameEnd = size
    }

    /** Removes and returns every complete frame, or null when there is none to send yet. */
    fun takeCompleteFrames(): LongArray? {
        val end = when {
            frameEnd > 0 -> frameEnd
            pendingEvents >= maxPendingEvents -> size
            else -> return null
        }
        val out = words.copyOf(end)
        System.arraycopy(words, end, words, 0, size - end)
        size -= end
        frameEnd = 0
        return out
    }

    fun clear() {
        size = 0
        frameEnd = 0
    }
}

/**
 * Turns the bytes read() returned from an evdev node into packed records.
 *
 * `struct input_event` is `{ struct timeval time; __u16 type; __u16 code; __s32 value; }`: 24
 * bytes on arm64 (two 8-byte longs of time), 16 on a 32-bit ABI. The native reader reports
 * `sizeof` for the ABI it was built for and this parses either layout, little-endian.
 */
object EvdevRecords {
    const val RECORD_SIZE_LP64 = 24
    const val RECORD_SIZE_ILP32 = 16

    /**
     * Parses `length` bytes of `raw` into [sink], adding `timeOffsetUs` to every timestamp.
     * Returns the number of records parsed; a trailing partial record (read() never returns
     * one) is ignored.
     */
    @JvmStatic
    fun parse(raw: ByteArray, length: Int, recordSize: Int, timeOffsetUs: Long, sink: PackedEventBuffer): Int {
        require(recordSize == RECORD_SIZE_LP64 || recordSize == RECORD_SIZE_ILP32) {
            "unsupported input_event size $recordSize"
        }
        val count = length.coerceAtMost(raw.size) / recordSize
        var off = 0
        repeat(count) {
            val sec: Long
            val usec: Long
            val rest: Int
            if (recordSize == RECORD_SIZE_LP64) {
                sec = le64(raw, off)
                usec = le64(raw, off + 8)
                rest = off + 16
            } else {
                sec = le32(raw, off).toLong()
                usec = le32(raw, off + 4).toLong()
                rest = off + 8
            }
            sink.add(
                sec * 1_000_000L + usec + timeOffsetUs,
                le16(raw, rest),
                le16(raw, rest + 2),
                le32(raw, rest + 4),
            )
            off += recordSize
        }
        return count
    }

    /**
     * How far CLOCK_MONOTONIC is ahead of CLOCK_REALTIME, in microseconds, from one reading of
     * each. Only used when the kernel refused EVIOCSCLOCKID and the node stamps events with
     * wall-clock time; the result shifts with any wall-clock step, so it is re-sampled per read.
     */
    @JvmStatic
    fun realtimeToMonotonicOffsetUs(monotonicNanos: Long, realtimeMillis: Long): Long =
        monotonicNanos / 1000L - realtimeMillis * 1000L

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)

    private fun le64(b: ByteArray, o: Int): Long =
        (le32(b, o).toLong() and 0xffff_ffffL) or (le32(b, o + 4).toLong() shl 32)
}
