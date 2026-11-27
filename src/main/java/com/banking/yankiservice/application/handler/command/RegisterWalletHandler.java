package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.command.RegisterWalletCommand;
import com.banking.yankiservice.application.port.in.RegisterWalletUseCase;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Wallet;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Opens a wallet with balance zero. The store refuses a mobile number or an identity document that
 * already has one.
 */
public class RegisterWalletHandler implements RegisterWalletUseCase {

  private final WalletStorePort wallets;
  private final Clock clock;
  private final Supplier<UUID> ids;

  /**
   * Creates the handler.
   *
   * @param ids source of the ID of the new wallet
   */
  public RegisterWalletHandler(WalletStorePort wallets, Clock clock, Supplier<UUID> ids) {
    this.wallets = Objects.requireNonNull(wallets);
    this.clock = Objects.requireNonNull(clock);
    this.ids = Objects.requireNonNull(ids);
  }

  @Override
  public Mono<Wallet> execute(RegisterWalletCommand command) {
    return Mono.fromCallable(
            () ->
                Wallet.register(
                    ids.get(),
                    command.documentType(),
                    command.documentNumber(),
                    command.phoneNumber(),
                    command.imei(),
                    command.email(),
                    clock.instant()))
        .flatMap(wallet -> wallets.insert(wallet).thenReturn(wallet));
  }
}
