/* bbkbevdev.c — raw evdev access for the Shizuku touch reader (libbbkbevdev.so).
 *
 * Loaded ONLY by dev.bbkb.ime.core.device.touch.shizuku.EvdevUserService, the Shizuku
 * UserService that runs as shell (adb-started Shizuku) or root in its own "<applicationId>:evdev"
 * process. The IME process never loads it, and this process never loads the engine blob. libc
 * only.
 *
 * Everything that can be pure Kotlin is (record parsing, packing, name matching, probe
 * decoding: see EvdevPacking.kt, TouchDeviceMatcher.kt, TouchDeviceInfo.kt). This file only does
 * what needs ioctl() and poll():
 *   nativeProbe    EVIOCGNAME, EVIOCGBIT + EVIOCGABS for ABS_X/Y, ABS_MT_POSITION_X/Y and
 *                  ABS_MT_SLOT, EVIOCGBIT for ABS_MT_TRACKING_ID and BTN_TOUCH
 *   nativeOpen     open, re-check the name on the open fd (the node may have been reassigned
 *                  since the probe), EVIOCSCLOCKID(CLOCK_MONOTONIC), and a wake pipe
 *   nativeRead     poll() on the device and the wake pipe, then read() whole input_event records
 *   nativeSetGrab  EVIOCGRAB on/off
 *   nativeWake     make the blocked (or next) nativeRead return 0, for good
 *   nativeClose    release the grab, close everything
 * Failures come back as a negated errno; -ENODEV means the device node is gone.
 *
 * Handles are small ints (1..MAX_HANDLES), not pointers: a tagged heap pointer can be negative
 * as a jlong, which would read as an error. Lifetime contract (kept by EvdevUserService): only
 * the reader thread calls nativeRead and then nativeClose; nativeWake/nativeSetGrab may come from
 * other threads but never after nativeClose.
 */
#define _GNU_SOURCE   /* pipe2 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <pthread.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

/* Mirrors EvdevProbeLayout in TouchDeviceInfo.kt. */
enum {
    PROBE_STATUS = 0,
    PROBE_ABS_X_MIN, PROBE_ABS_X_MAX,
    PROBE_ABS_Y_MIN, PROBE_ABS_Y_MAX,
    PROBE_MT_X_MIN, PROBE_MT_X_MAX,
    PROBE_MT_Y_MIN, PROBE_MT_Y_MAX,
    PROBE_MT_SLOT_MAX,
    PROBE_SIZE
};
#define FLAG_ABS_X          (1 << 0)
#define FLAG_ABS_Y          (1 << 1)
#define FLAG_MT_X           (1 << 2)
#define FLAG_MT_Y           (1 << 3)
#define FLAG_BTN_TOUCH      (1 << 4)
#define FLAG_MT_TRACKING_ID (1 << 5)
#define FLAG_MT_SLOT        (1 << 6)

#define MAX_HANDLES 4
#define NAME_CAP 256
#define READ_MAX_BYTES (sizeof(struct input_event) * 128)

#define BITS_PER_ULONG (sizeof(unsigned long) * 8)
#define NULONGS(n) (((n) + BITS_PER_ULONG - 1) / BITS_PER_ULONG)

typedef struct {
    int in_use;
    int fd;
    int wake_rd;
    int wake_wr;
    int monotonic;
} evdev_handle;

static evdev_handle g_handles[MAX_HANDLES];   /* handle n lives at g_handles[n - 1] */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static int test_bit(unsigned bit, const unsigned long* bits) {
    return (int)((bits[bit / BITS_PER_ULONG] >> (bit % BITS_PER_ULONG)) & 1UL);
}

/* EVIOCGNAME into buf, made safe for NewStringUTF (which takes modified UTF-8): anything
 * outside printable ASCII becomes '?'. EvdevUserService compares names in this form. */
static int read_name(int fd, char* buf, size_t cap) {
    memset(buf, 0, cap);
    if (ioctl(fd, EVIOCGNAME(cap - 1), buf) < 0) return -errno;
    for (char* p = buf; *p; p++) {
        unsigned char c = (unsigned char)*p;
        if (c < 0x20 || c > 0x7e) *p = '?';
    }
    return 0;
}

static void read_abs(int fd, unsigned code, const unsigned long* abs_bits,
                     jint* out, int min_index, int flag, int* flags) {
    struct input_absinfo info;
    if (!test_bit(code, abs_bits)) return;
    if (ioctl(fd, EVIOCGABS(code), &info) < 0) return;
    out[min_index] = info.minimum;
    out[min_index + 1] = info.maximum;
    *flags |= flag;
}

