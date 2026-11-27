package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.exception.ConcurrentChangeException;
import com.banking.yankiservice.application.port.in.ResendPendingCommandsUseCase;
import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.Wallet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sends again the request of every payment and every card link that has waited too long for an
 * answer.
 *
 * <p>This is what makes them finish when a message is lost on the way: the request never reached
 * the broker, the service that had to answer was down, or its answer could not be stored here.
 * Transaction Service answers a repeated MOVE_FUNDS with the result of the first one, and
 * RESOLVE_CARD only reads, so a repeat never moves money twice. A request is sent again for as long
 * as it waits; each time is counted and logged.
 */
public class ResendPendingCommandsHandler implements ResendPendingCommandsUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ResendPendingCommandsHandler.class);

  private final WalletStorePort wallets;
  private final PaymentStorePort payments;
  private final CommandDispatcher dispatcher;
  private final Clock clock;
  private final Duration resendAfter;
  private final int batchSize;

  /**
   * Creates the handler.
   *
   * @param resendAfter how long a request waits for its answer before it is sent again
   * @param batchSize most payments, and most card links, handled in one run
   */
  public ResendPendingCommandsHandler(
      WalletStorePort wallets,
      PaymentStorePort payments,
      CommandDispatcher dispatcher,
      Clock clock,
      Duration resendAfter,
      int batchSize) {
    this.wallets = Objects.requireNonNull(wallets);
    this.payments = Objects.requireNonNull(payments);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.resendAfter = Objects.requireNonNull(resendAfter);
    this.batchSize = batchSize;
  }

  @Override
  public Mono<Long> execute() {
    return Mono.fromSupplier(clock::instant)
        .flatMap(
            now ->
                Flux.concat(
                        payments
                            .findPendingSince(now.minus(resendAfter), batchSize)
                            .concatMap(payment -> resend(payment, now)),
                        wallets
                            .findCardLinksPendingSince(now.minus(resendAfter), batchSize)
                            .concatMap(wallet -> resend(wallet, now)))
                    .count());
  }

  // The new send time is written first: if the answer arrived in the meantime the write fails and
  // there is nothing to send again.
  private Mono<Object> resend(Payment payment, Instant now) {
    Payment marked = payment.commandResent(now);
    return payments
        .update(marked, payment.getVersion())
        .then(
            Mono.fromRunnable(
                () ->
                    LOG.warn(
                        "No answer in time, sending MOVE_FUNDS again. corrId={}, paymentId={}, "
                            + "attempt={}",
                        marked.getCorrelationId(),
                        marked.getId(),
                        marked.getCommandAttempts())))
        .then(dispatcher.dispatch(marked))
        .thenReturn((Object) marked)
        .onErrorResume(ConcurrentChangeException.class, moved -> Mono.empty());
  }

  private Mono<Object> resend(Wallet wallet, Instant now) {
    Wallet marked = wallet.cardCommandResent(now);
    return wallets
        .update(marked, wallet.getVersion())
        .then(
            Mono.fromRunnable(
                () ->
                    LOG.warn(
                        "No answer in time, sending RESOLVE_CARD again. corrId={}, walletId={}, "
                            + "attempt={}",
                        marked.getCardLink().correlationId(),
                        marked.getId(),
                        marked.getCardLink().commandAttempts())))
        .then(dispatcher.dispatch(marked))
        .thenReturn((Object) marked)
        .onErrorResume(ConcurrentChangeException.class, moved -> Mono.empty());
  }
}
