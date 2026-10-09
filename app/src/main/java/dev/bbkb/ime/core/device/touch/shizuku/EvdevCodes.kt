package dev.bbkb.ime.core.device.touch.shizuku

/**
 * The few linux/input-event-codes.h values the Shizuku touch engine reads. Kernel ABI: these
 * numbers never change, so they are spelled out rather than pulled from a header.
 */
object EvdevCodes {
    const val EV_SYN = 0x00
    const val EV_KEY = 0x01
    const val EV_ABS = 0x03

    /** End of one frame: every axis/key change since the previous SYN_REPORT happened together. */
    const val SYN_REPORT = 0
    /** Protocol A contact separator. Single-contact decoding ignores it. */
    const val SYN_MT_REPORT = 2
    /** The kernel's buffer overflowed and events were lost; resync at the next SYN_REPORT. */
    const val SYN_DROPPED = 3

    const val BTN_TOUCH = 0x14a

    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_MT_SLOT = 0x2f
    const val ABS_MT_TOUCH_MAJOR = 0x30
    const val ABS_MT_TOUCH_MINOR = 0x31
    const val ABS_MT_POSITION_X = 0x35
    const val ABS_MT_POSITION_Y = 0x36
    const val ABS_MT_TRACKING_ID = 0x39

    /** errno values the native reader returns (negated). Bionic/Linux numbering. */
    const val ENODEV = 19
    const val ESTALE = 116
}
