package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.exception.ConcurrentChangeException;
import java.time.Duration;
import reactor.util.retry.Retry;

/**
 * What to do when a wallet or a payment changed between the read and the write: read it again and
 * decide again.
 *
 * <p>Two operations on the same wallet at the same time both read the same balance; the database
 * lets only one of them write, and the other one starts over with the new balance. That is what
 * keeps the balance right under concurrent payments, without holding a lock while deciding.
 */
final class Conflicts {

  /** How many times an operation starts over before it gives up. */
  static final int ATTEMPTS = 10;

  private static final Duration FIRST_WAIT = Duration.ofMillis(5);
  private static final Duration LONGEST_WAIT = Duration.ofMillis(80);

  private Conflicts() {}

  /**
   * Starts over after a short, growing and slightly random wait, so the operations that collided do
   * not collide again all together. Any other error is passed on at once, and so is the conflict
   * itself once the attempts run out.
   */
  static Retry retry() {
    return Retry.backoff(ATTEMPTS, FIRST_WAIT)
        .maxBackoff(LONGEST_WAIT)
        .filter(ConcurrentChangeException.class::isInstance)
        .onRetryExhaustedThrow((spec, signal) -> signal.failure());
  }
}
