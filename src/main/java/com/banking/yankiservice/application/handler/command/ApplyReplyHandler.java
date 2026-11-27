package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.port.in.ApplyReplyUseCase;
import com.banking.yankiservice.application.port.out.MovementStorePort;
import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.application.port.out.UnitOfWorkPort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.WalletMovement;
import com.banking.yankiservice.domain.model.enums.Funding;
import com.banking.yankiservice.domain.model.vo.InboundReply;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Applies an answer of Card Service or Transaction Service.
 *
 * <p>An answer counts only while what it answers is still waiting for it: a card link that is
 * pending with that request ID, or a payment that is pending. Anything else (a repeat, an answer
 * for a link the wallet replaced or dropped, an unknown ID) is logged and ignored, so an answer
 * delivered twice never moves money twice.
 *
 * <p>A card Card Service found is linked only when its holder has the identity document of the
 * wallet; otherwise the link is rejected as {@code CARD_NOT_OWNED}.
 *
 * <p>What an answer about a payment changes:
 *
 * <ul>
 *   <li>The money moved: the payment is complete. When the receiver has no card, the amount goes
 *       into the balance of the receiving wallet. Each side that used a card gets a movement that
 *       leaves its balance as it was.
 *   <li>The money did not move: the payment is rejected. When the sender paid from the wallet
 *       balance, the amount goes back into it.
 * </ul>
 *
 * <p>The new state of the payment, the balance and its movements are written in one database
 * transaction, conditioned on the payment still being as it was read.
 */
public class ApplyReplyHandler implements ApplyReplyUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ApplyReplyHandler.class);

  private final WalletStorePort wallets;
  private final PaymentStorePort payments;
  private final MovementStorePort movements;
  private final UnitOfWorkPort unitOfWork;
  private final Clock clock;
  private final Supplier<UUID> ids;

  /**
   * Creates the handler.
   *
   * @param ids source of the IDs of the movements
   */
  public ApplyReplyHandler(
      WalletStorePort wallets,
      PaymentStorePort payments,
      MovementStorePort movements,
      UnitOfWorkPort unitOfWork,
      Clock clock,
      Supplier<UUID> ids) {
    this.wallets = Objects.requireNonNull(wallets);
    this.payments = Objects.requireNonNull(payments);
    this.movements = Objects.requireNonNull(movements);
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
    this.clock = Objects.requireNonNull(clock);
    this.ids = Objects.requireNonNull(ids);
  }

  @Override
  public Mono<Void> execute(InboundReply reply) {
    return Mono.defer(() -> apply(reply)).retryWhen(Conflicts.retry());
  }

  private Mono<Void> apply(InboundReply reply) {
    if (reply instanceof InboundReply.CardResolved resolved) {
      return answerLink(
          reply,
          (wallet, now) ->
              wallet.cardResolved(
                  resolved.transactionId(),
                  resolved.holderDocumentType(),
                  resolved.holderDocumentNumber(),
                  now));
    }
    if (reply instanceof InboundReply.CardResolutionRejected rejected) {
      return answerLink(
          reply,
          (wallet, now) ->
              wallet.cardRejected(rejected.transactionId(), rejected.rejection(), now));
    }
    if (reply instanceof InboundReply.FundsMoved moved) {
      return pendingPayment(reply).flatMap(payment -> complete(payment, moved));
    }
    InboundReply.FundsMoveRejected rejected = (InboundReply.FundsMoveRejected) reply;
    return pendingPayment(reply).flatMap(payment -> reject(payment, rejected));
  }

  // ----- card links

  private Mono<Void> answerLink(
      InboundReply reply, BiFunction<Wallet, Instant, Optional<Wallet>> change) {
    return wallets
        .findByCardLinkId(reply.transactionId())
        .flatMap(
            wallet ->
                Mono.justOrEmpty(change.apply(wallet, clock.instant()))
                    .flatMap(
                        answered -> wallets.update(answered, wallet.getVersion()).thenReturn(1)))
        .switchIfEmpty(Mono.fromSupplier(() -> ignored(reply)))
        .then();
  }

  // ----- payments

  private Mono<Payment> pendingPayment(InboundReply reply) {
    return payments
        .findById(reply.transactionId())
        .filter(Payment::isPending)
        .switchIfEmpty(Mono.fromSupplier(() -> ignored(reply)).then(Mono.<Payment>empty()));
  }

  private Mono<Void> complete(Payment payment, InboundReply.FundsMoved moved) {
    Instant now = clock.instant();
    Payment completed = payment.fundsMoved(moved.fee(), now).orElseThrow();
    return Mono.zip(wallet(payment.getFromWalletId()), wallet(payment.getToWalletId()))
        .flatMap(
            pair -> {
              Wallet sender = pair.getT1();
              Wallet receiver = pair.getT2();
              List<WalletMovement> recorded = new ArrayList<>();
              if (completed.sourceFunding() == Funding.CARD) {
                recorded.add(WalletMovement.sent(ids.get(), sender, completed, now));
              }
              boolean intoWallet = completed.destinationFunding() == Funding.WALLET;
              Wallet credited = intoWallet ? receiver.credit(completed.getAmount(), now) : receiver;
              recorded.add(WalletMovement.received(ids.get(), credited, completed, now));
              return unitOfWork.inTransaction(
                  () ->
                      payments
                          .update(completed, payment.getVersion())
                          .then(
                              intoWallet
                                  ? wallets.update(credited, receiver.getVersion())
                                  : Mono.<Void>empty())
                          .thenMany(Flux.fromIterable(recorded))
                          .concatMap(movements::insert)
                          .then());
            });
  }

  private Mono<Void> reject(Payment payment, InboundReply.FundsMoveRejected reply) {
    Instant now = clock.instant();
    Payment rejected = payment.fundsMoveRejected(reply.rejection(), now).orElseThrow();
    if (rejected.sourceFunding() == Funding.CARD) {
      return payments.update(rejected, payment.getVersion());
    }
    return wallet(payment.getFromWalletId())
        .flatMap(
            sender -> {
              Wallet refunded = sender.credit(rejected.getAmount(), now);
              WalletMovement refund = WalletMovement.refund(ids.get(), refunded, rejected, now);
              return unitOfWork.inTransaction(
                  () ->
                      payments
                          .update(rejected, payment.getVersion())
                          .then(wallets.update(refunded, sender.getVersion()))
                          .then(movements.insert(refund)));
            });
  }

  private Mono<Wallet> wallet(UUID walletId) {
    return wallets
        .findById(walletId)
        .switchIfEmpty(
            Mono.error(() -> new IllegalStateException("Wallet " + walletId + " is missing.")));
  }

  private static int ignored(InboundReply reply) {
    LOG.info(
        "Answer ignored, nothing is waiting for it. reply={}, transactionId={}",
        reply.getClass().getSimpleName(),
        reply.transactionId());
    return 0;
  }
}
