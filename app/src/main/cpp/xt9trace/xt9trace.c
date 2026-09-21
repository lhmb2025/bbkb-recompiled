/* xt9trace.c — in-app logcat dump of the ET9 trace-result region (no Frida, no root).
 *
 * Reads the region the engine's ProcessTrace fills, using statically-verified offsets
 * (see xt9kdb-scaffold/TRACE_RESULT_REGION.md / et9_context.h), and prints to logcat.
 * Runs in-process, so it reads the live context directly.
 *
 * Call from Java AFTER a swipe (right after the native touchEnd returns), passing
 * NuanceSDK.mNativeHandle. View with:  adb logcat -s XT9DUMP:I
 */
#define _GNU_SOURCE
#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <signal.h>
#include <ucontext.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <linux/perf_event.h>
#include <linux/hw_breakpoint.h>

#define TAG "XT9DUMP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* verified offsets */
#define OFF_TRACEOBJ 0x30
#define OFF_MAGIC    0x58
#define ET9_MAGIC    0x1428
#define RES_OFF      0x49000   /* result block = traceObj + 0x49000 (ProcessTrace base) */
#define TR_IDCTR     0x368     /* u32 running id counter (incremented per record)       */
#define TR_KIND      0x3a4     /* u32 kind written in first path (=3)                    */
#define TR_BLOCK     0x360     /* record array base, relative to RES                      */
#define TR_STRIDE    0x7568

static uint16_t ru16(const void* p, size_t o){ uint16_t v; memcpy(&v,(const char*)p+o,2); return v; }
static uint32_t ru32(const void* p, size_t o){ uint32_t v; memcpy(&v,(const char*)p+o,4); return v; }
static uint64_t ru64(const void* p, size_t o){ uint64_t v; memcpy(&v,(const char*)p+o,8); return v; }
static float    rf32(const void* p, size_t o){ float    v; memcpy(&v,(const char*)p+o,4); return v; }

/* ---- Native hardware watchpoint: catch whoever writes NKL+<off> (default 0x4c) --------------------
 * The descriptor field NKL+0x4c is written by a load-time routine that uses register-computed offsets,
 * so it can't be found by static grep. Instead we set a self-process HW write-watchpoint on the exact
 * address (perf_event_open PERF_TYPE_BREAKPOINT), then force the blob to REBUILD the NKL in place
 * (ET9KDB_InvalidateLoadedKdbInfo + ET9KDB_SetKdbNum, both exported), so the writer fires and our
 * SIGTRAP handler captures its PC + registers. dladdr() maps the PC to the blob's nearest symbol +
 * file offset so we can read the exact instruction/formula from the disassembly. No Frida, no root.
 * View: adb logcat -s XT9WP:I */
#define LOGW(...) __android_log_print(ANDROID_LOG_INFO, "XT9WP", __VA_ARGS__)

#define WP_MAX 4
static volatile sig_atomic_t g_wp_hit = 0;
static volatile int g_wp_fds[WP_MAX];
static volatile int g_wp_nfds = 0;
static uint64_t g_wp_pc, g_wp_sp, g_wp_regs[31], g_wp_addr;
static uint64_t g_wp_base = 0;      /* blob load base, for file_off */
static void*    g_wp_nkl = 0;
static void*    g_wp_target = 0;
static struct sigaction g_wp_old;

static void wp_handler(int sig, siginfo_t* si, void* uc) {
    (void)sig;
    /* CRITICAL: disable ALL watchpoints here so the faulting store re-executes WITHOUT re-triggering
     * (otherwise it retries, re-faults, and we livelock). ioctl is async-signal-safe. */
    for (int i = 0; i < g_wp_nfds; i++) if (g_wp_fds[i] >= 0) ioctl(g_wp_fds[i], PERF_EVENT_IOC_DISABLE, 0);
    if (g_wp_hit) return;                       /* capture the first write only */
    const ucontext_t* u = (const ucontext_t*)uc;
    g_wp_pc = u->uc_mcontext.pc;
    g_wp_sp = u->uc_mcontext.sp;
    g_wp_addr = si ? (uint64_t)(uintptr_t)si->si_addr : 0;
    for (int i = 0; i < 31; i++) g_wp_regs[i] = u->uc_mcontext.regs[i];
    g_wp_hit = 1;
}

