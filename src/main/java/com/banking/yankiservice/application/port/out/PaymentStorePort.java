package com.banking.yankiservice.application.port.out;

import com.banking.yankiservice.domain.model.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where payments are kept. */
public interface PaymentStorePort {

  /**
   * Stores a new payment.
   *
   * @return completion, or {@code PaymentAlreadyExistsException} when its ID is taken
   */
  Mono<Void> insert(Payment payment);

  Mono<Payment> findById(UUID paymentId);

  /**
   * Writes the changed payment if it is still at the version it was read with.
   *
   * @param expectedVersion version of the payment when it was read
   * @return completion, or {@code ConcurrentChangeException} when something else changed it first
   */
  Mono<Void> update(Payment changed, long expectedVersion);

  /**
   * What a wallet sent in a span of time: the amounts of the payments it requested from {@code
   * from} (included) to {@code until} (excluded) that are not rejected, added up.
   *
   * @return the sum, zero when there is no such payment
   */
  Mono<BigDecimal> sumSentBetween(UUID fromWalletId, Instant from, Instant until);

  /**
   * Payments still waiting for Transaction Service whose request was sent before the given instant,
   * oldest first.
   */
  Flux<Payment> findPendingSince(Instant sentBefore, int limit);
}
