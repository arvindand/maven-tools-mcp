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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Runs batches with one deadline, including time waiting for a permit.
 *
 * <p>Cancellation is cooperative. When the batch ends early (deadline or a failed item), work that
 * has not started is abandoned, and operations already running are left to finish on their own
 * bounded timeouts. They are deliberately not interrupted: an interrupted HTTP call surfaces as a
 * transport failure, and a burst of those opens the upstream circuit breaker and fails the calls
 * that follow. Whatever the running operations fetch still lands in the caches, so a retry resumes
 * where the batch stopped.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
public final class BoundedBatch {
  public static final int MAX_ITEMS = 500;

  private BoundedBatch() {}

  /** The batch did not finish within its total budget. */
  public static final class DeadlineExceededException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public DeadlineExceededException(Throwable cause) {
      super("Dependency batch exceeded its total deadline", cause);
    }
  }

  /** Marks an item that was abandoned before its operation started. */
  private static final class NotStartedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotStartedException() {
      super("Dependency batch ended before this item started", null, false, false);
    }
  }

  /**
   * Maps a bounded list on virtual threads without waiting for abandoned work.
   *
   * @param items input items in result order
   * @param operation operation for each item
   * @param permits shared concurrency limit
   * @param timeout total batch budget
   * @param <T> input type
   * @param <R> result type
   * @return results in input order
   * @throws IllegalArgumentException when the input exceeds the item budget
   * @throws DeadlineExceededException when the batch does not finish within {@code timeout}
   * @throws IllegalStateException when interrupted or a checked operation fails
   */
  public static <T, R> List<R> map(
      List<T> items, Function<T, R> operation, Semaphore permits, Duration timeout) {
    if (items.size() > MAX_ITEMS)
      throw new IllegalArgumentException("At most " + MAX_ITEMS + " dependencies per request");
    long deadline = System.nanoTime() + timeout.toNanos();
    AtomicBoolean ended = new AtomicBoolean();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<R>> futures = new ArrayList<>();
    try {
      for (T item : items) {
        futures.add(executor.submit(() -> runItem(item, operation, permits, deadline, ended)));
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
      // An item that ran out of time waiting for a permit is the same deadline, not a failure.
      if (ex.getCause() instanceof NotStartedException) throw new DeadlineExceededException(ex);
      if (ex.getCause() instanceof RuntimeException cause) throw cause;
      throw new IllegalStateException("Dependency batch failed", ex.getCause());
    } catch (TimeoutException ex) {
      throw new DeadlineExceededException(ex);
    } finally {
      ended.set(true);
      executor.shutdown();
    }
  }

  /** Waits for a permit until the batch deadline, then runs the item unless the batch ended. */
  private static <T, R> R runItem(
      T item, Function<T, R> operation, Semaphore permits, long deadline, AtomicBoolean ended)
      throws InterruptedException {
    long wait = deadline - System.nanoTime();
    if (ended.get() || wait <= 0 || !permits.tryAcquire(wait, TimeUnit.NANOSECONDS)) {
      throw new NotStartedException();
    }
    try {
      if (ended.get() || deadline - System.nanoTime() <= 0) {
        throw new NotStartedException();
      }
      return operation.apply(item);
    } finally {
      permits.release();
    }
  }
}
