package dev.bbkb.ime.core.device.touch.shizuku

import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_POSITION_X
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_POSITION_Y
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.ABS_MT_TRACKING_ID
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.BTN_TOUCH
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.EV_ABS
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.EV_KEY
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.EV_SYN
import dev.bbkb.ime.core.device.touch.shizuku.EvdevCodes.SYN_REPORT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The reader-to-IME wire format, from the raw bytes read() returns, through the packed long[]
 * that crosses binder, back to type/code/value, and on into the decoder.
 */
class EvdevPackingTest {

    private data class Rec(val sec: Long, val usec: Long, val type: Int, val code: Int, val value: Int)

    /** Lays records out as arm64 `struct input_event` (24 bytes) or the 32-bit one (16 bytes). */
    private fun raw(records: List<Rec>, recordSize: Int): ByteArray {
        val buf = ByteBuffer.allocate(records.size * recordSize).order(ByteOrder.LITTLE_ENDIAN)
        records.forEach {
            if (recordSize == EvdevRecords.RECORD_SIZE_LP64) {
                buf.putLong(it.sec).putLong(it.usec)
            } else {
                buf.putInt(it.sec.toInt()).putInt(it.usec.toInt())
            }
            buf.putShort(it.type.toShort()).putShort(it.code.toShort()).putInt(it.value)
        }
        return buf.array()
    }

    private fun unpack(packed: LongArray): List<Rec> = (packed.indices step EvdevPacking.WORDS_PER_EVENT).map {
        val time = packed[it]
        val word = packed[it + 1]
        Rec(time / 1_000_000L, time % 1_000_000L, EvdevPacking.type(word), EvdevPacking.code(word), EvdevPacking.value(word))
    }

    @Test
    fun typeCodeAndValueRoundTripThroughOneWord() {
        val cases = listOf(
            Triple(EV_ABS, ABS_MT_TRACKING_ID, -1),
            Triple(EV_ABS, ABS_MT_POSITION_X, 1440),
            Triple(EV_KEY, BTN_TOUCH, 1),
            Triple(EV_SYN, SYN_REPORT, 0),
            Triple(0xffff, 0xffff, Int.MIN_VALUE),
            Triple(0, 0, Int.MAX_VALUE),
        )
        for ((type, code, value) in cases) {
            val word = EvdevPacking.word(type, code, value)
            assertEquals(type, EvdevPacking.type(word))
            assertEquals(code, EvdevPacking.code(word))
            assertEquals(value, EvdevPacking.value(word))
        }
    }

    @Test
    fun arm64RecordsParseIntoPackedWordsAndBack() {
        val records = listOf(
            Rec(5_000, 123_456, EV_ABS, ABS_MT_TRACKING_ID, 42),
            Rec(5_000, 123_456, EV_ABS, ABS_MT_POSITION_X, 1439),
            Rec(5_000, 123_456, EV_ABS, ABS_MT_POSITION_Y, 0),
            Rec(5_000, 123_456, EV_KEY, BTN_TOUCH, 1),
            Rec(5_000, 123_456, EV_SYN, SYN_REPORT, 0),
            Rec(5_000, 999_999, EV_ABS, ABS_MT_TRACKING_ID, -1),
            Rec(5_001, 0, EV_SYN, SYN_REPORT, 0),
        )
        val bytes = raw(records, EvdevRecords.RECORD_SIZE_LP64)
        val sink = PackedEventBuffer(initialEvents = 2)   // forces growth

        assertEquals(records.size, EvdevRecords.parse(bytes, bytes.size, 24, 0L, sink))
        val packed = sink.takeCompleteFrames()!!
        assertEquals(records.size, EvdevPacking.eventCount(packed))
        assertEquals(records, unpack(packed))
    }

    @Test
    fun thirtyTwoBitRecordsParseToo() {
        val records = listOf(Rec(77, 500, EV_ABS, ABS_MT_TRACKING_ID, -1), Rec(77, 500, EV_SYN, SYN_REPORT, 0))
        val bytes = raw(records, EvdevRecords.RECORD_SIZE_ILP32)
        val sink = PackedEventBuffer()
        EvdevRecords.parse(bytes, bytes.size, 16, 0L, sink)
        assertEquals(records, unpack(sink.takeCompleteFrames()!!))
    }

