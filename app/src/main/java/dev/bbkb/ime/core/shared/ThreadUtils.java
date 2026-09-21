package dev.bbkb.ime.core.shared;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;



public final class ThreadUtils {

    private static final ConcurrentHashMap<String, ExecutorService> sExecutorMap = new ConcurrentHashMap<>();

    
    private static class NamedThreadFactory implements ThreadFactory {

        private final String threadName;

        NamedThreadFactory(String str) {
            this.threadName = str;
        }

        @Override // java.util.concurrent.ThreadFactory
        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, "Executor - " + this.threadName);
        }
    }

    public static ExecutorService getBackgroundExecutor(String str) {
        ExecutorService executorServiceNewSingleThreadExecutor = sExecutorMap.get(str);
        if (executorServiceNewSingleThreadExecutor == null) {
            synchronized (sExecutorMap) {
                executorServiceNewSingleThreadExecutor = sExecutorMap.get(str);
                if (executorServiceNewSingleThreadExecutor == null) {
                    executorServiceNewSingleThreadExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory(str));
                    sExecutorMap.put(str, executorServiceNewSingleThreadExecutor);
                }
            }
        }
        return executorServiceNewSingleThreadExecutor;
    }
}
