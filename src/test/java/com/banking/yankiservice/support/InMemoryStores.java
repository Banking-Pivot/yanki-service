package com.banking.yankiservice.support;

import com.banking.yankiservice.application.exception.ConcurrentChangeException;
import com.banking.yankiservice.application.exception.DocumentAlreadyRegisteredException;
import com.banking.yankiservice.application.exception.MovementAlreadyExistsException;
import com.banking.yankiservice.application.exception.PaymentAlreadyExistsException;
import com.banking.yankiservice.application.exception.PhoneAlreadyRegisteredException;
import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.port.out.MovementStorePort;
import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.application.port.out.UnitOfWorkPort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.application.query.ListMovementsQuery;
import com.banking.yankiservice.application.query.ListWalletsQuery;
import com.banking.yankiservice.domain.model.CardLinkStatus;
import com.banking.yankiservice.domain.model.MovementType;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.PaymentStatus;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.WalletMovement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The three stores in memory, for unit tests of the use cases. They keep the rules of the real
 * ones: a mobile number, a document, a payment ID and a movement of an operation are stored once, a
 * card is held by one wallet, and an update needs the version the row was read with.
 */
public final class InMemoryStores implements UnitOfWorkPort {

  private final Map<UUID, Wallet> walletRows = new LinkedHashMap<>();
  private final Map<UUID, Payment> paymentRows = new LinkedHashMap<>();
  private final List<WalletMovement> movementRows = new ArrayList<>();
  private int walletConflicts;
  private int paymentConflicts;
  private Payment rivalPayment;
  private WalletMovement rivalMovement;
  private int transactions;
  private boolean cardCheckSeesNothing;
  private final List<Runnable> storedByOthers = new ArrayList<>();

  /** The next updates of a wallet find that something else changed it first. */
  public void failNextWalletUpdates(int times) {
    walletConflicts = times;
  }

  /**
   * The check of who holds a card finds nothing, as when another wallet takes the card between the
   * check and the write. The write still refuses it.
   */
  public void anotherWalletTakesTheCardAfterTheCheck() {
    cardCheckSeesNothing = true;
  }

  /** The next updates of a payment find that something else changed it first. */
  public void failNextPaymentUpdates(int times) {
    paymentConflicts = times;
  }

  /** The next insert finds that another request stored this payment a moment before. */
  public void anotherRequestStoresFirst(Payment stored) {
    rivalPayment = stored;
  }

  /** The next insert finds that another request stored this movement a moment before. */
  public void anotherRequestStoresFirst(WalletMovement stored) {
    rivalMovement = stored;
  }

  public void put(Wallet wallet) {
    walletRows.put(wallet.getId(), wallet);
  }

  public void put(Payment payment) {
    paymentRows.put(payment.getId(), payment);
  }

  public Wallet wallet(UUID walletId) {
    return walletRows.get(walletId);
  }

  public Payment payment(UUID paymentId) {
    return paymentRows.get(paymentId);
  }

  /** The movements of a wallet in the order they were stored. */
  public List<WalletMovement> movementsOf(UUID walletId) {
    return movementRows.stream().filter(movement -> movement.walletId().equals(walletId)).toList();
  }

  /** How many units of work were run. */
  public int transactions() {
    return transactions;
  }

  public WalletStorePort wallets() {
    return new Wallets();
  }

  public PaymentStorePort payments() {
    return new Payments();
  }

  public MovementStorePort movements() {
    return new Movements();
  }

