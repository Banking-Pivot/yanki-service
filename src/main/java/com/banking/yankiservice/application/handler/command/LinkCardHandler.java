package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.command.LinkCardCommand;
import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.port.in.LinkCardUseCase;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Wallet;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Records that a wallet asked to be linked to a debit card and asks Card Service whether the card
 * exists and is active.
 *
 * <p>The wallet is not linked yet: it keeps paying from its own balance until Card Service says
 * yes. Asking again for the card the wallet already asked for, or is linked to, changes nothing.
 *
 * <p>A card has one wallet: a card another wallet is linked to, or waiting for, is refused with
 * {@code CARD_ALREADY_LINKED}. The store refuses it too, for two wallets that ask at the same time.
 */
public class LinkCardHandler implements LinkCardUseCase {

  private final WalletStorePort wallets;
  private final CommandDispatcher dispatcher;
  private final Clock clock;
  private final Supplier<UUID> ids;

  /**
   * Creates the handler.
   *
   * @param ids source of the ID of the link request
   */
  public LinkCardHandler(
      WalletStorePort wallets, CommandDispatcher dispatcher, Clock clock, Supplier<UUID> ids) {
    this.wallets = Objects.requireNonNull(wallets);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.ids = Objects.requireNonNull(ids);
  }

  @Override
  public Mono<Wallet> execute(LinkCardCommand command) {
    return Mono.defer(() -> attempt(command)).retryWhen(Conflicts.retry());
  }

  private Mono<Wallet> attempt(LinkCardCommand command) {
    return wallets
        .findById(command.walletId())
        .switchIfEmpty(Mono.error(() -> new WalletNotFoundException("The wallet does not exist.")))
        .flatMap(
            wallet -> {
              Wallet requested =
                  wallet.requestCardLink(
                      ids.get(), command.cardId(), command.correlationId(), clock.instant());
              if (requested == wallet) {
                return Mono.just(wallet);
              }
              return wallets
                  .isCardHeldByAnother(command.cardId(), wallet.getId())
                  .flatMap(
                      held ->
                          Boolean.TRUE.equals(held)
                              ? Mono.<Void>error(Wallet::cardAlreadyLinked)
                              : wallets.update(requested, wallet.getVersion()))
                  .then(dispatcher.dispatch(requested))
                  .thenReturn(requested);
            });
  }
}
