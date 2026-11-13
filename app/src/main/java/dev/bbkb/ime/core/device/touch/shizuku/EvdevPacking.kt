package dev.bbkb.ime.core.device.touch.shizuku

/**
 * Wire format of [IEvdevCallback.onEvents], two longs per evdev record (one flat binder array):
 *  - word 0: timestamp, CLOCK_MONOTONIC microseconds;
 *  - word 1: `type` in bits 63..48, `code` in 47..32, `value` (two's complement) in 31..0.
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
 * Collects packed records on the reader thread and hands out whole frames (up to the last
 * SYN_REPORT). Past [maxPendingEvents] without a SYN_REPORT (broken firmware) it flushes anyway;
 * [TouchStreamDecoder] copes with split frames, an unbounded buffer would not be.
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
 * Parses evdev read() bytes into packed records. `struct input_event` is
 * `{ struct timeval time; __u16 type; __u16 code; __s32 value; }`, little-endian: 24 bytes on
 * arm64, 16 on a 32-bit ABI; the native reader reports which.
 */
object EvdevRecords {
    const val RECORD_SIZE_LP64 = 24
    const val RECORD_SIZE_ILP32 = 16

    /** Parses into [sink], adding `timeOffsetUs` to each stamp; returns the record count (partial tail ignored). */
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
     * CLOCK_MONOTONIC minus CLOCK_REALTIME in microseconds, for a kernel that refused EVIOCSCLOCKID.
     * Wall-clock steps shift it, so it is re-sampled per read.
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
