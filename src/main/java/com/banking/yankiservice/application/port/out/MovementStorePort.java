package com.banking.yankiservice.application.port.out;

import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.query.ListMovementsQuery;
import com.banking.yankiservice.domain.model.WalletMovement;
import com.banking.yankiservice.domain.model.enums.MovementType;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Where the movements of the wallets are kept. */
public interface MovementStorePort {

  /**
   * Stores a movement.
   *
   * @return completion, or {@code MovementAlreadyExistsException} when the wallet already has a
   *     movement of that type for that operation
   */
  Mono<Void> insert(WalletMovement movement);

  /** The movement of that type an operation left in a wallet. */
  Mono<WalletMovement> findByOperation(UUID walletId, UUID operationId, MovementType type);

  /** A page of the movements of a wallet, newest first. */
  Mono<ResultPage<WalletMovement>> findPage(ListMovementsQuery query);
}