/* open one HW write-watchpoint on target; returns fd or -1 */
static int wp_open(void* target) {
    struct perf_event_attr pe; memset(&pe, 0, sizeof pe);
    pe.type = PERF_TYPE_BREAKPOINT; pe.size = sizeof pe;
    pe.bp_type = HW_BREAKPOINT_W; pe.bp_addr = (uint64_t)(uintptr_t)target; pe.bp_len = HW_BREAKPOINT_LEN_4;
    pe.sample_period = 1; pe.precise_ip = 2; pe.exclude_kernel = 1; pe.exclude_hv = 1; pe.disabled = 1;
    int fd = (int)syscall(__NR_perf_event_open, &pe, 0, -1, -1, 0);
    if (fd < 0) return -1;
    fcntl(fd, F_SETFL, O_ASYNC);
    fcntl(fd, F_SETSIG, SIGTRAP);
    struct f_owner_ex ow; ow.type = F_OWNER_TID; ow.pid = (int)syscall(__NR_gettid);
    fcntl(fd, F_SETOWN_EX, &ow);
    ioctl(fd, PERF_EVENT_IOC_RESET, 0);
    ioctl(fd, PERF_EVENT_IOC_ENABLE, 0);
    return fd;
}

/* ARM the watchpoint on NKL+off and RETURN (leave armed). Java then triggers a genuine in-place re-parse
 * via loadKeyboardLayout (Load_XmlKDB rebuilds the NKL at ctx+0x60, same address), so the real DESCRIPTOR
 * COMPUTER — not memcpy — stores our watched byte. Call reportDescWatch() after the reload. */
JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Trace_armDescWatch(JNIEnv* env, jclass cls, jlong handle, jint off, jint fromBump) {
    (void)env; (void)cls;
    unsigned char* ctx = (unsigned char*)(intptr_t)handle;
    if (!ctx || ru16(ctx, OFF_MAGIC) != ET9_MAGIC) { LOGW("arm: bad handle"); return; }
    /* fromBump: watch the BUMP head (ctx+0x68) where a FRESH parse builds the NKL — the descriptor
     * computer only runs on a layout's first build (then it's cached). Else watch the active NKL (0x60). */
    unsigned char* nkl = *(unsigned char* const*)(ctx + (fromBump ? 0x68 : 0x60));
    if (!nkl) { LOGW("arm: NKL=null (fromBump=%d)", (int)fromBump); return; }
    g_wp_nkl = nkl; g_wp_target = nkl + (int)off;

    /* blob base for file_off (via a known exported symbol at file offset 0xba654) */
    void* blob = dlopen("libnative-lib.so", RTLD_NOLOAD | RTLD_NOW);
    if (!blob) blob = dlopen("libnative-lib.so", RTLD_NOW);
    void* setk = blob ? dlsym(blob, "ET9KDB_SetKdbNum") : 0;
    g_wp_base = setk ? (uint64_t)(uintptr_t)setk - 0xba654u : 0;

    struct sigaction sa; memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = wp_handler; sa.sa_flags = SA_SIGINFO; sigemptyset(&sa.sa_mask);
    sigaction(SIGTRAP, &sa, &g_wp_old);

    /* A fresh parse builds the NKL at bump ± one NKL size (0x3ef0), direction varies. Watch the whole
     * neighborhood at once (ARM64 has 4 HW watchpoints): base-0x3ef0, base, base+0x3ef0, base+0x7de0. */
    g_wp_hit = 0; g_wp_nfds = 0;
    const long SZ = 0x3ef0;
    long deltas4[WP_MAX] = { -SZ, 0, SZ, 2*SZ };
    for (int i = 0; i < WP_MAX; i++) {
        void* t = (unsigned char*)nkl + deltas4[i] + (int)off;
        int fd = wp_open(t);
        if (fd >= 0) g_wp_fds[g_wp_nfds++] = fd;
        else LOGW("arm: wp_open(%p) failed errno=%d (%s)", t, errno, strerror(errno));
    }
    if (g_wp_nfds == 0) { LOGW("arm: no watchpoints opened"); sigaction(SIGTRAP, &g_wp_old, 0); return; }
    LOGW("armed %d watchpoints around base=%p (off+%#x) bump@0x68=%p blobbase=%#lx",
         g_wp_nfds, nkl, (int)off, *(void* const*)(ctx + 0x68), (unsigned long)g_wp_base);
}

