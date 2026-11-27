package com.banking.yankiservice.application.handler.query;

import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.port.in.GetWalletUseCase;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Wallet;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Reads one wallet, or fails when it does not exist. */
public class GetWalletHandler implements GetWalletUseCase {

  private final WalletStorePort wallets;

  public GetWalletHandler(WalletStorePort wallets) {
    this.wallets = Objects.requireNonNull(wallets);
  }

  @Override
  public Mono<Wallet> execute(UUID walletId) {
    return wallets
        .findById(walletId)
        .switchIfEmpty(Mono.error(() -> new WalletNotFoundException("The wallet does not exist.")));
  }
}
