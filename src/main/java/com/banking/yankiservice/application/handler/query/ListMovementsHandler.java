package com.banking.yankiservice.application.handler.query;

import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.port.in.ListMovementsUseCase;
import com.banking.yankiservice.application.port.out.MovementStorePort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.application.query.ListMovementsQuery;
import com.banking.yankiservice.domain.model.WalletMovement;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Lists the movements of a wallet, newest first, or fails when the wallet does not exist. */
public class ListMovementsHandler implements ListMovementsUseCase {

  private final WalletStorePort wallets;
  private final MovementStorePort movements;

  public ListMovementsHandler(WalletStorePort wallets, MovementStorePort movements) {
    this.wallets = Objects.requireNonNull(wallets);
    this.movements = Objects.requireNonNull(movements);
  }

  @Override
  public Mono<ResultPage<WalletMovement>> execute(ListMovementsQuery query) {
    return wallets
        .findById(query.walletId())
        .switchIfEmpty(Mono.error(() -> new WalletNotFoundException("The wallet does not exist.")))
        .flatMap(wallet -> movements.findPage(query));
  }
}
