package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.port.in.UnlinkCardUseCase;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Wallet;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Leaves a wallet without a debit card, whatever state its link was in. From then on the wallet
 * pays and receives with its own balance, which was kept as it was. A wallet without a card stays
 * as it is. Payments already requested keep the side they were given.
 */
public class UnlinkCardHandler implements UnlinkCardUseCase {

  private final WalletStorePort wallets;
  private final Clock clock;

  public UnlinkCardHandler(WalletStorePort wallets, Clock clock) {
    this.wallets = Objects.requireNonNull(wallets);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Mono<Void> execute(UUID walletId) {
    return Mono.defer(() -> attempt(walletId)).retryWhen(Conflicts.retry());
  }

  private Mono<Void> attempt(UUID walletId) {
    return wallets
        .findById(walletId)
        .switchIfEmpty(Mono.error(() -> new WalletNotFoundException("The wallet does not exist.")))
        .flatMap(
            wallet -> {
              Wallet unlinked = wallet.unlinkCard(clock.instant());
              return unlinked == wallet
                  ? Mono.<Void>empty()
                  : wallets.update(unlinked, wallet.getVersion());
            });
  }
}
