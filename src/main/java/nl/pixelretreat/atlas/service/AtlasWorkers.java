package nl.pixelretreat.atlas.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded database and file workers. A full queue rejects the request instead of waiting. */
public final class AtlasWorkers implements AutoCloseable {
    private final ExecutorService executor;

    public AtlasWorkers(int threads, int queueSize) {
        this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), runnable -> {
                    var thread = new Thread(runnable, "Atlas-worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Runs a task off every game thread and completes the future with its result. */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        var future = new CompletableFuture<T>();
        try {
            executor.execute(() -> {
                try { future.complete(task.call()); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
            });
        } catch (RuntimeException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    /** Stops accepting work and interrupts what is queued. */
    @Override public void close() { executor.shutdownNow(); }
}
