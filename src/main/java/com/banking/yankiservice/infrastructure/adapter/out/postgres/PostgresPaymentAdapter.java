package com.banking.yankiservice.infrastructure.adapter.out.postgres;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import com.banking.yankiservice.application.exception.ConcurrentChangeException;
import com.banking.yankiservice.application.exception.PaymentAlreadyExistsException;
import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps payments in PostgreSQL through Spring Data, with no SQL written by hand.
 *
 * <p>Every update is conditioned on the version the payment was read with, so an answer delivered
 * twice, or an answer and the periodic re-send, never overwrite each other: the one that arrives
 * second is told the payment changed.
 */
@Component
public class PostgresPaymentAdapter implements PaymentStorePort {

  private static final String PRIMARY_KEY = "pk_payments";
  private static final String COMMAND_SENT_AT = "commandSentAt";
  private static final String CREATED_AT = "createdAt";
  private static final String STATUS = "status";
  private static final String FROM_WALLET_ID = "fromWalletId";

  private final R2dbcEntityTemplate template;

  public PostgresPaymentAdapter(R2dbcEntityTemplate template) {
    this.template = template;
  }

  @Override
  public Mono<Void> insert(Payment payment) {
    return template
        .insert(PaymentRow.of(payment))
        .then()
        .onErrorMap(
            error -> PostgresErrors.isUniqueViolation(error, PRIMARY_KEY),
            error -> new PaymentAlreadyExistsException(payment.getId(), error));
  }

  @Override
  public Mono<Payment> findById(UUID paymentId) {
    return template
        .selectOne(query(where("id").is(paymentId)), PaymentRow.class)
        .map(PaymentRow::toPayment);
  }

  @Override
  public Mono<Void> update(Payment changed, long expectedVersion) {
    return template
        .update(PaymentRow.class)
        .matching(query(where("id").is(changed.getId()).and("version").is(expectedVersion)))
        .apply(changesOf(changed))
        .flatMap(
            rows ->
                rows == 1
                    ? Mono.<Void>empty()
                    : Mono.error(new ConcurrentChangeException("Payment", changed.getId())));
  }

  @Override
  public Mono<Boolean> hasPendingOf(UUID walletId) {
    return template.exists(
        query(
            where(STATUS)
                .is(PaymentStatus.PENDING.name())
                .and(where(FROM_WALLET_ID).is(walletId).or("toWalletId").is(walletId))),
        PaymentRow.class);
  }

  @Override
  public Flux<Payment> findPendingSince(Instant sentBefore, int limit) {
    return template
        .select(
            query(
                    where(STATUS)
                        .is(PaymentStatus.PENDING.name())
                        .and(COMMAND_SENT_AT)
                        .lessThan(sentBefore))
                .sort(Sort.by(COMMAND_SENT_AT))
                .limit(limit),
            PaymentRow.class)
        .map(PaymentRow::toPayment);
  }

  // Spring Data has no sum through Criteria and this service writes no SQL by hand: the payments
  // of the day are read and added here. A day holds few of them, the daily limit sees to that.
  @Override
  public Mono<BigDecimal> sumSentBetween(UUID fromWalletId, Instant from, Instant until) {
    return template
        .select(
            query(
                where(FROM_WALLET_ID)
                    .is(fromWalletId)
                    .and(CREATED_AT)
                    .greaterThanOrEquals(from)
                    .and(CREATED_AT)
                    .lessThan(until)
                    .and(STATUS)
                    .not(PaymentStatus.REJECTED.name())),
            PaymentRow.class)
        .map(PaymentRow::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  // The request data never changes. The fee and the rejection only go from empty to set; a value
  // that is still empty is left out of the statement.
  private static Update changesOf(Payment changed) {
    Update update =
        Update.update(STATUS, changed.getStatus().name())
            .set(COMMAND_SENT_AT, changed.getCommandSentAt())
            .set("commandAttempts", changed.getCommandAttempts())
            .set("updatedAt", changed.getUpdatedAt())
            .set("version", changed.getVersion());
    if (changed.getFee() != null) {
      update = update.set("fee", changed.getFee());
    }
    if (changed.getRejection() != null) {
      update =
          update
              .set("rejectionCode", changed.getRejection().code())
              .set("rejectionMessage", changed.getRejection().message());
    }
    return update;
  }
}
