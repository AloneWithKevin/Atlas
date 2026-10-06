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
        var work = new Work<>(task);
        try {
            executor.execute(work);
        } catch (RuntimeException rejected) {
            work.future.completeExceptionally(rejected);
        }
        return work.future;
    }

    /** Rejects future submissions, settles dropped work and interrupts active calls. */
    @Override public void close() {
        for (Runnable dropped : executor.shutdownNow()) {
            if (dropped instanceof Work<?> work)
                work.future.completeExceptionally(new java.util.concurrent.CancellationException());
        }
    }

    private static final class Work<T> implements Runnable {
        private final Callable<T> call;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private Work(Callable<T> call) { this.call = call; }
        @Override public void run() {
            if (future.isDone()) return;
            try { future.complete(call.call()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        }
    }
}