/* Disarm and report whoever wrote the watched byte during the reparse (the descriptor computer). */
JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Trace_reportDescWatch(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    unsigned char* ctx = (unsigned char*)(intptr_t)handle;
    if (ctx && ru16(ctx, OFF_MAGIC) == ET9_MAGIC)
        LOGW("post-reparse: NKL@0x60=%p bump@0x68=%p (armed NKL=%p target=%p)",
             *(void* const*)(ctx + 0x60), *(void* const*)(ctx + 0x68), g_wp_nkl, g_wp_target);
    if (g_wp_nfds <= 0) { LOGW("report: not armed"); return; }
    for (int i = 0; i < g_wp_nfds; i++) if (g_wp_fds[i] >= 0) { ioctl(g_wp_fds[i], PERF_EVENT_IOC_DISABLE, 0); close(g_wp_fds[i]); }
    g_wp_nfds = 0;
    sigaction(SIGTRAP, &g_wp_old, 0);
    if (!g_wp_hit) { LOGW("report: NO HIT (fresh parse didn't land in the watched neighborhood of %p)", g_wp_nkl); return; }
    LOGW("WRITER pc=%#llx  base=%#lx  file_off=%#lx  hit_addr=%#llx  sp=%#llx",
         (unsigned long long)g_wp_pc, (unsigned long)g_wp_base, (unsigned long)(g_wp_pc - g_wp_base),
         (unsigned long long)g_wp_addr, (unsigned long long)g_wp_sp);
    LOGW("regs x0-x7:  %016llx %016llx %016llx %016llx %016llx %016llx %016llx %016llx",
         (unsigned long long)g_wp_regs[0],(unsigned long long)g_wp_regs[1],(unsigned long long)g_wp_regs[2],
         (unsigned long long)g_wp_regs[3],(unsigned long long)g_wp_regs[4],(unsigned long long)g_wp_regs[5],
         (unsigned long long)g_wp_regs[6],(unsigned long long)g_wp_regs[7]);
    LOGW("regs x8-x15: %016llx %016llx %016llx %016llx %016llx %016llx %016llx %016llx",
         (unsigned long long)g_wp_regs[8],(unsigned long long)g_wp_regs[9],(unsigned long long)g_wp_regs[10],
         (unsigned long long)g_wp_regs[11],(unsigned long long)g_wp_regs[12],(unsigned long long)g_wp_regs[13],
         (unsigned long long)g_wp_regs[14],(unsigned long long)g_wp_regs[15]);
    LOGW("regs x16-x23:%016llx %016llx %016llx %016llx %016llx %016llx %016llx %016llx",
         (unsigned long long)g_wp_regs[16],(unsigned long long)g_wp_regs[17],(unsigned long long)g_wp_regs[18],
         (unsigned long long)g_wp_regs[19],(unsigned long long)g_wp_regs[20],(unsigned long long)g_wp_regs[21],
         (unsigned long long)g_wp_regs[22],(unsigned long long)g_wp_regs[23]);
    LOGW("regs x24-x30:%016llx %016llx %016llx %016llx %016llx %016llx %016llx  sp=%016llx",
         (unsigned long long)g_wp_regs[24],(unsigned long long)g_wp_regs[25],(unsigned long long)g_wp_regs[26],
         (unsigned long long)g_wp_regs[27],(unsigned long long)g_wp_regs[28],(unsigned long long)g_wp_regs[29],
         (unsigned long long)g_wp_regs[30],(unsigned long long)g_wp_sp);
}

/* S1 validation: dump the blob's finalized trace record so the owned byte-exact writer (kdb_trace.c
 * store_trace_record) can be diffed against it. Reads result = traceObj+0x49000, the header cursor/
 * counter, every slot's state (+0x44), and the first 0x70 bytes of the most-recently-finalized slot
 * (state==2, highest seqId). Read-only. Tag XT9REC. Call right after touchEnd. adb logcat -s XT9REC:I */
JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Trace_dumpTraceRec(JNIEnv* env, jclass cls, jlong handle, jstring jphase) {
    (void)cls;
    #define LOGR(...) __android_log_print(ANDROID_LOG_INFO, "XT9REC", __VA_ARGS__)
    const char* phase = jphase ? (*env)->GetStringUTFChars(env, jphase, 0) : "?";
    const unsigned char* ctx = (const unsigned char*)(intptr_t)handle;
    if (!ctx || ru16(ctx, OFF_MAGIC) != ET9_MAGIC) { LOGR("[%s] bad handle", phase); goto out; }
    {
    const unsigned char* traceObj = *(const unsigned char* const*)(ctx + OFF_TRACEOBJ);
    if (!traceObj) { LOGR("[%s] traceObj=null", phase); goto out; }
    const unsigned char* res = traceObj + RES_OFF;   /* +0x49000 */
    LOGR("[%s] header: cursor(0x370)=%u counter(0x374)=%u", phase, ru32(res, 0x370), ru32(res, 0x374));
    int best = -1; uint32_t bestseq = 0;
    int alt = -1;  uint32_t altn = 0;                /* fallback: fullest non-finalized slot */
    for (int s = 0; s < 10; s++) {
        const unsigned char* rec = res + 0x360 + (size_t)s * TR_STRIDE;
        uint32_t state = ru32(rec, 0x44), seq = ru32(rec, 0x3c), n = ru32(rec, 0x48);
        LOGR("[%s]   slot[%d] state(0x44)=%u seqId(0x3c)=%u nSamp(0x48)=%u tag(0x28)=0x%08x", phase, s, state, seq, n, ru32(rec,0x28));
        if (state == 2 && (best < 0 || seq >= bestseq)) { best = s; bestseq = seq; }
        if (n > altn) { alt = s; altn = n; }
    }
    if (best < 0) {
        LOGR("[%s] (no finalized slot)%s", phase, alt >= 0 ? " — dumping fullest slot instead:" : "");
        best = alt;
    }
    if (best < 0) goto out;
    const unsigned char* rec = res + 0x360 + (size_t)best * TR_STRIDE;
    LOGR("[%s] slot[%d] first 0x70 bytes (header + sample[0..1]):", phase, best);
    for (int o = 0; o < 0x70; o += 16) {
        const unsigned char* h = rec + o;
        LOGR("[%s]   +%03x: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
             phase, o, h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7],h[8],h[9],h[10],h[11],h[12],h[13],h[14],h[15]);
    }
    }
out:
    if (jphase) (*env)->ReleaseStringUTFChars(env, jphase, phase);
    #undef LOGR
}

/* P3 extraction: dump the in-memory NKL (loaded KDB) at ctx+0x60 so we can read the per-key smart-touch
 * data the resolver (0x5cbe8) uses — radius params (NKL+0x38/0x3c), per-key bias, protective regions,
 * and the two centers. RE-confirmed layout: keyCount @ NKL+0x64; key array @ NKL+0x68, stride 136.
 * Read-only. Tag XT9NKL. Call once after the KDB is loaded. View: adb logcat -s XT9NKL:I */
JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Trace_dumpNkl(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    #define LOGN(...) __android_log_print(ANDROID_LOG_INFO, "XT9NKL", __VA_ARGS__)
    const unsigned char* ctx = (const unsigned char*)(intptr_t)handle;
    if (!ctx || ru16(ctx, OFF_MAGIC) != ET9_MAGIC) { LOGN("bad handle"); return; }
    const unsigned char* nkl = *(const unsigned char* const*)(ctx + 0x60);
    if (!nkl) { LOGN("NKL=null (no KDB loaded)"); return; }
    uint32_t kc = ru32(nkl, 0x64);
    LOGN("NKL=%p dims(0x1a)=%u x %u  radius(0x38)=%u,%u  keyCount(0x64)=%u",
         nkl, ru16(nkl,0x1a), ru16(nkl,0x1c), ru32(nkl,0x38), ru32(nkl,0x3c), kc);
    /* header */
    for (int o = 0; o < 0x70; o += 16) {
        const unsigned char* h = nkl + o;
        LOGN("hdr+%03x: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
             o, h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7],h[8],h[9],h[10],h[11],h[12],h[13],h[14],h[15]);
    }
    /* per-key structs: NKL+0x68 + i*136 (0x88). Dump full 136 B so every smart-touch field is captured. */
    uint32_t n = kc > 40 ? 40 : kc;
    for (uint32_t i = 0; i < n; i++) {
        const unsigned char* k = nkl + 0x68 + (size_t)i * 136;
        uint16_t code = ru16(k, 0);
        char c = (code >= 32 && code < 127) ? (char)code : '?';
        LOGN("key[%u] '%c' code=%u  (base +0x%zx):", i, c, code, (size_t)(0x68 + i*136));
        for (int o = 0; o < 136; o += 16) {
            const unsigned char* h = k + o;
            int m = (o + 16 <= 136) ? 16 : (136 - o);
            char line[80]; int p = 0;
            p += snprintf(line+p, sizeof(line)-p, "  +%03x:", o);
            for (int j = 0; j < m; j++) p += snprintf(line+p, sizeof(line)-p, "%s%02x", (j%4==0)?" ":"", h[j]);
            LOGN("%s", line);
        }
        /* Resolve per-key identity fields so a single capture answers the cutover unknowns:
         *   +0x00 index, +0x04 type, +0x08 flag8 (letter-vs-function discrimination),
         *   +0x40 alt-char COUNT, +0x48 char-list POINTER -> deref and show the first few entries
         *   as BOTH u8 and u16 so the char encoding (ET9SYMB u16 vs u8) is unambiguous. */
        uint32_t idx = ru32(k, 0x00), type = ru32(k, 0x04), flag8 = ru32(k, 0x08), cnt = ru32(k, 0x40);
        uint64_t lp = ru64(k, 0x48);
        LOGN("  identity: idx=%u type=%u flag8=%u  altCount(+0x40)=%u  charPtr(+0x48)=0x%llx",
             idx, type, flag8, cnt, (unsigned long long)lp);
        if (lp) {
            const unsigned char* cl = (const unsigned char*)(intptr_t)lp;
            uint32_t show = cnt ? (cnt > 6 ? 6 : cnt) : 4;   /* show a few even if count reads 0 */
            char u8s[64]; int p8 = 0; char u16s[96]; int p16 = 0;
            for (uint32_t j = 0; j < show; j++) {
                uint8_t  b  = cl[j];
                uint16_t w2 = ru16(cl, (size_t)j * 2);
                p8  += snprintf(u8s + p8,  sizeof(u8s) - p8,  " %02x('%c')", b,  (b  >= 32 && b  < 127) ? (char)b  : '.');
                p16 += snprintf(u16s + p16, sizeof(u16s) - p16, " %04x('%c')", w2, (w2 >= 32 && w2 < 127) ? (char)w2 : '.');
            }
            LOGN("    charList u8 :%s", u8s);
            LOGN("    charList u16:%s", u16s);
        }
    }
    #undef LOGN
}

/* W3b discovery: locate + dump AW's committed symbol buffer (ET9WordSymbInfo, at ctx+0x52) so we can
 * map its layout against a KNOWN swiped word, then diff the blob's per-position candidate sets vs the
 * owned XT9OWNED sets. Read-only. Tag XT9SYMB. Call right after touchEnd, before the word is accepted. */
JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Trace_dumpSymb(JNIEnv* env, jclass cls, jlong handle, jstring jtag) {
    (void)cls;
    /* Log under the PASSED tag (e.g. "XT9SYMB") so `adb logcat -s XT9SYMB:I` catches it. */
    const char* lt = jtag ? (*env)->GetStringUTFChars(env, jtag, 0) : "XT9SYMB";
    #define LOGS(...) __android_log_print(ANDROID_LOG_INFO, lt, __VA_ARGS__)

    const unsigned char* ctx = (const unsigned char*)(intptr_t)handle;
    if (!ctx || ru16(ctx, OFF_MAGIC) != ET9_MAGIC) {
        LOGS("bad handle (magic=0x%x)", ctx ? ru16(ctx, OFF_MAGIC) : 0);
        if (jtag) (*env)->ReleaseStringUTFChars(env, jtag, lt);
        return;
    }

    /* (1) raw window across the boundary: shows 0x52 (WordSymbInfo) and the 0x58 ctx magic. */
    LOGS("ctx+0x52: u16=0x%04x  u64=0x%016llx  (ctx magic@0x58=0x%04x)",
         ru16(ctx,0x52), (unsigned long long)ru64(ctx,0x52), ru16(ctx,0x58));

    const unsigned char* w = ctx + 0x52;        /* treat as embedded struct base */

    /* (2) hexdump 0x180 bytes of the embedded region + a u16-as-ASCII preview so a known word
     *     (e.g. swipe "was" -> look for w,a,s = 0x77,0x61,0x73) is recognizable in the layout. */
    char prev[200]; int pn = 0;
    for (int o = 0; o < 0x180; o += 16) {
        const unsigned char* h = w + o;
        LOGS("+%03x: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
             o, h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7],h[8],h[9],h[10],h[11],h[12],h[13],h[14],h[15]);
    }
    for (int o = 0; o + 2 <= 0x180 && pn < 190; o += 2) {
        uint16_t s = ru16(w, o);
        prev[pn++] = (s >= 0x20 && s < 0x7f) ? (char)s : '.';
    }
    prev[pn] = 0;
    LOGS("u16-ascii: %s", prev);

    #undef LOGS
    if (jtag) (*env)->ReleaseStringUTFChars(env, jtag, lt);
}

