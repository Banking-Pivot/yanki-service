package com.banking.yankiservice.application.handler.command;

import static com.banking.yankiservice.support.Fixtures.ANA;
import static com.banking.yankiservice.support.Fixtures.ANA_CARD;
import static com.banking.yankiservice.support.Fixtures.ANA_ID;
import static com.banking.yankiservice.support.Fixtures.ANA_LINK;
import static com.banking.yankiservice.support.Fixtures.BETO;
import static com.banking.yankiservice.support.Fixtures.BETO_CARD;
import static com.banking.yankiservice.support.Fixtures.BETO_ID;
import static com.banking.yankiservice.support.Fixtures.BETO_LINK;
import static com.banking.yankiservice.support.Fixtures.LIMITS;
import static com.banking.yankiservice.support.Fixtures.START;
import static com.banking.yankiservice.support.Fixtures.ana;
import static com.banking.yankiservice.support.Fixtures.beto;
import static com.banking.yankiservice.support.Fixtures.clockAt;
import static com.banking.yankiservice.support.Fixtures.ids;
import static com.banking.yankiservice.support.Fixtures.linked;
import static com.banking.yankiservice.support.Fixtures.linking;
import static com.banking.yankiservice.support.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.yankiservice.application.command.LinkCardCommand;
import com.banking.yankiservice.application.command.RequestPaymentCommand;
import com.banking.yankiservice.application.command.TopUpWalletCommand;
import com.banking.yankiservice.application.command.UpdateWalletCommand;
import com.banking.yankiservice.application.exception.ConcurrentChangeException;
import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.enums.WalletStatus;
import com.banking.yankiservice.domain.model.vo.Rejection;
import com.banking.yankiservice.support.InMemoryStores;
import com.banking.yankiservice.support.RecordingPublisher;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** Updating the contact data of a wallet, closing it, and what a closed wallet refuses. */
class WalletLifecycleHandlersTest {

  private static final UUID OPERATION = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final String NEW_IMEI = "490154203237518";

  private final InMemoryStores stores = new InMemoryStores();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final UpdateWalletHandler update = new UpdateWalletHandler(stores.wallets(), clockAt(30));
  private final CloseWalletHandler close =
      new CloseWalletHandler(stores.wallets(), stores.payments(), clockAt(40));

  // ----- updating the contact data

  @Test
  void anUpdateReplacesTheImeiAndTheEmailAndNothingElse() {
    Wallet before = ana("10.00");
    stores.put(before);

    Wallet updated =
        update.execute(new UpdateWalletCommand(ANA_ID, NEW_IMEI, " Ana.New@Example.COM ")).block();

    assertThat(updated.getImei()).isEqualTo(NEW_IMEI);
    assertThat(updated.getEmail()).isEqualTo("ana.new@example.com");
    assertThat(updated.getPhoneNumber()).isEqualTo(ANA);
    assertThat(updated.getDocumentNumber()).isEqualTo(before.getDocumentNumber());
    assertThat(updated.getBalance()).isEqualByComparingTo("10.00");
    assertThat(updated.getStatus()).isEqualTo(WalletStatus.ACTIVE);
    assertThat(updated.getCreatedAt()).isEqualTo(START);
    assertThat(updated.getUpdatedAt()).isEqualTo(START.plusSeconds(30));
    assertThat(updated.getVersion()).isEqualTo(before.getVersion() + 1);
    assertThat(stores.wallet(ANA_ID)).isSameAs(updated);
  }

  @Test
  void anUpdateWithWrongDataOrOfAnUnknownWalletChangesNothing() {
    Wallet before = ana("0.00");
    stores.put(before);

    assertViolation(
        () -> update.execute(new UpdateWalletCommand(ANA_ID, "123", "ana@example.com")).block(),
        RuleViolation.INVALID_IMEI);
    assertViolation(
        () -> update.execute(new UpdateWalletCommand(ANA_ID, NEW_IMEI, "not-an-email")).block(),
        RuleViolation.INVALID_EMAIL);
    assertThatThrownBy(
            () ->
                update.execute(new UpdateWalletCommand(BETO_ID, NEW_IMEI, "b@example.com")).block())
        .isInstanceOf(WalletNotFoundException.class);
    assertThat(stores.wallet(ANA_ID)).isSameAs(before);
  }