    @Test
    fun theTimeOffsetIsAddedAndATrailingPartialRecordIgnored() {
        val bytes = raw(listOf(Rec(1, 0, EV_SYN, SYN_REPORT, 0)), 24) + ByteArray(10)
        val sink = PackedEventBuffer()
        assertEquals(1, EvdevRecords.parse(bytes, bytes.size, 24, 2_500L, sink))
        assertEquals(1_002_500L, sink.takeCompleteFrames()!![0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownRecordSizeIsRefused() {
        EvdevRecords.parse(ByteArray(40), 40, 20, 0L, PackedEventBuffer())
    }

    @Test
    fun realtimeToMonotonicOffsetIsTheDifferenceInMicroseconds() {
        // monotonic 10 s, wall clock 1,700,000,000 s: events stamped in wall time move back by the gap.
        val offset = EvdevRecords.realtimeToMonotonicOffsetUs(10_000_000_000L, 1_700_000_000_000L)
        assertEquals(10_000_000L - 1_700_000_000_000_000L, offset)
        assertEquals(10_000_000L, 1_700_000_000_000_000L + offset)
    }

    @Test
    fun batchesEndOnTheLastSynReportAndKeepTheRest() {
        val buffer = PackedEventBuffer()
        buffer.add(1, EV_ABS, ABS_MT_POSITION_X, 1)
        assertNull(buffer.takeCompleteFrames())

        buffer.add(1, EV_SYN, SYN_REPORT, 0)
        buffer.add(2, EV_ABS, ABS_MT_POSITION_X, 2)
        buffer.add(2, EV_SYN, SYN_REPORT, 0)
        buffer.add(3, EV_ABS, ABS_MT_POSITION_X, 3)   // the start of a frame still being read

        val batch = buffer.takeCompleteFrames()!!
        assertEquals(4, EvdevPacking.eventCount(batch))
        assertEquals(SYN_REPORT, EvdevPacking.code(batch[batch.size - 1]))
        assertEquals(EV_SYN, EvdevPacking.type(batch[batch.size - 1]))
        assertEquals(1, buffer.pendingEvents)

        buffer.add(3, EV_SYN, SYN_REPORT, 0)
        val next = buffer.takeCompleteFrames()!!
        assertArrayEquals(
            longArrayOf(3, EvdevPacking.word(EV_ABS, ABS_MT_POSITION_X, 3), 3, EvdevPacking.word(EV_SYN, SYN_REPORT, 0)),
            next,
        )
        assertEquals(0, buffer.pendingEvents)
    }

    @Test
    fun aRunawayFrameIsFlushedAtTheCap() {
        val buffer = PackedEventBuffer(initialEvents = 4, maxPendingEvents = 8)
        repeat(7) { buffer.add(it.toLong(), EV_ABS, ABS_MT_POSITION_X, it) }
        assertNull(buffer.takeCompleteFrames())
        buffer.add(7, EV_ABS, ABS_MT_POSITION_X, 7)
        assertEquals(8, EvdevPacking.eventCount(buffer.takeCompleteFrames()!!))
        assertEquals(0, buffer.pendingEvents)
    }

    @Test
    fun rawBytesThroughPackingIntoTheDecoder() {
        // The whole pipeline minus binder: read() bytes -> packed batch -> decoded touches.
        val records = listOf(
            Rec(100, 0, EV_ABS, ABS_MT_TRACKING_ID, 1),
            Rec(100, 0, EV_ABS, ABS_MT_POSITION_X, 720),
            Rec(100, 0, EV_ABS, ABS_MT_POSITION_Y, 360),
            Rec(100, 0, EV_KEY, BTN_TOUCH, 1),
            Rec(100, 0, EV_SYN, SYN_REPORT, 0),
            Rec(100, 8_000, EV_ABS, ABS_MT_POSITION_X, 800),
            Rec(100, 8_000, EV_SYN, SYN_REPORT, 0),
            Rec(100, 16_000, EV_ABS, ABS_MT_TRACKING_ID, -1),
            Rec(100, 16_000, EV_KEY, BTN_TOUCH, 0),
            Rec(100, 16_000, EV_SYN, SYN_REPORT, 0),
        )
        val bytes = raw(records, 24)
        val sink = PackedEventBuffer()
        EvdevRecords.parse(bytes, bytes.size, 24, 0L, sink)

        val device = TouchDeviceInfo("/dev/input/event6", "touchPad", mtX = AxisRange(0, 1440), mtY = AxisRange(0, 720))
        val touches = ArrayList<DecodedTouch>()
        TouchStreamDecoder(device).feed(sink.takeCompleteFrames()!!) { touches.add(it) }

        assertEquals(listOf(TouchAction.DOWN, TouchAction.MOVE, TouchAction.UP), touches.map { it.action })
        assertEquals(listOf(720, 800, 800), touches.map { it.x })
        assertEquals(listOf(100_000L, 100_008L, 100_016L), touches.map { it.eventTimeMs })
    }
}