  /**
   * Runs the work and, when it fails, puts everything back as it was, except what another request
   * stored in the meantime.
   */
  @Override
  public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
    return Mono.defer(
        () -> {
          transactions++;
          Map<UUID, Wallet> walletsBefore = new LinkedHashMap<>(walletRows);
          Map<UUID, Payment> paymentsBefore = new LinkedHashMap<>(paymentRows);
          List<WalletMovement> movementsBefore = new ArrayList<>(movementRows);
          storedByOthers.clear();
          return work.get()
              .doOnError(
                  failure -> {
                    walletRows.clear();
                    walletRows.putAll(walletsBefore);
                    paymentRows.clear();
                    paymentRows.putAll(paymentsBefore);
                    movementRows.clear();
                    movementRows.addAll(movementsBefore);
                    storedByOthers.forEach(Runnable::run);
                  });
        });
  }

  private final class Wallets implements WalletStorePort {

    @Override
    public Mono<Void> insert(Wallet wallet) {
      return Mono.defer(
          () -> {
            for (Wallet stored : walletRows.values()) {
              if (stored.getPhoneNumber().equals(wallet.getPhoneNumber())) {
                return Mono.error(new PhoneAlreadyRegisteredException(null));
              }
              if (stored.getDocumentType() == wallet.getDocumentType()
                  && stored.getDocumentNumber().equals(wallet.getDocumentNumber())) {
                return Mono.error(new DocumentAlreadyRegisteredException(null));
              }
            }
            walletRows.put(wallet.getId(), wallet);
            return Mono.empty();
          });
    }

    @Override
    public Mono<Wallet> findById(UUID walletId) {
      return Mono.defer(() -> Mono.justOrEmpty(walletRows.get(walletId)));
    }

    @Override
    public Mono<Wallet> findByPhoneNumber(String phoneNumber) {
      return Flux.defer(() -> Flux.fromIterable(List.copyOf(walletRows.values())))
          .filter(wallet -> wallet.getPhoneNumber().equals(phoneNumber))
          .next();
    }

    @Override
    public Mono<Wallet> findByCardLinkId(UUID linkId) {
      return Flux.defer(() -> Flux.fromIterable(List.copyOf(walletRows.values())))
          .filter(wallet -> wallet.getCardLink() != null)
          .filter(wallet -> wallet.getCardLink().linkId().equals(linkId))
          .next();
    }

    @Override
    public Mono<Void> update(Wallet changed, long expectedVersion) {
      return Mono.defer(
          () -> {
            Wallet stored = walletRows.get(changed.getId());
            if (walletConflicts > 0 || stored == null || stored.getVersion() != expectedVersion) {
              walletConflicts = Math.max(0, walletConflicts - 1);
              return Mono.error(new ConcurrentChangeException("Wallet", changed.getId()));
            }
            if (changed.getCardLink() != null
                && changed.getCardLink().status() != CardLinkStatus.REJECTED
                && heldByAnother(changed.getCardLink().cardId(), changed.getId())) {
              return Mono.error(Wallet.cardAlreadyLinked());
            }
            walletRows.put(changed.getId(), changed);
            return Mono.empty();
          });
    }

    @Override
    public Mono<Boolean> isCardHeldByAnother(UUID cardId, UUID walletId) {
      return Mono.fromSupplier(() -> !cardCheckSeesNothing && heldByAnother(cardId, walletId));
    }

    private boolean heldByAnother(UUID cardId, UUID walletId) {
      return walletRows.values().stream()
          .filter(wallet -> !wallet.getId().equals(walletId))
          .map(Wallet::getCardLink)
          .anyMatch(
              link ->
                  link != null
                      && link.status() != CardLinkStatus.REJECTED
                      && link.cardId().equals(cardId));
    }

    @Override
    public Flux<Wallet> findCardLinksPendingSince(Instant sentBefore, int limit) {
      return Flux.defer(() -> Flux.fromIterable(List.copyOf(walletRows.values())))
          .filter(wallet -> wallet.nextCommand().isPresent())
          .filter(wallet -> wallet.getCardLink().commandSentAt().isBefore(sentBefore))
          .sort(Comparator.comparing(wallet -> wallet.getCardLink().commandSentAt()))
          .take(limit);
    }

    @Override
    public Mono<ResultPage<Wallet>> findPage(ListWalletsQuery query) {
      return Mono.fromSupplier(
          () -> {
            List<Wallet> matching =
                walletRows.values().stream()
                    .filter(
                        wallet ->
                            query.phoneNumber() == null
                                || wallet.getPhoneNumber().equals(query.phoneNumber()))
                    .toList();
            return ResultPage.of(
                matching.stream()
                    .skip(query.pageRequest().offset())
                    .limit(query.pageRequest().size())
                    .toList(),
                query.pageRequest(),
                matching.size());
          });
    }
  }

  private final class Payments implements PaymentStorePort {

    @Override
    public Mono<Void> insert(Payment payment) {
      return Mono.defer(
          () -> {
            if (rivalPayment != null) {
              Payment rival = rivalPayment;
              rivalPayment = null;
              paymentRows.put(rival.getId(), rival);
              storedByOthers.add(() -> paymentRows.put(rival.getId(), rival));
            }
            if (paymentRows.containsKey(payment.getId())) {
              return Mono.error(new PaymentAlreadyExistsException(payment.getId(), null));
            }
            paymentRows.put(payment.getId(), payment);
            return Mono.empty();
          });
    }

    @Override
    public Mono<Payment> findById(UUID paymentId) {
      return Mono.defer(() -> Mono.justOrEmpty(paymentRows.get(paymentId)));
    }

    @Override
    public Mono<Void> update(Payment changed, long expectedVersion) {
      return Mono.defer(
          () -> {
            Payment stored = paymentRows.get(changed.getId());
            if (paymentConflicts > 0 || stored == null || stored.getVersion() != expectedVersion) {
              paymentConflicts = Math.max(0, paymentConflicts - 1);
              return Mono.error(new ConcurrentChangeException("Payment", changed.getId()));
            }
            paymentRows.put(changed.getId(), changed);
            return Mono.empty();
          });
    }

    @Override
    public Mono<BigDecimal> sumSentBetween(UUID fromWalletId, Instant from, Instant until) {
      return Flux.defer(() -> Flux.fromIterable(List.copyOf(paymentRows.values())))
          .filter(payment -> payment.getFromWalletId().equals(fromWalletId))
          .filter(payment -> !payment.getCreatedAt().isBefore(from))
          .filter(payment -> payment.getCreatedAt().isBefore(until))
          .filter(payment -> payment.getStatus() != PaymentStatus.REJECTED)
          .map(Payment::getAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public Flux<Payment> findPendingSince(Instant sentBefore, int limit) {
      return Flux.defer(() -> Flux.fromIterable(List.copyOf(paymentRows.values())))
          .filter(Payment::isPending)
          .filter(payment -> payment.getCommandSentAt().isBefore(sentBefore))
          .sort(Comparator.comparing(Payment::getCommandSentAt))
          .take(limit);
    }
  }

  private final class Movements implements MovementStorePort {

    @Override
    public Mono<Void> insert(WalletMovement movement) {
      return Mono.defer(
          () -> {
            if (rivalMovement != null) {
              WalletMovement rival = rivalMovement;
              rivalMovement = null;
              movementRows.add(rival);
              storedByOthers.add(() -> movementRows.add(rival));
            }
            boolean taken =
                movementRows.stream()
                    .anyMatch(
                        stored ->
                            stored.walletId().equals(movement.walletId())
                                && stored.operationId().equals(movement.operationId())
                                && stored.type() == movement.type());
            if (taken) {
              return Mono.error(new MovementAlreadyExistsException(movement.operationId(), null));
            }
            movementRows.add(movement);
            return Mono.empty();
          });
    }

    @Override
    public Mono<WalletMovement> findByOperation(
        UUID walletId, UUID operationId, MovementType type) {
      return Flux.defer(() -> Flux.fromIterable(List.copyOf(movementRows)))
          .filter(movement -> movement.walletId().equals(walletId))
          .filter(movement -> movement.operationId().equals(operationId))
          .filter(movement -> movement.type() == type)
          .next();
    }

    @Override
    public Mono<ResultPage<WalletMovement>> findPage(ListMovementsQuery query) {
      return Mono.fromSupplier(
          () -> {
            List<WalletMovement> matching = movementsOf(query.walletId());
            return ResultPage.of(
                matching.stream()
                    .skip(query.pageRequest().offset())
                    .limit(query.pageRequest().size())
                    .toList(),
                query.pageRequest(),
                matching.size());
          });
    }
  }
}