  @Test
  void anUpdateStartsOverWhenTheWalletChangedUnderIt() {
    stores.put(ana("0.00"));
    stores.failNextWalletUpdates(2);

    update.execute(new UpdateWalletCommand(ANA_ID, NEW_IMEI, "ana.new@example.com")).block();

    assertThat(stores.wallet(ANA_ID).getImei()).isEqualTo(NEW_IMEI);
  }

  // ----- closing a wallet

  @Test
  void anEmptyWalletIsClosedAndClosingItAgainChangesNothing() {
    Wallet before = ana("0.00");
    stores.put(before);

    close.execute(ANA_ID).block();
    Wallet closed = stores.wallet(ANA_ID);
    close.execute(ANA_ID).block();

    assertThat(closed.getStatus()).isEqualTo(WalletStatus.CLOSED);
    assertThat(closed.isClosed()).isTrue();
    assertThat(closed.getPhoneNumber()).isEqualTo(ANA);
    assertThat(closed.getUpdatedAt()).isEqualTo(START.plusSeconds(40));
    assertThat(closed.getVersion()).isEqualTo(before.getVersion() + 1);
    assertThat(stores.wallet(ANA_ID)).isSameAs(closed);
    assertThatThrownBy(() -> close.execute(BETO_ID).block())
        .isInstanceOf(WalletNotFoundException.class);
  }

  @Test
  void aWalletWithMoneyIsNotClosed() {
    Wallet before = ana("0.01");
    stores.put(before);

    assertViolation(() -> close.execute(ANA_ID).block(), RuleViolation.WALLET_HAS_BALANCE);
    assertThat(stores.wallet(ANA_ID)).isSameAs(before);
  }

  @Test
  void aWalletWithACardLinkedOrBeingCheckedIsNotClosed() {
    stores.put(linked(ana("0.00"), ANA_LINK, ANA_CARD));
    assertViolation(() -> close.execute(ANA_ID).block(), RuleViolation.WALLET_HAS_CARD);

    stores.put(linking(ana("0.00"), ANA_LINK, ANA_CARD));
    assertViolation(() -> close.execute(ANA_ID).block(), RuleViolation.WALLET_HAS_CARD);

    assertThat(stores.wallet(ANA_ID).isClosed()).isFalse();
  }

  @Test
  void aWalletWhoseCardWasRejectedIsClosed() {
    stores.put(rejectedLink(ana("0.00")));

    close.execute(ANA_ID).block();

    assertThat(stores.wallet(ANA_ID).isClosed()).isTrue();
  }

  @Test
  void aWalletWithAPaymentInProgressIsNotClosedOnEitherSide() {
    Wallet ana = ana("12.50");
    Wallet beto = linked(beto("0.00"), BETO_LINK, BETO_CARD);
    Payment pending =
        Payment.request(OPERATION, ana, beto, money("12.50"), "Lunch", "corr-1", START);
    stores.put(ana.debit(money("12.50"), START));
    stores.put(beto.unlinkCard(START));
    stores.put(pending);

    assertViolation(() -> close.execute(ANA_ID).block(), RuleViolation.WALLET_HAS_PENDING_PAYMENT);
    assertViolation(() -> close.execute(BETO_ID).block(), RuleViolation.WALLET_HAS_PENDING_PAYMENT);

    stores.put(pending.fundsMoved(money("0.00"), START).orElseThrow());
    close.execute(ANA_ID).block();
    close.execute(BETO_ID).block();
    assertThat(stores.wallet(ANA_ID).isClosed()).isTrue();
    assertThat(stores.wallet(BETO_ID).isClosed()).isTrue();
  }

