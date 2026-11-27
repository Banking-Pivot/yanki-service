package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.command.RequestPaymentCommand;
import com.banking.yankiservice.application.exception.OperationIdConflictException;
import com.banking.yankiservice.application.exception.PaymentAlreadyExistsException;
import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.model.RequestedPayment;
import com.banking.yankiservice.application.port.in.RequestPaymentUseCase;
import com.banking.yankiservice.application.port.out.MovementStorePort;
import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.application.port.out.UnitOfWorkPort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Amounts;
import com.banking.yankiservice.domain.model.Funding;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.PaymentLimits;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.WalletMovement;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Records a payment between two mobile numbers, once per operationId, and moves what this service
 * can move by itself.
 *
 * <p>Which side uses a debit card is decided here, from the wallets as they are now, and stays with
 * the payment:
 *
 * <ul>
 *   <li>Neither side linked: the sender's balance goes down and the receiver's goes up, and the
 *       payment is complete.
 *   <li>Sender linked: nothing moves here. Transaction Service is asked to charge the main account
 *       of the sender's card (and to credit the receiver's, when that side is linked too).
 *   <li>Only the receiver linked: the sender's balance goes down at once and Transaction Service is
 *       asked to credit the main account of the receiver's card.
 * </ul>
 *
 * <p>Before anything is stored the payment must fit the limits: the most one payment can move and
 * the most the sender can send in a day, counting what it already sent today that was not rejected.
 * Every payment writes its sender, with the version it was read with, in the same transaction as
 * the payment, so two payments of one wallet cannot both be decided against the same sum: the
 * second one starts over and counts the first.
 *
 * <p>The payment, the new balances and their movements are written in one database transaction, so
 * a balance never changes without its payment. The request to Transaction Service leaves after
 * that; if it cannot be sent the payment stays pending and the periodic re-send delivers it.
 *
 * <p>A repeat of a payment that is already recorded changes nothing and gets the payment back as it
 * is now; the same operationId with other data is refused.
 */
public class RequestPaymentHandler implements RequestPaymentUseCase {

  private final WalletStorePort wallets;
  private final PaymentStorePort payments;
  private final MovementStorePort movements;
  private final UnitOfWorkPort unitOfWork;
  private final CommandDispatcher dispatcher;
  private final Clock clock;
  private final Supplier<UUID> ids;
  private final PaymentLimits limits;

  /**
   * Creates the handler.
   *
   * @param ids source of the IDs of the movements
   * @param limits most one payment can move and most a wallet can send in a day
   */
  public RequestPaymentHandler(
      WalletStorePort wallets,
      PaymentStorePort payments,
      MovementStorePort movements,
      UnitOfWorkPort unitOfWork,
      CommandDispatcher dispatcher,
      Clock clock,
      Supplier<UUID> ids,
      PaymentLimits limits) {
    this.wallets = Objects.requireNonNull(wallets);
    this.payments = Objects.requireNonNull(payments);
    this.movements = Objects.requireNonNull(movements);
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.ids = Objects.requireNonNull(ids);
    this.limits = Objects.requireNonNull(limits);
  }

  @Override
  public Mono<RequestedPayment> execute(RequestPaymentCommand command) {
    return Mono.fromCallable(() -> validAmount(command))
        .flatMap(
            amount ->
                payments
                    .findById(command.operationId())
                    .map(first -> repeated(first, command, amount))
                    .switchIfEmpty(Mono.defer(() -> create(command, amount))));
  }

  private static BigDecimal validAmount(RequestPaymentCommand command) {
    if (command.fromPhoneNumber().equals(command.toPhoneNumber())) {
      throw Payment.sameWallet();
    }
    return Amounts.positive(command.amount());
  }

  private static RequestedPayment repeated(
      Payment first, RequestPaymentCommand command, BigDecimal amount) {
    if (!first.sameRequestAs(
        command.fromPhoneNumber(), command.toPhoneNumber(), amount, command.description())) {
      throw new OperationIdConflictException(command.operationId());
    }
    return new RequestedPayment(first, true);
  }

  private Mono<RequestedPayment> create(RequestPaymentCommand command, BigDecimal amount) {
    limits.checkPayment(amount);
    return Mono.defer(() -> attempt(command, amount))
        .retryWhen(Conflicts.retry())
        .flatMap(payment -> dispatcher.dispatch(payment).thenReturn(payment))
        .map(payment -> new RequestedPayment(payment, false))
        // A request with the same operationId stored its payment first.
        .onErrorResume(
            PaymentAlreadyExistsException.class,
            duplicate ->
                payments
                    .findById(command.operationId())
                    .map(first -> repeated(first, command, amount)));
  }

  private Mono<Payment> attempt(RequestPaymentCommand command, BigDecimal amount) {
    return Mono.zip(
            walletOf(command.fromPhoneNumber(), "sender"),
            walletOf(command.toPhoneNumber(), "receiver"))
        .flatMap(
            pair -> {
              Wallet sender = pair.getT1();
              Instant now = clock.instant();
              // Read after the sender: a payment stored since then moves the version of the sender
              // and makes the write below start over, so the sum is never out of date.
              return payments
                  .sumSentBetween(sender.getId(), limits.dayStart(now), limits.dayEnd(now))
                  .doOnNext(sentToday -> limits.checkDaily(sentToday, amount))
                  .then(Mono.defer(() -> store(sender, pair.getT2(), command, amount, now)));
            });
  }

  private Mono<Wallet> walletOf(String phoneNumber, String side) {
    return wallets
        .findByPhoneNumber(phoneNumber)
        .switchIfEmpty(
            Mono.error(
                () ->
                    new WalletNotFoundException(
                        "The mobile number of the " + side + " has no wallet.")));
  }

  private Mono<Payment> store(
      Wallet sender,
      Wallet receiver,
      RequestPaymentCommand command,
      BigDecimal amount,
      Instant now) {
    Payment payment =
        Payment.request(
            command.operationId(),
            sender,
            receiver,
            amount,
            command.description(),
            command.correlationId(),
            now);
    List<Change> changes = new ArrayList<>();
    List<WalletMovement> recorded = new ArrayList<>();
    if (payment.sourceFunding() == Funding.WALLET) {
      Wallet debited = sender.debit(amount, now);
      changes.add(new Change(debited, sender.getVersion()));
      recorded.add(WalletMovement.sent(ids.get(), debited, payment, now));
    } else {
      // The balance stays, but the sender is written all the same: see Wallet#paymentRequested.
      changes.add(new Change(sender.paymentRequested(), sender.getVersion()));
    }
    if (payment.isLocal()) {
      Wallet credited = receiver.credit(amount, now);
      changes.add(new Change(credited, receiver.getVersion()));
      recorded.add(WalletMovement.received(ids.get(), credited, payment, now));
    }
    // Always in the same order, so two payments that cross each other never wait on one another.
    changes.sort(Comparator.comparing(change -> change.wallet().getId()));
    return unitOfWork.inTransaction(
        () ->
            payments
                .insert(payment)
                .thenMany(Flux.fromIterable(changes))
                .concatMap(change -> wallets.update(change.wallet(), change.expectedVersion()))
                .thenMany(Flux.fromIterable(recorded))
                .concatMap(movements::insert)
                .then(Mono.just(payment)));
  }

  /** A wallet to write and the version it was read with. */
  private record Change(Wallet wallet, long expectedVersion) {}
}
