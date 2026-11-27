package com.banking.yankiservice.application.handler.command;

import static com.banking.yankiservice.support.Fixtures.ANA;
import static com.banking.yankiservice.support.Fixtures.ANA_CARD;
import static com.banking.yankiservice.support.Fixtures.ANA_ID;
import static com.banking.yankiservice.support.Fixtures.ANA_LINK;
import static com.banking.yankiservice.support.Fixtures.BETO_CARD;
import static com.banking.yankiservice.support.Fixtures.BETO_ID;
import static com.banking.yankiservice.support.Fixtures.BETO_LINK;
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
import com.banking.yankiservice.application.command.RegisterWalletCommand;
import com.banking.yankiservice.application.command.TopUpWalletCommand;
import com.banking.yankiservice.application.exception.ConcurrentChangeException;
import com.banking.yankiservice.application.exception.DocumentAlreadyRegisteredException;
import com.banking.yankiservice.application.exception.OperationIdConflictException;
import com.banking.yankiservice.application.exception.PhoneAlreadyRegisteredException;
import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.model.RecordedTopUp;
import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.WalletMovement;
import com.banking.yankiservice.domain.model.enums.CardLinkStatus;
import com.banking.yankiservice.domain.model.enums.DocumentType;
import com.banking.yankiservice.domain.model.enums.Funding;
import com.banking.yankiservice.domain.model.enums.MovementType;
import com.banking.yankiservice.domain.model.vo.OutboundCommand;
import com.banking.yankiservice.support.InMemoryStores;
import com.banking.yankiservice.support.RecordingPublisher;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Opening a wallet, top-ups and the link to a debit card. */
class WalletHandlersTest {

  private static final UUID OPERATION = UUID.fromString("00000000-0000-4000-8000-000000000001");

  private final InMemoryStores stores = new InMemoryStores();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final RegisterWalletHandler register =
      new RegisterWalletHandler(stores.wallets(), clockAt(0), () -> ANA_ID);
  private final TopUpWalletHandler topUp =
      new TopUpWalletHandler(stores.wallets(), stores.movements(), stores, clockAt(10), ids());
  private final LinkCardHandler link =
      new LinkCardHandler(
          stores.wallets(), new CommandDispatcher(publisher), clockAt(10), () -> ANA_LINK);
  private final UnlinkCardHandler unlink = new UnlinkCardHandler(stores.wallets(), clockAt(20));

  // ----- opening a wallet

  @Test
  void aWalletIsOpenedEmpty() {
    Wallet wallet = register.execute(registration(ANA, "45678912")).block();

    assertThat(wallet.getId()).isEqualTo(ANA_ID);
    assertThat(wallet.getBalance()).isEqualByComparingTo("0.00");
    assertThat(wallet.getCreatedAt()).isEqualTo(START);
    assertThat(stores.wallet(ANA_ID)).isSameAs(wallet);
  }

  @Test
  void aMobileNumberAndADocumentHaveOneWalletEach() {
    stores.put(ana("0.00"));
    RegisterWalletHandler another =
        new RegisterWalletHandler(stores.wallets(), clockAt(0), () -> BETO_ID);

    assertThatThrownBy(() -> another.execute(registration(ANA, "11111111")).block())
        .isInstanceOf(PhoneAlreadyRegisteredException.class);
    assertThatThrownBy(() -> another.execute(registration("999999999", "45678912")).block())
        .isInstanceOf(DocumentAlreadyRegisteredException.class);
    assertThat(stores.wallet(BETO_ID)).isNull();
  }

  @Test
  void invalidDataOpensNothing() {
    assertThatThrownBy(() -> register.execute(registration(ANA, "123")).block())
        .isInstanceOf(YankiRuleViolationException.class);
    assertThat(stores.wallet(ANA_ID)).isNull();
  }

  // ----- top-ups

