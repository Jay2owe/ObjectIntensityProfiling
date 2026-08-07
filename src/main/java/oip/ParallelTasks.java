/*-
 * #%L
 * Per-object, cross-channel intensity profiles and texture measurements for ImageJ and Fiji
 * %%
 * Copyright (C) 2026 Jamie Malcolm
 * %%
 * BSD 3-Clause License
 * #L%
 */
package oip;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Run-independent, deterministically ordered parallel task support. */
public final class ParallelTasks {

    public interface Task<T> {
        T run(int index);
    }

    public interface CompletionListener {
        void completed(int index);
    }

    private ParallelTasks() {
    }

    public static <T> List<T> mapOrdered(
            int taskCount,
            OipParameters.CancellationToken cancellation,
            Task<T> task,
            CompletionListener completion) {
        ArrayList<T> output = new ArrayList<T>(taskCount);
        int workers = workerCount(taskCount);
        if (workers == 1) {
            for (int i = 0; i < taskCount; i++) {
                checkCancelled(cancellation);
                output.add(task.run(i));
                if (completion != null) completion.completed(i);
            }
            return output;
        }

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        ArrayList<Future<T>> futures = new ArrayList<Future<T>>(taskCount);
        try {
            for (int i = 0; i < taskCount; i++) {
                final int index = i;
                futures.add(executor.submit(new Callable<T>() {
                    @Override
                    public T call() {
                        checkCancelled(cancellation);
                        T result = task.run(index);
                        checkCancelled(cancellation);
                        return result;
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                checkCancelled(cancellation);
                output.add(get(futures.get(i), futures));
                if (completion != null) completion.completed(i);
            }
            return output;
        } finally {
            stop(executor);
        }
    }

    private static int workerCount(int tasks) {
        if (tasks < 2) return 1;
        int configured = Integer.getInteger("oip.parallelism", 0).intValue();
        int available = Runtime.getRuntime().availableProcessors();
        int desired = configured > 0 ? configured : Math.min(available, 8);
        return Math.max(1, Math.min(tasks, desired));
    }

    private static <T> T get(Future<T> future, List<? extends Future<?>> all) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            cancel(all);
            Thread.currentThread().interrupt();
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        } catch (ExecutionException failed) {
            cancel(all);
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Parallel analysis task failed.", cause);
        }
    }

    private static void checkCancelled(OipParameters.CancellationToken cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ObjectIntensityProfiling.AnalysisCancelledException();
        }
    }

    private static void cancel(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) future.cancel(true);
    }

    private static void stop(ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
