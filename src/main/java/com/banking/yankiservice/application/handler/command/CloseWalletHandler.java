package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.port.in.CloseWalletUseCase;
import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Wallet;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Closes a wallet for good. The row and its movements stay, so they can still be read, and the
 * mobile number and the document stay taken.
 *
 * <p>A wallet is closed only when nothing is left in it or on its way: no card linked or being
 * checked, balance zero and no payment still waiting for Transaction Service, sent or to be
 * received. Closing a wallet that is already closed changes nothing.
 *
 * <p>The write is conditioned on the version the wallet was read with, so a top-up or a payment the
 * wallet sends at the same time makes the close start over and decide again.
 */
public class CloseWalletHandler implements CloseWalletUseCase {

  private final WalletStorePort wallets;
  private final PaymentStorePort payments;
  private final Clock clock;

  /** Creates the handler. */
  public CloseWalletHandler(WalletStorePort wallets, PaymentStorePort payments, Clock clock) {
    this.wallets = Objects.requireNonNull(wallets);
    this.payments = Objects.requireNonNull(payments);
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
              Wallet closed = wallet.close(clock.instant());
              if (closed == wallet) {
                return Mono.<Void>empty();
              }
              return payments
                  .hasPendingOf(walletId)
                  .flatMap(
                      pending ->
                          Boolean.TRUE.equals(pending)
                              ? Mono.<Void>error(Wallet::hasPendingPayment)
                              : wallets.update(closed, wallet.getVersion()));
            });
  }
}
