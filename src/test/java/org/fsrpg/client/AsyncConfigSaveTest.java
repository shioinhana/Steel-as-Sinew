package org.fsrpg.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Exercises actual executor overlap, failure/backoff and bounded exit draining without Minecraft. */
public final class AsyncConfigSaveTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        overlappingEdits(); replacedLoadedHandle(); retryAndExitDrain(); preparationFailure();
        System.out.println("AsyncConfigSaveTest: " + checks + " checks passed");
    }
    private static void replacedLoadedHandle() throws Exception {
        var original = new LinkedHashMap<String, Object>(Map.of("camera", 1, "customHud", "original custom value"));
        var replacement = new LinkedHashMap<String, Object>(Map.of("camera", 0, "customHud", "reloaded custom value"));
        var active = new AtomicReference<Map<String, Object>>(original);
        var disk = new AtomicReference<Map<String, Object>>();
        var executor = Executors.newSingleThreadExecutor();
        var queue = new AsyncConfigSave(executor, System::nanoTime, 100, error -> { throw new AssertionError(error); });
        try {
            queue.changed();
            Supplier<Runnable> prepare = () -> AsyncConfigSave.capture(active::get, () -> {
                // A watcher swaps in stale on-disk values precisely between handle capture and field updates.
                active.set(replacement);
                return 42;
            }, (captured, desired) -> captured.put("camera", desired), captured -> disk.set(Map.copyOf(captured)));
            check(queue.drain(prepare, 3), "captured graph saves despite active-handle replacement");
            check(disk.get().get("camera").equals(42), "acknowledged generation contains the desired value on disk");
            check(disk.get().get("customHud").equals("original custom value"), "whole captured graph retains unrelated custom values");
            check(active.get() == replacement && replacement.get("camera").equals(0), "save does not accidentally write the replacement graph");
            check(queue.savedGeneration() == 1 && queue.saveCount() == 1 && !queue.pending(), "acknowledgement corresponds to the actual captured save");
        } finally { queue.shutdown(); check(executor.awaitTermination(3, TimeUnit.SECONDS), "captured-graph writer terminates"); }
    }
    private static void overlappingEdits() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var clock = new AtomicLong(); var errors = new AtomicInteger();
        var queue = new AsyncConfigSave(executor, clock::get, 100, error -> errors.incrementAndGet());
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var memory = new AtomicInteger(1); var disk = new AtomicInteger(); var writers = new AtomicInteger();
        var concurrent = new AtomicInteger(); var peak = new AtomicInteger();
        long caller = Thread.currentThread().threadId(); var writerThread = new AtomicLong(caller);
        Supplier<Runnable> prepare = () -> {
            check(Thread.currentThread().threadId() == caller, "loaded handles are captured on caller thread");
            int snapshot = memory.get();
            return () -> {
                writerThread.set(Thread.currentThread().threadId());
                peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                try {
                    if (writers.incrementAndGet() == 1) {
                        entered.countDown();
                        try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test writer timeout"); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                    }
                    disk.set(snapshot);
                } finally { concurrent.decrementAndGet(); }
            };
        };
        try {
            queue.changed(); queue.flush(prepare);
            check(entered.await(3, TimeUnit.SECONDS), "background writer started while caller remains free");
            check(writerThread.get() != caller, "blocking persistence never executes on caller thread");
            check(queue.pending() && queue.saveCount() == 0, "in-flight save is not acknowledged early");
            memory.set(2); queue.changed(); queue.flush(prepare);
            memory.set(3); queue.changed(); queue.flush(prepare);
            check(writers.get() == 1, "new edits cannot overlap an existing writer");
            release.countDown();
            await(() -> { queue.poll(); return !queue.writing(); });
            check(disk.get() == 1 && queue.savedGeneration() == 1 && queue.pending(), "old completion cannot clear newer edits");
            check(queue.drain(prepare, 3), "exit drains latest requested generation");
            check(disk.get() == 3 && queue.savedGeneration() == 3 && !queue.pending(), "latest coalesced value reaches disk");
            check(writers.get() == 2 && queue.saveCount() == 2 && peak.get() == 1 && errors.get() == 0, "exactly two serialized successful writes");
        } finally { release.countDown(); queue.shutdown(); check(executor.awaitTermination(3, TimeUnit.SECONDS), "writer terminates after drain"); }
    }
    private static void retryAndExitDrain() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var clock = new AtomicLong();
        var errors = new AtomicInteger(); var attempts = new AtomicInteger(); var disk = new AtomicInteger();
        var queue = new AsyncConfigSave(executor, clock::get, 100, error -> errors.incrementAndGet());
        Supplier<Runnable> prepare = () -> () -> {
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("temporary file lock");
            disk.set(42);
        };
        try {
            queue.changed(); queue.flush(prepare);
            await(() -> { queue.poll(); return !queue.writing(); });
            check(queue.pending() && queue.saveCount() == 0 && errors.get() == 1, "failed write preserves dirty generation");
            clock.set(99); queue.flush(prepare);
            check(!queue.writing() && attempts.get() == 1, "gameplay retries obey backoff");
            clock.set(100); queue.flush(prepare);
            await(() -> { queue.poll(); return !queue.writing(); });
            check(attempts.get() == 2 && queue.pending(), "retry runs once its deadline arrives");
            check(queue.drain(prepare, 3), "exit retries without requiring future client ticks");
            check(disk.get() == 42 && queue.saveCount() == 1 && queue.savedGeneration() == 1 && errors.get() == 2, "only durable success increments completion count");
        } finally { queue.shutdown(); check(executor.awaitTermination(3, TimeUnit.SECONDS), "retry writer terminates"); }
    }
    private static void preparationFailure() throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var errors = new AtomicInteger();
        var queue = new AsyncConfigSave(executor, () -> 0, 100, error -> errors.incrementAndGet());
        Supplier<Runnable> missing = () -> { throw new IllegalStateException("configuration unloaded"); };
        try {
            queue.changed();
            check(!queue.drain(missing, 3), "permanent failure has bounded exit attempts");
            check(errors.get() == 3 && queue.pending() && queue.saveCount() == 0 && !queue.writing(), "capture failure retains unsaved state accurately");
        } finally { queue.shutdown(); check(executor.awaitTermination(3, TimeUnit.SECONDS), "unused executor terminates"); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("executor did not finish in time");
            Thread.sleep(1);
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
