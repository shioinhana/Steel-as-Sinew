package org.fsrpg.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Main-thread-owned generations with one background writer; completion never mutates caller state. */
final class AsyncConfigSave {
    private final ExecutorService executor;
    private final LongSupplier clock;
    private final long retryDelay;
    private final Consumer<RuntimeException> failures;
    private Future<Long> writing;
    private long requested, saved, writingGeneration, retryAfter, saves, lastWriteNanos, longestWriteNanos;

    AsyncConfigSave(ExecutorService executor, LongSupplier clock, long retryDelay, Consumer<RuntimeException> failures) {
        this.executor = executor; this.clock = clock; this.retryDelay = retryDelay; this.failures = failures;
    }
    void changed() { requested++; }
    boolean pending() { return saved < requested; }
    boolean writing() { return writing != null; }
    long requestedGeneration() { return requested; }
    long savedGeneration() { return saved; }
    long saveCount() { return saves; }
    long lastWriteNanos() { return lastWriteNanos; }
    long longestWriteNanos() { return longestWriteNanos; }

    /** Apply and save the identical captured graph even if a watcher replaces the active handle. */
    static <C, S> Runnable capture(Supplier<C> loaded, Supplier<S> snapshot, BiConsumer<C, S> apply, Consumer<C> save) {
        C captured = java.util.Objects.requireNonNull(loaded.get(), "Configuration is not loaded");
        S desired = snapshot.get();
        apply.accept(captured, desired);
        return () -> save.accept(captured);
    }

    /** Nonblocking during gameplay. The supplier captures loaded configuration on the calling thread. */
    void flush(Supplier<Runnable> prepare) { start(prepare, false); }
    void poll() { if (writing != null && writing.isDone()) finish(); }
    private void start(Supplier<Runnable> prepare, boolean force) {
        poll();
        if (!pending() || writing != null || (!force && clock.getAsLong() < retryAfter)) return;
        try {
            Runnable save = prepare.get();
            writingGeneration = requested;
            writing = executor.submit(() -> {
                long start = System.nanoTime();
                save.run();
                return System.nanoTime() - start;
            });
        } catch (RuntimeException error) { failed(error); }
    }
    private void finish() {
        boolean completed = true;
        try {
            lastWriteNanos = writing.get(); longestWriteNanos = Math.max(longestWriteNanos, lastWriteNanos);
            saved = Math.max(saved, writingGeneration); saves++; retryAfter = 0;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            // Keep the in-flight future: neither cancellation nor an interrupted caller proves a save completed.
            completed = false;
            return;
        } catch (ExecutionException error) {
            failed(new IllegalStateException("Configuration save failed", error.getCause()));
        } finally {
            if (completed) writing = null;
        }
    }
    private void failed(RuntimeException error) {
        retryAfter = clock.getAsLong() + retryDelay;
        failures.accept(error);
    }
    /** Only for leaving the world or process. Retry a bounded number of times and retain dirty on failure. */
    boolean drain(Supplier<Runnable> prepare, int attempts) {
        for (int attempt = 0; pending() && attempt < attempts && !Thread.currentThread().isInterrupted(); attempt++) {
            start(prepare, true);
            if (writing != null) finish();
        }
        return !pending();
    }
    void shutdown() { executor.shutdown(); }
}
