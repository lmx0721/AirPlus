// skid SetsunaClient
package net.airplus.music.ncm.util.other.multithreading;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiThreadingUtil {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "airplus-ncm-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private MultiThreadingUtil() {
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        if (runnable == null) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("Runnable is null"));
            return failed;
        }
        return CompletableFuture.runAsync(runnable, EXECUTOR);
    }

}
