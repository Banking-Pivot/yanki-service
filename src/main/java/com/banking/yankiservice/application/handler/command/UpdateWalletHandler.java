package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.command.UpdateWalletCommand;
import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.port.in.UpdateWalletUseCase;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Wallet;
import java.time.Clock;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Replaces the IMEI and the email of a wallet. The identity document and the mobile number are not
 * part of the change. A closed wallet is refused.
 */
public class UpdateWalletHandler implements UpdateWalletUseCase {

  private final WalletStorePort wallets;
  private final Clock clock;

  public UpdateWalletHandler(WalletStorePort wallets, Clock clock) {
    this.wallets = Objects.requireNonNull(wallets);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Mono<Wallet> execute(UpdateWalletCommand command) {
    return Mono.defer(() -> attempt(command)).retryWhen(Conflicts.retry());
  }

  private Mono<Wallet> attempt(UpdateWalletCommand command) {
    return wallets
        .findById(command.walletId())
        .switchIfEmpty(Mono.error(() -> new WalletNotFoundException("The wallet does not exist.")))
        .flatMap(
            wallet -> {
              Wallet updated =
                  wallet.updateContact(command.imei(), command.email(), clock.instant());
              return wallets.update(updated, wallet.getVersion()).thenReturn(updated);
            });
  }
}
