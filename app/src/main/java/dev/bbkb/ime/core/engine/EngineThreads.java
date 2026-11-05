package dev.bbkb.ime.core.engine;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.bbkb.ime.BuildConfig;

/**
 * The two threads that make every call into the XT9 engine, and the only two that may.
 *
 * <p><b>Why only two, and why they never exit.</b> The engine is a closed native library that
 * keeps pointers into the stack of the thread that last called it. While that thread lives, the
 * stale pointers read stale but mapped memory and nothing visible happens; once it exits, the next
 * call from another thread reads an unmapped stack and the process dies (SIGSEGV in libnative-lib,
 * twice on the KEY2 on 2026-11-04, after a per-service worker thread was quit). Pool threads,
 * binder threads and a startup pool shut down with its service all exit sooner or later, so none
 * of them may call the engine. These two are created once and live as long as the process:
 * <ul>
 *   <li><b>The engine thread</b> ({@value #ENGINE_THREAD_NAME}, a HandlerThread): every short op.
 *       Key feeds, gesture samples, suggestion requests (the request queue's Handler is on this
 *       looper), learning at commit, validity and converter queries, option setters.</li>
 *   <li><b>The loader thread</b> ({@value #LOADER_THREAD_NAME}, a single-thread executor): every
 *       long hold. Engine init, language loads, pack registration, the contacts batch, learned
 *       model export, import, enumeration and reset, dispose.</li>
 * </ul>
 * They are separate so that a keystroke posted during a seconds-long language load waits only for
 * the engine lock ({@code NuanceSDK.sMutex}, which arbitrates between these two threads and
 * nothing else), and the main thread waits for neither. In debug builds every engine wrapper calls
 * {@link #assertEngineCapable}, so a call from any other thread fails at once instead of crashing
 * on some later day.
 *
 * <p><b>FIFO is the ordering model.</b> Everything posted to the engine looper with no delay runs
 * in the order it was posted, across every Handler on that looper: a key feed posted before a
 * suggestion request is in the engine's buffer when the request runs, a commit's learn op runs
 * before the next word's first feed, a gesture's deposit and the request that consumes it cannot
 * interleave. No lock between those ops is needed and none should be added. For that reason
 * nothing may use {@code postDelayed} (or {@code sendMessageDelayed}) on this looper: a delayed op
 * jumps behind everything posted after it and silently breaks the order the callers rely on. Delay
 * on the main thread and post from there instead. The one sanctioned exception to plain FIFO is
 * {@link #postAtFront}, for a layout sync that must land before queued feeds after a language load.
 *
 * <p><b>The main thread never waits on the loader.</b> It may wait, bounded, for a short engine
 * thread op ({@link #query}); a long hold is started with {@link #load} and its result arrives
 * through a callback or a coroutine.
 *
 * <p>Both threads already being engine-capable, {@link #post}, {@link #postAtFront},
 * {@link #query} and {@link #load} run the op inline when called from one of them (load: only on
 * the loader itself), which keeps a nested call from deadlocking on its own queue.
 */
public final class EngineThreads {

    private static final String TAG = "EngineThreads";

    /** Literal names: R8 renames the classes, so thread dumps rely on these. */
    static final String ENGINE_THREAD_NAME = "EngineThread";
    static final String LOADER_THREAD_NAME = "EngineLoader";

    private static volatile Looper sEngineLooper;
    private static volatile Handler sEngineHandler;
    private static volatile Thread sLoaderThread;
    private static volatile boolean sInlineForTests;

    private EngineThreads() {
    }

    /** The engine thread's looper; the thread is created on first use and never quits. */
    public static Looper engineLooper() {
        Looper looper = sEngineLooper;
        if (looper == null) {
            synchronized (EngineThreads.class) {
                looper = sEngineLooper;
                if (looper == null) {
                    HandlerThread thread = new HandlerThread(ENGINE_THREAD_NAME,
                            Process.THREAD_PRIORITY_DEFAULT);
                    thread.start();
                    looper = thread.getLooper();
                    sEngineHandler = new Handler(looper);
                    sEngineLooper = looper;
                }
            }
        }
        return looper;
    }

    /** A shared Handler on {@link #engineLooper()}, for callers that need a Handler to post to. */
    public static Handler engineHandler() {
        engineLooper();
        return sEngineHandler;
    }

    /** Holder idiom: the loader thread is created on first use. */
    private static final class Loader {
        static final ExecutorService EXECUTOR = new PersistentSingleThreadExecutor();
    }

    /**
     * The loader thread's executor. It is never shut down ({@code shutdown()} is ignored), and a
     * task that throws does not replace its thread: a plain single-thread executor swaps in a new
     * worker after an uncaught exception, which is a thread exit by another name.
     */
    public static ExecutorService loader() {
        return Loader.EXECUTOR;
    }

    public static boolean onEngineThread() {
        Looper looper = sEngineLooper;
        return looper != null && looper.getThread() == Thread.currentThread();
    }

    public static boolean onLoaderThread() {
        Thread loader = sLoaderThread;
        return loader != null && loader == Thread.currentThread();
    }

