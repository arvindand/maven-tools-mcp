package com.arvindand.mcp.maven.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Runs batches with one deadline and cancellation, including time waiting for a permit.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
public final class BoundedBatch {
  public static final int MAX_ITEMS = 500;

  private BoundedBatch() {}

  /**
   * Maps a bounded list on virtual threads without waiting indefinitely during executor shutdown.
   *
   * @param items input items in result order
   * @param operation operation for each item
   * @param permits shared concurrency limit
   * @param timeout total batch budget
   * @param <T> input type
   * @param <R> result type
   * @return results in input order
   * @throws IllegalArgumentException when the input exceeds the item budget
   * @throws IllegalStateException when interrupted, timed out or a checked operation fails
   */
  public static <T, R> List<R> map(
      List<T> items, Function<T, R> operation, Semaphore permits, Duration timeout) {
    if (items.size() > MAX_ITEMS)
      throw new IllegalArgumentException("At most " + MAX_ITEMS + " dependencies per request");
    long deadline = System.nanoTime() + timeout.toNanos();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<R>> futures = new ArrayList<>();
    try {
      for (T item : items) {
        futures.add(
            executor.submit(
                () -> {
                  permits.acquire();
                  try {
                    return operation.apply(item);
                  } finally {
                    permits.release();
                  }
                }));
      }
      List<R> results = new ArrayList<>();
      for (Future<R> future : futures) {
        results.add(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      }
      return results;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Dependency batch interrupted", ex);
    } catch (ExecutionException ex) {
      if (ex.getCause() instanceof RuntimeException cause) throw cause;
      throw new IllegalStateException("Dependency batch failed", ex.getCause());
    } catch (TimeoutException ex) {
      throw new IllegalStateException("Dependency batch exceeded its total deadline", ex);
    } finally {
      futures.forEach(future -> future.cancel(true));
      executor.shutdownNow();
    }
  }
}
