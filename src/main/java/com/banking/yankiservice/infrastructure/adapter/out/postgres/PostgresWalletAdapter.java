package com.banking.yankiservice.infrastructure.adapter.out.postgres;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import com.banking.yankiservice.application.exception.ConcurrentChangeException;
import com.banking.yankiservice.application.exception.DocumentAlreadyRegisteredException;
import com.banking.yankiservice.application.exception.PhoneAlreadyRegisteredException;
import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.application.query.ListWalletsQuery;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.enums.CardLinkStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps wallets in PostgreSQL through Spring Data, with no SQL written by hand.
 *
 * <p>Every update is conditioned on the version the wallet was read with. Two operations that read
 * the same balance cannot both write it: the one that arrives second is told the wallet changed and
 * starts over. The table also refuses a negative balance, and a card that a second wallet asks for
 * while another one is linked to it or waiting for it.
 */
@Component
public class PostgresWalletAdapter implements WalletStorePort {

  private static final String PHONE_KEY = "uk_wallets_phone";
  private static final String DOCUMENT_KEY = "uk_wallets_document";
  private static final String ACTIVE_CARD_KEY = "uk_wallets_active_card";
  private static final String CARD_LINK_STATUS = "cardLinkStatus";
  private static final String CARD_COMMAND_SENT_AT = "cardCommandSentAt";

  private final R2dbcEntityTemplate template;

  public PostgresWalletAdapter(R2dbcEntityTemplate template) {
    this.template = template;
  }

  @Override
  public Mono<Void> insert(Wallet wallet) {
    return template
        .insert(WalletRow.of(wallet))
        .then()
        .onErrorMap(
            error -> PostgresErrors.isUniqueViolation(error, PHONE_KEY),
            PhoneAlreadyRegisteredException::new)
        .onErrorMap(
            error -> PostgresErrors.isUniqueViolation(error, DOCUMENT_KEY),
            DocumentAlreadyRegisteredException::new);
  }

  @Override
  public Mono<Wallet> findById(UUID walletId) {
    return one(where("id").is(walletId));
  }

  @Override
  public Mono<Wallet> findByPhoneNumber(String phoneNumber) {
    return one(where("phoneNumber").is(phoneNumber));
  }

  @Override
  public Mono<Wallet> findByCardLinkId(UUID linkId) {
    return one(where("cardLinkId").is(linkId));
  }

  @Override
  public Mono<Void> update(Wallet changed, long expectedVersion) {
    return template
        .update(WalletRow.class)
        .matching(query(where("id").is(changed.getId()).and("version").is(expectedVersion)))
        .apply(changesOf(WalletRow.of(changed)))
        .flatMap(
            rows ->
                rows == 1
                    ? Mono.<Void>empty()
                    : Mono.<Void>error(new ConcurrentChangeException("Wallet", changed.getId())))
        .onErrorMap(
            error -> PostgresErrors.isUniqueViolation(error, ACTIVE_CARD_KEY),
            error -> Wallet.cardAlreadyLinked());
  }

  @Override
  public Mono<Boolean> isCardHeldByAnother(UUID cardId, UUID walletId) {
    return template.exists(
        query(
            where("cardId")
                .is(cardId)
                .and(CARD_LINK_STATUS)
                .in(CardLinkStatus.PENDING.name(), CardLinkStatus.LINKED.name())
                .and("id")
                .not(walletId)),
        WalletRow.class);
  }

  @Override
  public Flux<Wallet> findCardLinksPendingSince(Instant sentBefore, int limit) {
    return template
        .select(
            query(
                    where(CARD_LINK_STATUS)
                        .is(CardLinkStatus.PENDING.name())
                        .and(CARD_COMMAND_SENT_AT)
                        .lessThan(sentBefore))
                .sort(Sort.by(CARD_COMMAND_SENT_AT))
                .limit(limit),
            WalletRow.class)
        .map(WalletRow::toWallet);
  }

  @Override
  public Mono<ResultPage<Wallet>> findPage(ListWalletsQuery request) {
    Criteria filter =
        request.phoneNumber() == null
            ? Criteria.empty()
            : where("phoneNumber").is(request.phoneNumber());
    return Mono.zip(
            template
                .select(
                    query(filter)
                        .sort(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))
                        .offset(request.pageRequest().offset())
                        .limit(request.pageRequest().size()),
                    WalletRow.class)
                .map(WalletRow::toWallet)
                .collectList(),
            template.count(query(filter), WalletRow.class))
        .map(page -> ResultPage.of(page.getT1(), request.pageRequest(), page.getT2()));
  }

  private Mono<Wallet> one(Criteria criteria) {
    return template.selectOne(query(criteria), WalletRow.class).map(WalletRow::toWallet);
  }

  // The document and the mobile number of the wallet never change. Its status, its contact data,
  // the balance and the whole card link do; a wallet that was unlinked gets its card columns
  // emptied.
  private static Update changesOf(WalletRow row) {
    return Update.update("balance", row.balance())
        .set("status", row.status().name())
        .set("imei", row.imei())
        .set("email", row.email())
        .set("cardId", row.cardId())
        .set("cardLinkId", row.cardLinkId())
        .set(CARD_LINK_STATUS, row.cardLinkStatus())
        .set("cardRejectionCode", row.cardRejectionCode())
        .set("cardRejectionMessage", row.cardRejectionMessage())
        .set("cardCorrelationId", row.cardCorrelationId())
        .set(CARD_COMMAND_SENT_AT, row.cardCommandSentAt())
        .set("cardCommandAttempts", row.cardCommandAttempts())
        .set("updatedAt", row.updatedAt())
        .set("version", row.version());
  }
}
