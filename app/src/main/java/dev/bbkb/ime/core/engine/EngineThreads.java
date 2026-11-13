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
 * The only two threads that may call the XT9 engine; both live as long as the process. The engine
 * keeps pointers into the stack of the thread that last called it, so once that thread exits the
 * next call reads an unmapped stack and crashes (SIGSEGV in libnative-lib).
 * <ul>
 *   <li>Engine thread ({@value #ENGINE_THREAD_NAME}): short ops (feeds, gestures, suggestion
 *       requests, learning, queries, setters).</li>
 *   <li>Loader thread ({@value #LOADER_THREAD_NAME}): long holds (init, language loads, packs,
 *       contacts, learned model export/import/enumerate/reset).</li>
 * </ul>
 * A keystroke during a language load waits only for {@code NuanceSDK.sMutex}; main waits for neither.
 *
 * <p>Ordering is FIFO on the engine looper, across all its Handlers, so ops need no locks. Never
 * use {@code postDelayed}/{@code sendMessageDelayed} there (delay on main, then post); the only
 * exception is {@link #postAtFront}. Main never waits on the loader, only bounded on {@link #query}.
 * Calls made from either thread run inline so nested calls cannot deadlock.
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
     * Never shut down, and a throwing task does not replace its thread (a plain single-thread
     * executor would swap in a new worker, which is a thread exit).
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

    /** Runs {@code op} on the engine thread after everything posted; inline from an engine-capable thread. */
    public static void post(Runnable op) {
        if (sInlineForTests || onEngineCapableThread()) {
            op.run();
            return;
        }
        engineHandler().post(guarded(op));
    }

    /** Ahead of everything queued; only for the layout sync after a language load. */
    public static void postAtFront(Runnable op) {
        if (sInlineForTests || onEngineCapableThread()) {
            op.run();
            return;
        }
        engineHandler().postAtFrontOfQueue(guarded(op));
    }

    /**
     * Runs {@code op} on the engine thread, waiting up to {@code timeoutMs}; {@code fallback} on
     * timeout, throw or interrupt. A timed-out op is not cancelled and runs late, so it must be safe to.
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

    /** Runs {@code op} on the loader thread; inline (future already complete) when called from it. */
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

    /** Debug builds: throws unless on an engine-capable thread. The first statement of every engine wrapper. */
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

    /** Tests: every op runs inline on the caller and the assertions pass. Reset in teardown. */
    public static void setInlineForTests(boolean inline) {
        sInlineForTests = inline;
    }

    /** Logs a throwing op instead of letting it kill the engine thread. */
    private static Runnable guarded(Runnable op) {
        return () -> {
            try {
                op.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "engine op failed", e);
            }
        };
    }

    /** One daemon worker at background priority that never exits: catch-all tasks, shutdown ignored. */
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
