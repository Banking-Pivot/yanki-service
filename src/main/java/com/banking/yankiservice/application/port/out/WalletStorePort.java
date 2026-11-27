package com.banking.yankiservice.application.port.out;

import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.query.ListWalletsQuery;
import com.banking.yankiservice.domain.model.Wallet;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where wallets are kept. */
public interface WalletStorePort {

  /**
   * Stores a new wallet.
   *
   * @return completion, or {@code PhoneAlreadyRegisteredException} or {@code
   *     DocumentAlreadyRegisteredException} when the mobile number or the document has a wallet
   */
  Mono<Void> insert(Wallet wallet);

  Mono<Wallet> findById(UUID walletId);

  Mono<Wallet> findByPhoneNumber(String phoneNumber);

  /** The wallet whose current card link request has that ID. */
  Mono<Wallet> findByCardLinkId(UUID linkId);

  /** Whether a wallet other than the given one is linked to the card or waiting to be. */
  Mono<Boolean> isCardHeldByAnother(UUID cardId, UUID walletId);

  /**
   * Writes the changed wallet if it is still at the version it was read with.
   *
   * @param expectedVersion version of the wallet when it was read
   * @return completion, {@code ConcurrentChangeException} when something else changed it first, or
   *     {@code YankiRuleViolationException} with {@code CARD_ALREADY_LINKED} when the wallet asks
   *     for a card another wallet got in the meantime
   */
  Mono<Void> update(Wallet changed, long expectedVersion);

  /**
   * Wallets still waiting for Card Service whose question was sent before the given instant, oldest
   * first.
   */
  Flux<Wallet> findCardLinksPendingSince(Instant sentBefore, int limit);

  Mono<ResultPage<Wallet>> findPage(ListWalletsQuery query);
}
