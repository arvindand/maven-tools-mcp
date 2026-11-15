package com.arvindand.mcp.maven.util;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies batch admission and bounded cancellation without executor-close waits.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
class BoundedBatchTest {
  @Test
  void timesOutEvenWhenAnOperationIgnoresInterruption() {
    CountDownLatch release = new CountDownLatch(1);
    List<Integer> inputs = List.of(1);
    Semaphore permits = new Semaphore(1);
    Duration timeout = Duration.ofMillis(100);
    long start = System.nanoTime();
    try {
      assertThatThrownBy(
              () ->
                  BoundedBatch.map(
                      inputs,
                      value -> {
                        boolean done = false;
                        while (!done) {
                          try {
                            release.await();
                            done = true;
                          } catch (InterruptedException _) {
                            /* Simulate a non-cooperative third-party call. */
                          }
                        }
                        return value;
                      },
                      permits,
                      timeout))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("deadline");
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    } finally {
      release.countDown();
    }
  }

  @Test
  void runningOperationsAreLeftToFinishNotInterrupted() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    List<Integer> inputs = List.of(1);
    Semaphore permits = new Semaphore(1);
    Duration timeout = Duration.ofMillis(100);

    assertThatThrownBy(
            () ->
                BoundedBatch.map(
                    inputs,
                    value -> {
                      try {
                        release.await();
                      } catch (InterruptedException _) {
                        interrupted.set(true);
                      } finally {
                        finished.countDown();
                      }
                      return value;
                    },
                    permits,
                    timeout))
        .isInstanceOf(BoundedBatch.DeadlineExceededException.class);

    release.countDown();
    assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted)
        .as("an interrupted HTTP call counts as an upstream failure and opens the breaker")
        .isFalse();
  }

  @Test
  void itemsStillWaitingWhenTheBatchEndsNeverStart() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger started = new AtomicInteger();
    List<Integer> inputs = List.of(1, 2, 3);
    Semaphore permits = new Semaphore(1);
    Duration timeout = Duration.ofMillis(100);

    assertThatThrownBy(
            () ->
                BoundedBatch.map(
                    inputs,
                    value -> {
                      started.incrementAndGet();
                      try {
                        release.await();
                      } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                      }
                      return value;
                    },
                    permits,
                    timeout))
        .isInstanceOf(BoundedBatch.DeadlineExceededException.class);

    release.countDown();
    assertThat(permits.tryAcquire(2, TimeUnit.SECONDS))
        .as("the running item releases its permit when it finishes")
        .isTrue();
    assertThat(started).hasValue(1);
  }

  @Test
  void permitQueueTimeIsPartOfTheSameDeadline() {
    List<Integer> inputs = List.of(1);
    Semaphore permits = new Semaphore(0);
    Duration timeout = Duration.ofMillis(50);
    assertThatThrownBy(() -> BoundedBatch.map(inputs, value -> value, permits, timeout))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("deadline");
  }

  @Test
  void expiredBatchDoesNotStartAnItemEvenWithAnAvailablePermit() {
    AtomicInteger started = new AtomicInteger();
    assertThatThrownBy(
            () ->
                BoundedBatch.map(
                    List.of(1),
                    value -> started.incrementAndGet(),
                    new Semaphore(1),
                    Duration.ZERO))
        .isInstanceOf(BoundedBatch.DeadlineExceededException.class);
    assertThat(started).hasValue(0);
  }

  @Test
  void rejectsOversizedInputsBeforeScheduling() {
    List<Integer> inputs = java.util.Collections.nCopies(501, 1);
    Semaphore permits = new Semaphore(1);
    Duration timeout = Duration.ofSeconds(1);
    assertThatThrownBy(() -> BoundedBatch.map(inputs, value -> value, permits, timeout))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