    /** Either of the two threads that may call the engine. */
    public static boolean onEngineCapableThread() {
        return onEngineThread() || onLoaderThread();
    }

    /**
     * Runs {@code op} on the engine thread, after everything already posted there. Runs it inline
     * when the caller is already the engine thread or the loader thread.
     */
    public static void post(Runnable op) {
        if (sInlineForTests || onEngineCapableThread()) {
            op.run();
            return;
        }
        engineHandler().post(guarded(op));
    }

    /**
     * Runs {@code op} on the engine thread ahead of everything queued there. Only for an op that
     * must land before queued feeds (the layout sync after a language load); see the class
     * comment. Inline when the caller is already engine-capable.
     */
    public static void postAtFront(Runnable op) {
        if (sInlineForTests || onEngineCapableThread()) {
            op.run();
            return;
        }
        engineHandler().postAtFrontOfQueue(guarded(op));
    }

    /**
     * Runs {@code op} on the engine thread and waits up to {@code timeoutMs} for its result.
     * Inline when the caller is already engine-capable. Returns {@code fallback} when the wait
     * times out, when the op throws, or when the waiting thread is interrupted (its interrupt
     * flag is set again). A timed-out op is NOT cancelled: it stays queued and runs later, after
     * everything posted before it, with its result discarded. So an op passed here must be safe
     * to run late, which every read is.
     */
    public static <T> T query(Callable<T> op, long timeoutMs, T fallback) {
        if (sInlineForTests || onEngineCapableThread()) {
            try {
                return op.call();
            } catch (Exception e) {
                Log.w(TAG, "engine query failed", e);
                return fallback;
            }
        }
        FutureTask<T> task = new FutureTask<>(op);
        engineHandler().post(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "engine query timed out after " + timeoutMs + " ms");
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException e) {
            Log.w(TAG, "engine query failed", e.getCause());
            return fallback;
        }
    }

    /**
     * Runs {@code op} on the loader thread. When the caller is the loader thread it runs inline
     * and the returned future is already complete (queueing it would deadlock a caller that then
     * waits on it).
     */
    public static <T> Future<T> load(Callable<T> op) {
        FutureTask<T> task = new FutureTask<>(op);
        if (sInlineForTests || onLoaderThread()) {
            task.run();
            return task;
        }
        loader().execute(task);
        return task;
    }

    /** {@link #load(Callable)} for an op with no result. */
    public static Future<?> load(Runnable op) {
        return load(Executors.callable(op));
    }

    /**
     * Debug builds only: throws unless the caller is the engine thread or the loader thread (or
     * the tests made every op inline). The first statement of every engine wrapper. In release
     * builds {@code BuildConfig.DEBUG} is a constant false and R8 removes the body.
     */
    public static void assertEngineCapable(String what) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (sInlineForTests || onEngineCapableThread()) {
            return;
        }
        throw new IllegalStateException(what + " called on " + Thread.currentThread().getName()
                + "; engine calls run on " + ENGINE_THREAD_NAME + " or " + LOADER_THREAD_NAME + " only");
    }

    /** Debug builds only: throws when called on the main thread (unless the tests made ops inline). */
    public static void assertNotMain(String what) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (sInlineForTests) {
            return;
        }
        if (Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalStateException(what + " called on the main thread; it may wait for "
                    + "the engine, which the main thread never does");
        }
    }

    /**
     * Tests: when true, {@link #post}, {@link #postAtFront}, {@link #query} and {@link #load} run
     * the op at once on the caller, and the assertions pass, so a test that drives typing on its
     * own thread keeps its synchronous expectations. Reset it in the test's teardown.
     */
    public static void setInlineForTests(boolean inline) {
        sInlineForTests = inline;
    }

    /**
     * An uncaught exception in a Handler callback kills the looper's thread; an op that throws is
     * logged instead, so the engine thread outlives it.
     */
    private static Runnable guarded(Runnable op) {
        return () -> {
            try {
                op.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "engine op failed", e);
            }
        };
    }

    /**
     * One worker thread named {@value #LOADER_THREAD_NAME}, a daemon at background priority,
     * that never exits: tasks run inside a catch-all, and shutdown is ignored.
     */
    private static final class PersistentSingleThreadExecutor extends ThreadPoolExecutor {

        PersistentSingleThreadExecutor() {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    runnable.run();
                }, LOADER_THREAD_NAME);
                thread.setDaemon(true);
                sLoaderThread = thread;
                return thread;
            });
        }

        @Override
        public void execute(Runnable command) {
            super.execute(() -> {
                try {
                    command.run();
                } catch (Throwable t) {
                    // A FutureTask (submit, load) never gets here; a plain execute() task would
                    // otherwise kill the worker and the pool would start a new thread.
                    Log.e(TAG, "loader op failed", t);
                }
            });
        }

        @Override
        public void shutdown() {
            if (BuildConfig.DEBUG) Log.w(TAG, "the engine loader is never shut down; ignored");
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            return Collections.emptyList();
        }
    }
}
