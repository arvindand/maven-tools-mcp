package com.arvindand.mcp.maven.util;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
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
    long start = System.nanoTime();
    try {
      assertThatThrownBy(
              () ->
                  BoundedBatch.map(
                      List.of(1),
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
                      new Semaphore(1),
                      Duration.ofMillis(100)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("deadline");
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    } finally {
      release.countDown();
    }
  }

  @Test
  void permitQueueTimeIsPartOfTheSameDeadline() {
    assertThatThrownBy(
            () ->
                BoundedBatch.map(
                    List.of(1), value -> value, new Semaphore(0), Duration.ofMillis(50)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("deadline");
  }

  @Test
  void rejectsOversizedInputsBeforeScheduling() {
    assertThatThrownBy(
            () ->
                BoundedBatch.map(
                    java.util.Collections.nCopies(501, 1),
                    value -> value,
                    new Semaphore(1),
                    Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