/* scribble: 0=read-only, 1=zero x/y, 2=zero keyId (boundary test — see §4). */
JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Trace_dump(JNIEnv* env, jclass cls,
                                             jlong handle, jstring jtag, jint scribble) {
    (void)cls;
    const char* ctx = (const char*)(intptr_t)handle;
    if (!ctx) { LOGI("ctx=null"); return; }
    uint16_t magic = ru16(ctx, OFF_MAGIC);
    if (magic != ET9_MAGIC) { LOGI("magic=0x%x != 0x1428 (wrong handle?)", magic); return; }
    void* traceObj = *(void* const*)(ctx + OFF_TRACEOBJ);
    if (!traceObj) { LOGI("traceObj=null"); return; }
    char* res = (char*)traceObj + RES_OFF;     /* the actual result block */

    const char* tag = jtag ? (*env)->GetStringUTFChars(env, jtag, 0) : "dump";
    LOGI("[%s] res=%p idctr=%u kind@3a4=%u  hdr: 360=%u 364=%u 368=%u 36c=%u 39c=%u 3a8=%u",
         tag, res, ru32(res,TR_IDCTR), ru32(res,TR_KIND),
         ru32(res,0x360), ru32(res,0x364), ru32(res,0x368), ru32(res,0x36c),
         ru32(res,0x39c), ru32(res,0x3a8));

    /* Ground-truth hex of the header region so we can confirm field offsets. */
    for (int o = 0x360; o < 0x3b0; o += 16) {
        const unsigned char* h = (const unsigned char*)res + o;
        LOGI("  hdr +%03x: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
             o, h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7],h[8],h[9],h[10],h[11],h[12],h[13],h[14],h[15]);
    }

    uint32_t cnt = ru32(res, 0x364);
    /* ~6 records fit before the next sub-region; probe 7 to see the boundary. */
    for (uint32_t i = 0; i < 7; i++) {
        char* rec = res + TR_BLOCK + (size_t)i * TR_STRIDE;
        LOGI("  rec[%u] seq=%u x=%.1f y=%.1f t=%u m48=%u f44=%u tag=0x%x",
             i, ru32(rec,0x3c), rf32(rec,0x50), rf32(rec,0x54),
             ru32(rec,0x30), ru32(rec,0x48), ru32(rec,0x44), ru32(rec,0x28));
        if (scribble == 1) { float z = 0; memcpy(rec+0x50,&z,4); memcpy(rec+0x54,&z,4); }
        if (scribble == 2) { uint32_t z = 0; memcpy(rec+0x28,&z,4); }
    }
    /* The just-added gesture is record[cnt]. Its sampled path is an array at
     * +0x5c of {float X, float Y, u32 timestamp} (12 B each); count is at +0x48. */
    if (cnt < 7) {
        char* rec = res + TR_BLOCK + (size_t)cnt * TR_STRIDE;
        uint32_t nsamp = ru32(rec, 0x48);
        const char* s = rec + 0x5c;            /* sample[0] */
        /* Touch coordinate RANGE over the whole gesture — tells us the real sensor span
         * vs. the 1080x525 we tell setKeyboardSize. If maxY tops out ~324, the 525 is
         * stretching the grid (keys mapped into empty space below the physical rows). */
        float minX=1e9f,maxX=-1e9f,minY=1e9f,maxY=-1e9f;
        uint32_t lim = nsamp; if (lim > 256) lim = 256;
        for (uint32_t k = 0; k < lim; k++) {
            const char* sp = s + (size_t)k * 12;
            float x = rf32(sp,0), y = rf32(sp,4);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        LOGI("  gesture rec[%u]: samples=%u start=(%.0f,%.0f) RANGE x[%.0f..%.0f] y[%.0f..%.0f]",
             cnt, nsamp, rf32(rec,0x50), rf32(rec,0x54), minX,maxX,minY,maxY);
        uint32_t show = nsamp; if (show > 8) show = 8;     /* fewer samples now; mapping the tail */
        for (uint32_t k = 0; k < show; k++) {
            const char* sp = s + (size_t)k * 12;
            LOGI("    s[%u] x=%.1f y=%.1f t=%u", k, rf32(sp,0), rf32(sp,4), ru32(sp,8));
        }
        /* OUTPUT TAIL: per-sample candidate-key data lives after the path. Find the
         * first non-zero region at/after the sample array end and dump a window. */
        size_t tail = 0x5c + (size_t)nsamp * 12;
        tail = (tail + 0xf) & ~(size_t)0xf;     /* round up to 16 */
        size_t found = 0;
        for (size_t o = tail; o + 16 <= 0x7568; o += 16) {
            const unsigned char* h = (const unsigned char*)rec + o;
            int nz = 0; for (int k = 0; k < 16; k++) nz |= h[k];
            if (nz) { found = o; break; }
        }
        LOGI("  tail: samples end ~+0x%zx; first non-zero after = +0x%zx", tail, found);
        if (found) {
            for (size_t o = found; o < found + 0x90 && o + 16 <= 0x7568; o += 16) {
                const unsigned char* h = (const unsigned char*)rec + o;
                LOGI("  tail +%04zx: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
                     o, h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7],h[8],h[9],h[10],h[11],h[12],h[13],h[14],h[15]);
            }
        }
    }
    if (jtag) (*env)->ReleaseStringUTFChars(env, jtag, tag);
}

/* Optional: hexdump `len` bytes of record[idx] to map the 30 KB internals later
 * (only needed for the ET9AWReselectWord path). */
JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Trace_dumpHex(JNIEnv* env, jclass cls,
                                                jlong handle, jint idx, jint len) {
    (void)env;(void)cls;
    const char* ctx = (const char*)(intptr_t)handle;
    if (!ctx || ru16(ctx, OFF_MAGIC) != ET9_MAGIC) { LOGI("hex: bad handle"); return; }
    void* traceObj = *(void* const*)(ctx + OFF_TRACEOBJ);
    if (!traceObj) return;
    const unsigned char* rec = (const unsigned char*)traceObj + RES_OFF + TR_BLOCK + (size_t)idx * TR_STRIDE;
    if (len > 0x400) len = 0x400;
    for (int o = 0; o < len; o += 16) {
        char line[80]; int p = 0;
        p += snprintf(line+p, sizeof(line)-p, "+%04x:", o);
        for (int k = 0; k < 16 && o+k < len; k++) p += snprintf(line+p, sizeof(line)-p, " %02x", rec[o+k]);
        LOGI("  rec[%d]%s", idx, line);
    }
}
