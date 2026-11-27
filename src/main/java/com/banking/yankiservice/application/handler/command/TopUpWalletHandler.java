package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.command.TopUpWalletCommand;
import com.banking.yankiservice.application.exception.MovementAlreadyExistsException;
import com.banking.yankiservice.application.exception.OperationIdConflictException;
import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.model.RecordedTopUp;
import com.banking.yankiservice.application.port.in.TopUpWalletUseCase;
import com.banking.yankiservice.application.port.out.MovementStorePort;
import com.banking.yankiservice.application.port.out.UnitOfWorkPort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.Amounts;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.WalletMovement;
import com.banking.yankiservice.domain.model.enums.MovementType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Loads money into the balance of a wallet, once per operationId.
 *
 * <p>The new balance and the movement that explains it are written in one database transaction. A
 * repeat of a top-up that is already recorded changes nothing and gets the same movement back; the
 * same operationId with another amount is refused. Two requests with the same operationId that
 * arrive together are told apart by the store, which accepts one movement per wallet and operation.
 */
public class TopUpWalletHandler implements TopUpWalletUseCase {

  private final WalletStorePort wallets;
  private final MovementStorePort movements;
  private final UnitOfWorkPort unitOfWork;
  private final Clock clock;
  private final Supplier<UUID> ids;

  /**
   * Creates the handler.
   *
   * @param ids source of the ID of the movement
   */
  public TopUpWalletHandler(
      WalletStorePort wallets,
      MovementStorePort movements,
      UnitOfWorkPort unitOfWork,
      Clock clock,
      Supplier<UUID> ids) {
    this.wallets = Objects.requireNonNull(wallets);
    this.movements = Objects.requireNonNull(movements);
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
    this.clock = Objects.requireNonNull(clock);
    this.ids = Objects.requireNonNull(ids);
  }

  @Override
  public Mono<RecordedTopUp> execute(TopUpWalletCommand command) {
    return Mono.fromCallable(() -> Amounts.positive(command.amount()))
        .flatMap(
            amount ->
                Mono.defer(() -> attempt(command, amount))
                    .retryWhen(Conflicts.retry())
                    .onErrorResume(
                        MovementAlreadyExistsException.class,
                        duplicate ->
                            recorded(command).map(first -> repeated(first, command, amount))));
  }

  private Mono<RecordedTopUp> attempt(TopUpWalletCommand command, BigDecimal amount) {
    return wallets
        .findById(command.walletId())
        .switchIfEmpty(Mono.error(() -> new WalletNotFoundException("The wallet does not exist.")))
        .flatMap(
            wallet ->
                recorded(command)
                    .map(first -> repeated(first, command, amount))
                    .switchIfEmpty(Mono.defer(() -> recordTopUp(wallet, command, amount))));
  }

  private Mono<WalletMovement> recorded(TopUpWalletCommand command) {
    return movements.findByOperation(
        command.walletId(), command.operationId(), MovementType.TOP_UP);
  }

  private static RecordedTopUp repeated(
      WalletMovement first, TopUpWalletCommand command, BigDecimal amount) {
    if (first.amount().compareTo(amount) != 0) {
      throw new OperationIdConflictException(command.operationId());
    }
    return new RecordedTopUp(first, true);
  }

  private Mono<RecordedTopUp> recordTopUp(
      Wallet wallet, TopUpWalletCommand command, BigDecimal amount) {
    Instant now = clock.instant();
    Wallet loaded = wallet.topUp(amount, now);
    WalletMovement movement =
        WalletMovement.topUp(ids.get(), loaded, amount, command.operationId(), now);
    return unitOfWork.inTransaction(
        () ->
            wallets
                .update(loaded, wallet.getVersion())
                .then(movements.insert(movement))
                .thenReturn(new RecordedTopUp(movement, false)));
  }
}