/* Copies a live handle out under the lock. 0 if `handle` is not open. */
static int get_handle(jint handle, evdev_handle* out) {
    int ok = 0;
    if (handle < 1 || handle > MAX_HANDLES) return 0;
    pthread_mutex_lock(&g_lock);
    if (g_handles[handle - 1].in_use) {
        *out = g_handles[handle - 1];
        ok = 1;
    }
    pthread_mutex_unlock(&g_lock);
    return ok;
}

JNIEXPORT jint JNICALL
Java_dev_bbkb_ime_core_device_touch_shizuku_EvdevNative_nativeRecordSize(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    return (jint)sizeof(struct input_event);
}

JNIEXPORT jstring JNICALL
Java_dev_bbkb_ime_core_device_touch_shizuku_EvdevNative_nativeProbe(JNIEnv* env, jclass cls,
                                                                    jstring jpath, jintArray jout) {
    (void)cls;
    jint out[PROBE_SIZE];
    char name[NAME_CAP];
    unsigned long abs_bits[NULONGS(ABS_CNT)];
    unsigned long key_bits[NULONGS(KEY_CNT)];
    jstring result = NULL;
    int flags = 0;
    int fd;
    const char* path;

    memset(out, 0, sizeof(out));
    if (!jpath || !jout || (*env)->GetArrayLength(env, jout) < PROBE_SIZE) return NULL;
    path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return NULL;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) out[PROBE_STATUS] = -errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (fd >= 0) {
        int rc = read_name(fd, name, sizeof(name));
        if (rc < 0) {
            out[PROBE_STATUS] = rc;
        } else {
            memset(abs_bits, 0, sizeof(abs_bits));
            memset(key_bits, 0, sizeof(key_bits));
            if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) >= 0) {
                read_abs(fd, ABS_X, abs_bits, out, PROBE_ABS_X_MIN, FLAG_ABS_X, &flags);
                read_abs(fd, ABS_Y, abs_bits, out, PROBE_ABS_Y_MIN, FLAG_ABS_Y, &flags);
                read_abs(fd, ABS_MT_POSITION_X, abs_bits, out, PROBE_MT_X_MIN, FLAG_MT_X, &flags);
                read_abs(fd, ABS_MT_POSITION_Y, abs_bits, out, PROBE_MT_Y_MIN, FLAG_MT_Y, &flags);
                if (test_bit(ABS_MT_SLOT, abs_bits)) {
                    struct input_absinfo slot;
                    if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &slot) >= 0) {
                        out[PROBE_MT_SLOT_MAX] = slot.maximum;
                        flags |= FLAG_MT_SLOT;
                    }
                }
                if (test_bit(ABS_MT_TRACKING_ID, abs_bits)) flags |= FLAG_MT_TRACKING_ID;
            }
            if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits) >= 0 &&
                test_bit(BTN_TOUCH, key_bits)) {
                flags |= FLAG_BTN_TOUCH;
            }
            out[PROBE_STATUS] = flags;
            result = (*env)->NewStringUTF(env, name);
        }
        close(fd);
    }
    (*env)->SetIntArrayRegion(env, jout, 0, PROBE_SIZE, out);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_bbkb_ime_core_device_touch_shizuku_EvdevNative_nativeOpen(JNIEnv* env, jclass cls,
                                                                   jstring jpath, jstring jexpected) {
    (void)cls;
    char name[NAME_CAP];
    int wake[2];
    int clock_id = CLOCK_MONOTONIC;
    int monotonic;
    int fd;
    int rc;
    const char* path;
    const char* expected;

    if (!jpath || !jexpected) return -EINVAL;
    path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -ENOMEM;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    rc = fd < 0 ? -errno : 0;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (rc < 0) return rc;

    expected = (*env)->GetStringUTFChars(env, jexpected, NULL);
    if (!expected) {
        close(fd);
        return -ENOMEM;
    }
    rc = read_name(fd, name, sizeof(name));
    if (rc == 0 && strcmp(name, expected) != 0) rc = -ESTALE;   /* node now belongs to another device */
    (*env)->ReleaseStringUTFChars(env, jexpected, expected);
    if (rc < 0) {
        close(fd);
        return rc;
    }

    /* Stamp events with CLOCK_MONOTONIC (the uptimeMillis clock) instead of the per-fd default
     * CLOCK_REALTIME. Kernels before 3.4 lack it; the Kotlin side then converts. */
    monotonic = ioctl(fd, EVIOCSCLOCKID, &clock_id) == 0;

    if (pipe2(wake, O_CLOEXEC | O_NONBLOCK) < 0) {
        rc = -errno;
        close(fd);
        return rc;
    }

    rc = -EMFILE;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_HANDLES; i++) {
        if (!g_handles[i].in_use) {
            g_handles[i].in_use = 1;
            g_handles[i].fd = fd;
            g_handles[i].wake_rd = wake[0];
            g_handles[i].wake_wr = wake[1];
            g_handles[i].monotonic = monotonic;
            rc = i + 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    if (rc < 0) {
        close(fd);
        close(wake[0]);
        close(wake[1]);
    }
    return rc;
}

JNIEXPORT jboolean JNICALL
Java_dev_bbkb_ime_core_device_touch_shizuku_EvdevNative_nativeIsMonotonic(JNIEnv* env, jclass cls,
                                                                          jint handle) {
    (void)env; (void)cls;
    evdev_handle h;
    return get_handle(handle, &h) && h.monotonic ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_bbkb_ime_core_device_touch_shizuku_EvdevNative_nativeSetGrab(JNIEnv* env, jclass cls,
                                                                      jint handle, jboolean grab) {
    (void)env; (void)cls;
    evdev_handle h;
    if (!get_handle(handle, &h)) return -EBADF;
    /* EVIOCGRAB takes its argument by value: non-zero grabs, zero releases. */
    return ioctl(h.fd, EVIOCGRAB, grab ? 1 : 0) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_dev_bbkb_ime_core_device_touch_shizuku_EvdevNative_nativeRead(JNIEnv* env, jclass cls,
                                                                   jint handle, jbyteArray jbuf) {
    (void)cls;
    unsigned char buf[READ_MAX_BYTES];
    evdev_handle h;
    size_t want;

    if (!jbuf || !get_handle(handle, &h)) return -EBADF;
    want = (size_t)(*env)->GetArrayLength(env, jbuf);
    if (want > sizeof(buf)) want = sizeof(buf);
    want -= want % sizeof(struct input_event);
    if (want == 0) return -EINVAL;

    for (;;) {
        struct pollfd fds[2];
        ssize_t got;
        fds[0].fd = h.fd;
        fds[0].events = POLLIN;
        fds[0].revents = 0;
        fds[1].fd = h.wake_rd;
        fds[1].events = POLLIN;
        fds[1].revents = 0;
        if (poll(fds, 2, -1) < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        /* The wake byte is never drained: once woken, every later read returns 0 too. */
        if (fds[1].revents) return 0;
        if (fds[0].revents & POLLIN) {
            got = read(h.fd, buf, want);
            if (got > 0) {
                (*env)->SetByteArrayRegion(env, jbuf, 0, (jsize)got, (const jbyte*)buf);
                return (jint)got;
            }
            if (got < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            return got < 0 ? -errno : -ENODEV;
        }
        /* evdev reports a removed device as POLLHUP | POLLERR. */
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) return -ENODEV;
    }
}

JNIEXPORT void JNICALL
Java_dev_bbkb_ime_core_device_touch_shizuku_EvdevNative_nativeWake(JNIEnv* env, jclass cls,
                                                                   jint handle) {
    (void)env; (void)cls;
    evdev_handle h;
    if (get_handle(handle, &h)) {
        ssize_t ignored = write(h.wake_wr, "w", 1);   /* EAGAIN = already woken */
        (void)ignored;
    }
}

JNIEXPORT void JNICALL
Java_dev_bbkb_ime_core_device_touch_shizuku_EvdevNative_nativeClose(JNIEnv* env, jclass cls,
                                                                    jint handle) {
    (void)env; (void)cls;
    evdev_handle h;
    int found = 0;
    if (handle < 1 || handle > MAX_HANDLES) return;
    pthread_mutex_lock(&g_lock);
    if (g_handles[handle - 1].in_use) {
        h = g_handles[handle - 1];
        memset(&g_handles[handle - 1], 0, sizeof(evdev_handle));
        found = 1;
    }
    pthread_mutex_unlock(&g_lock);
    if (!found) return;
    ioctl(h.fd, EVIOCGRAB, 0);   /* close() drops it too; say so explicitly. EINVAL if not held. */
    close(h.fd);
    close(h.wake_rd);
    close(h.wake_wr);
}