  @Test
  void aTopUpChangesTheBalanceAndLeavesItsMovementInOneUnitOfWork() {
    stores.put(ana("10.00"));

    RecordedTopUp recorded =
        topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("25.5"))).block();

    assertThat(recorded.replayed()).isFalse();
    WalletMovement movement = recorded.movement();
    assertThat(movement.type()).isEqualTo(MovementType.TOP_UP);
    assertThat(movement.fundedBy()).isEqualTo(Funding.WALLET);
    assertThat(movement.amount()).hasToString("25.50");
    assertThat(movement.balanceAfter()).isEqualByComparingTo("35.50");
    assertThat(movement.operationId()).isEqualTo(OPERATION);
    assertThat(movement.occurredAt()).isEqualTo(START.plusSeconds(10));
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("35.50");
    assertThat(stores.movementsOf(ANA_ID)).containsExactly(movement);
    assertThat(stores.transactions()).isEqualTo(1);
  }

  @Test
  void aRepeatedTopUpCountsOnceAndADifferentAmountIsAConflict() {
    stores.put(ana("0.00"));
    RecordedTopUp first =
        topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("20.00"))).block();

    RecordedTopUp repeat =
        topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("20"))).block();

    assertThat(repeat.replayed()).isTrue();
    assertThat(repeat.movement()).isEqualTo(first.movement());
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("20.00");
    assertThatThrownBy(
            () -> topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("21.00"))).block())
        .isInstanceOf(OperationIdConflictException.class);
    assertThat(stores.movementsOf(ANA_ID)).hasSize(1);
  }

  @Test
  void twoRequestsWithTheSameOperationAtTheSameTimeAreToldApartByTheStore() {
    stores.put(ana("0.00"));
    WalletMovement rival =
        WalletMovement.topUp(
            UUID.randomUUID(),
            ana("0.00").topUp(money("20.00"), START),
            money("20.00"),
            OPERATION,
            START);
    stores.anotherRequestStoresFirst(rival);

    RecordedTopUp repeat =
        topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("20.00"))).block();

    assertThat(repeat.replayed()).isTrue();
    assertThat(repeat.movement()).isEqualTo(rival);
  }

  @Test
  void aTopUpStartsOverWhenTheWalletChangedUnderIt() {
    stores.put(ana("10.00"));
    stores.failNextWalletUpdates(2);

    topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("5.00"))).block();

    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("15.00");
    assertThat(stores.movementsOf(ANA_ID)).hasSize(1);
    assertThat(stores.transactions()).isEqualTo(3);
  }

  @Test
  void aTopUpGivesUpWhenTheWalletNeverStopsChanging() {
    stores.put(ana("10.00"));
    stores.failNextWalletUpdates(Conflicts.ATTEMPTS + 1);

    assertThatThrownBy(
            () -> topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("5.00"))).block())
        .isInstanceOf(ConcurrentChangeException.class);
    assertThat(stores.movementsOf(ANA_ID)).isEmpty();
  }

  @Test
  void aTopUpIsRefusedForAnUnknownWalletABadAmountAndALinkedWallet() {
    stores.put(linked(ana("10.00"), ANA_LINK, ANA_CARD));

    assertThatThrownBy(
            () -> topUp.execute(new TopUpWalletCommand(BETO_ID, OPERATION, money("5.00"))).block())
        .isInstanceOf(WalletNotFoundException.class);
    assertViolation(
        () -> topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("0.00"))).block(),
        RuleViolation.INVALID_AMOUNT);
    assertViolation(
        () -> topUp.execute(new TopUpWalletCommand(ANA_ID, OPERATION, money("5.00"))).block(),
        RuleViolation.WALLET_LINKED_TO_CARD);
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("10.00");
  }

  // ----- link to a debit card

  @Test
  void aLinkRequestIsStoredAndCardServiceIsAsked() {
    stores.put(ana("10.00"));

    Wallet waiting = link.execute(new LinkCardCommand(ANA_ID, ANA_CARD, "corr-9")).block();

    assertThat(waiting.getCardLink().status()).isEqualTo(CardLinkStatus.PENDING);
    assertThat(stores.wallet(ANA_ID)).isSameAs(waiting);
    assertThat(publisher.sent())
        .containsExactly(
            new RecordingPublisher.Sent(
                new OutboundCommand.ResolveCard(ANA_LINK, ANA_CARD), "corr-9"));
  }

  @Test
  void aLinkRequestIsKeptWhenKafkaIsDown() {
    stores.put(ana("10.00"));
    publisher.goDown();

    Wallet waiting = link.execute(new LinkCardCommand(ANA_ID, ANA_CARD, "corr-9")).block();

    assertThat(waiting.nextCommand()).isPresent();
    assertThat(stores.wallet(ANA_ID).getCardLink().status()).isEqualTo(CardLinkStatus.PENDING);
  }

  @Test
  void askingAgainForTheSameCardSendsNothingNew() {
    Wallet waiting = linking(ana("10.00"), ANA_LINK, ANA_CARD);
    stores.put(waiting);

    Wallet same = link.execute(new LinkCardCommand(ANA_ID, ANA_CARD, "corr-9")).block();

    assertThat(same).isSameAs(waiting);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aLinkIsRefusedForAnUnknownWalletAndForAWalletLinkedToAnotherCard() {
    stores.put(linked(ana("10.00"), ANA_LINK, ANA_CARD));

    assertThatThrownBy(() -> link.execute(new LinkCardCommand(BETO_ID, ANA_CARD, "corr-9")).block())
        .isInstanceOf(WalletNotFoundException.class);
    assertViolation(
        () -> link.execute(new LinkCardCommand(ANA_ID, BETO_CARD, "corr-9")).block(),
        RuleViolation.WALLET_ALREADY_LINKED);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aCardAnotherWalletHoldsOrWaitsForIsRefused() {
    LinkCardHandler linkBeto =
        new LinkCardHandler(
            stores.wallets(), new CommandDispatcher(publisher), clockAt(10), () -> BETO_LINK);
    Wallet beto = beto("0.00");
    stores.put(beto);

    stores.put(linked(ana("10.00"), ANA_LINK, ANA_CARD));
    assertViolation(
        () -> linkBeto.execute(new LinkCardCommand(BETO_ID, ANA_CARD, "corr-9")).block(),
        RuleViolation.CARD_ALREADY_LINKED);
    stores.put(linking(ana("10.00"), ANA_LINK, ANA_CARD));
    assertViolation(
        () -> linkBeto.execute(new LinkCardCommand(BETO_ID, ANA_CARD, "corr-9")).block(),
        RuleViolation.CARD_ALREADY_LINKED);

    assertThat(stores.wallet(BETO_ID)).isSameAs(beto);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aCardWhoseOtherRequestWasRejectedOrDroppedIsFree() {
    LinkCardHandler linkBeto =
        new LinkCardHandler(
            stores.wallets(), new CommandDispatcher(publisher), clockAt(10), () -> BETO_LINK);
    stores.put(beto("0.00"));
    stores.put(
        linking(ana("10.00"), ANA_LINK, ANA_CARD)
            .cardResolved(ANA_LINK, "DNI", "41234567", START)
            .orElseThrow());

    Wallet waiting = linkBeto.execute(new LinkCardCommand(BETO_ID, ANA_CARD, "corr-9")).block();

    assertThat(waiting.getCardLink().status()).isEqualTo(CardLinkStatus.PENDING);
    assertThat(publisher.sent()).hasSize(1);
  }

  @Test
  void theStoreRefusesACardTakenBetweenTheCheckAndTheWrite() {
    LinkCardHandler linkBeto =
        new LinkCardHandler(
            stores.wallets(), new CommandDispatcher(publisher), clockAt(10), () -> BETO_LINK);
    Wallet beto = beto("0.00");
    stores.put(beto);
    stores.put(linking(ana("10.00"), ANA_LINK, ANA_CARD));
    stores.anotherWalletTakesTheCardAfterTheCheck();

    assertViolation(
        () -> linkBeto.execute(new LinkCardCommand(BETO_ID, ANA_CARD, "corr-9")).block(),
        RuleViolation.CARD_ALREADY_LINKED);
    assertThat(stores.wallet(BETO_ID)).isSameAs(beto);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aLinkRequestStartsOverWhenTheWalletChangedUnderIt() {
    stores.put(ana("10.00"));
    stores.failNextWalletUpdates(1);

    link.execute(new LinkCardCommand(ANA_ID, ANA_CARD, "corr-9")).block();

    assertThat(stores.wallet(ANA_ID).getCardLink().cardId()).isEqualTo(ANA_CARD);
    assertThat(publisher.sent()).hasSize(1);
  }

  @Test
  void unlinkingLeavesTheWalletWithoutACardAndWithItsBalance() {
    stores.put(linked(ana("10.00"), ANA_LINK, ANA_CARD));

    unlink.execute(ANA_ID).block();
    unlink.execute(ANA_ID).block();

    assertThat(stores.wallet(ANA_ID).getCardLink()).isNull();
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("10.00");
    assertThat(stores.wallet(ANA_ID).getUpdatedAt()).isEqualTo(START.plusSeconds(20));
    assertThatThrownBy(() -> unlink.execute(BETO_ID).block())
        .isInstanceOf(WalletNotFoundException.class);
  }

  private static RegisterWalletCommand registration(String phoneNumber, String documentNumber) {
    return new RegisterWalletCommand(
        DocumentType.DNI, documentNumber, phoneNumber, "356938035643809", "ana@example.com");
  }

  private static void assertViolation(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, RuleViolation violation) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            YankiRuleViolationException.class,
            exception -> assertThat(exception.violation()).isEqualTo(violation));
  }
}