  @Test
  void aCloseStartsOverWhenTheWalletChangedUnderItAndGivesUpIfItNeverStops() {
    stores.put(ana("0.00"));
    stores.failNextWalletUpdates(1);

    close.execute(ANA_ID).block();

    assertThat(stores.wallet(ANA_ID).isClosed()).isTrue();

    stores.put(beto("0.00"));
    stores.failNextWalletUpdates(Conflicts.ATTEMPTS + 1);
    assertThatThrownBy(() -> close.execute(BETO_ID).block())
        .isInstanceOf(ConcurrentChangeException.class);
    assertThat(stores.wallet(BETO_ID).isClosed()).isFalse();
  }

  // ----- what a closed wallet refuses

  @Test
  void aClosedWalletIsNotUpdatedToppedUpOrLinkedToACard() {
    Wallet closed = closed(ana("0.00"));
    stores.put(closed);
    TopUpWalletHandler topUp =
        new TopUpWalletHandler(stores.wallets(), stores.movements(), stores, clockAt(50), ids());
    LinkCardHandler link =
        new LinkCardHandler(
            stores.wallets(), new CommandDispatcher(publisher), clockAt(50), () -> ANA_LINK);

    assertViolation(
        () -> update.execute(new UpdateWalletCommand(ANA_ID, NEW_IMEI, "a@example.com")).block(),
        RuleViolation.WALLET_CLOSED);
    assertViolation(
        () -> topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("5.00"))).block(),
        RuleViolation.WALLET_CLOSED);
    assertViolation(
        () -> link.execute(new LinkCardCommand(ANA_ID, ANA_CARD, "corr-9")).block(),
        RuleViolation.WALLET_CLOSED);

    assertThat(stores.wallet(ANA_ID)).isSameAs(closed);
    assertThat(stores.movementsOf(ANA_ID)).isEmpty();
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void unlinkingAClosedWalletLeavesItsRejectedCardAsItWas() {
    Wallet closed = closed(rejectedLink(ana("0.00")));
    stores.put(closed);

    new UnlinkCardHandler(stores.wallets(), clockAt(50)).execute(ANA_ID).block();

    assertThat(stores.wallet(ANA_ID)).isSameAs(closed);
    assertThat(closed.getCardLink()).isNotNull();
  }

  @Test
  void aClosedWalletNeitherPaysNorIsPaid() {
    final RequestPaymentHandler pay =
        new RequestPaymentHandler(
            stores.wallets(),
            stores.payments(),
            stores.movements(),
            stores,
            new CommandDispatcher(publisher),
            clockAt(50),
            ids(),
            LIMITS);
    Wallet ana = ana("20.00");
    Wallet closedBeto = closed(beto("0.00"));
    stores.put(ana);
    stores.put(closedBeto);

    assertViolation(
        () -> pay.execute(payment(OPERATION, ANA, BETO)).block(), RuleViolation.WALLET_CLOSED);
    assertViolation(
        () -> pay.execute(payment(UUID.randomUUID(), BETO, ANA)).block(),
        RuleViolation.WALLET_CLOSED);

    assertThat(stores.payment(OPERATION)).isNull();
    assertThat(stores.wallet(ANA_ID)).isSameAs(ana);
    assertThat(stores.wallet(BETO_ID)).isSameAs(closedBeto);
    assertThat(stores.movementsOf(ANA_ID)).isEmpty();
    assertThat(publisher.sent()).isEmpty();
  }

  private static Wallet closed(Wallet wallet) {
    return wallet.close(START.plusSeconds(1));
  }

  private static Wallet rejectedLink(Wallet wallet) {
    return linking(wallet, ANA_LINK, ANA_CARD)
        .cardRejected(ANA_LINK, new Rejection("CARD_NOT_ACTIVE", "The card is blocked."), START)
        .orElseThrow();
  }

  private static RequestPaymentCommand payment(UUID operationId, String from, String to) {
    return new RequestPaymentCommand(operationId, from, to, money("5.00"), "Lunch", "corr-1");
  }

  private static void assertViolation(ThrowingCallable action, RuleViolation violation) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            YankiRuleViolationException.class,
            exception -> assertThat(exception.violation()).isEqualTo(violation));
  }
}
