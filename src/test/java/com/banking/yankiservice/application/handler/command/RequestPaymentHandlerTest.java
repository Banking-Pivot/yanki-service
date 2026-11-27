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
import static com.banking.yankiservice.support.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.yankiservice.application.command.RequestPaymentCommand;
import com.banking.yankiservice.application.exception.OperationIdConflictException;
import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.model.RequestedPayment;
import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import com.banking.yankiservice.domain.model.Funding;
import com.banking.yankiservice.domain.model.MovementType;
import com.banking.yankiservice.domain.model.OutboundCommand;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.PaymentStatus;
import com.banking.yankiservice.domain.model.Rejection;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.WalletMovement;
import com.banking.yankiservice.support.InMemoryStores;
import com.banking.yankiservice.support.RecordingPublisher;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestPaymentHandlerTest {

  private static final UUID OPERATION = UUID.fromString("00000000-0000-4000-8000-000000000001");

  private final InMemoryStores stores = new InMemoryStores();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final RequestPaymentHandler handler =
      new RequestPaymentHandler(
          stores.wallets(),
          stores.payments(),
          stores.movements(),
          stores,
          new CommandDispatcher(publisher),
          clockAt(10),
          ids(),
          LIMITS);

  @Test
  void betweenTwoWalletsWithoutACardBothBalancesChangeAtOnce() {
    stores.put(ana("50.00"));
    stores.put(beto("5.00"));

    RequestedPayment requested = handler.execute(request("12.5", "Lunch")).block();

    assertThat(requested.replayed()).isFalse();
    Payment payment = requested.payment();
    assertThat(payment.getId()).isEqualTo(OPERATION);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.getAmount()).hasToString("12.50");
    assertThat(payment.getCorrelationId()).isEqualTo("corr-1");
    assertThat(stores.payment(OPERATION)).isSameAs(payment);
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("37.50");
    assertThat(stores.wallet(BETO_ID).getBalance()).isEqualByComparingTo("17.50");
    assertThat(stores.transactions()).isEqualTo(1);
    assertThat(publisher.sent()).isEmpty();

    WalletMovement sent = stores.movementsOf(ANA_ID).get(0);
    assertThat(sent.type()).isEqualTo(MovementType.PAYMENT_SENT);
    assertThat(sent.fundedBy()).isEqualTo(Funding.WALLET);
    assertThat(sent.balanceAfter()).isEqualByComparingTo("37.50");
    assertThat(sent.counterpartyPhoneNumber()).isEqualTo(BETO);
    assertThat(sent.operationId()).isEqualTo(OPERATION);
    assertThat(sent.description()).isEqualTo("Lunch");
    WalletMovement received = stores.movementsOf(BETO_ID).get(0);
    assertThat(received.type()).isEqualTo(MovementType.PAYMENT_RECEIVED);
    assertThat(received.balanceAfter()).isEqualByComparingTo("17.50");
    assertThat(received.counterpartyPhoneNumber()).isEqualTo(ANA);
    assertThat(received.occurredAt()).isEqualTo(START.plusSeconds(10));
  }

  @Test
  void aLinkedSenderPaysFromTheBankAccountAndNothingMovesHereYet() {
    stores.put(linked(ana("50.00"), ANA_LINK, ANA_CARD));
    stores.put(beto("5.00"));

    Payment payment = handler.execute(request("12.50", null)).block().payment();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.sourceFunding()).isEqualTo(Funding.CARD);
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("50.00");
    assertThat(stores.wallet(BETO_ID).getBalance()).isEqualByComparingTo("5.00");
    assertThat(stores.movementsOf(ANA_ID)).isEmpty();
    assertThat(stores.movementsOf(BETO_ID)).isEmpty();
    assertThat(publisher.sent())
        .containsExactly(
            new RecordingPublisher.Sent(
                new OutboundCommand.MoveFunds(
                    OPERATION, ANA_CARD, null, money("12.50"), "PEN", "Yanki payment"),
                "corr-1"));
  }

  @Test
  void aLinkedSenderDoesNotNeedBalanceInTheWallet() {
    stores.put(linked(ana("0.00"), ANA_LINK, ANA_CARD));
    stores.put(linked(beto("0.00"), BETO_LINK, BETO_CARD));

    Payment payment = handler.execute(request("12.50", "Rent")).block().payment();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(publisher.last())
        .isEqualTo(
            new OutboundCommand.MoveFunds(
                OPERATION, ANA_CARD, BETO_CARD, money("12.50"), "PEN", "Rent"));
  }

  @Test
  void whenOnlyTheReceiverIsLinkedTheSenderIsDebitedAtOnce() {
    stores.put(ana("50.00"));
    stores.put(linked(beto("5.00"), BETO_LINK, BETO_CARD));

    Payment payment = handler.execute(request("12.50", "Rent")).block().payment();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("37.50");
    assertThat(stores.wallet(BETO_ID).getBalance()).isEqualByComparingTo("5.00");
    assertThat(stores.movementsOf(ANA_ID))
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.type()).isEqualTo(MovementType.PAYMENT_SENT);
              assertThat(movement.fundedBy()).isEqualTo(Funding.WALLET);
            });
    assertThat(stores.movementsOf(BETO_ID)).isEmpty();
    assertThat(publisher.last())
        .isEqualTo(
            new OutboundCommand.MoveFunds(
                OPERATION, null, BETO_CARD, money("12.50"), "PEN", "Rent"));
  }

  @Test
  void thePaymentIsKeptWhenKafkaIsDown() {
    stores.put(linked(ana("50.00"), ANA_LINK, ANA_CARD));
    stores.put(beto("5.00"));
    publisher.goDown();

    RequestedPayment requested = handler.execute(request("12.50", null)).block();

    assertThat(requested.payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(stores.payment(OPERATION)).isNotNull();
  }

  @Test
  void aRepeatReturnsThePaymentAsItIsAndMovesNothing() {
    stores.put(ana("50.00"));
    stores.put(beto("5.00"));
    handler.execute(request("12.50", "Lunch")).block();

    RequestedPayment repeat = handler.execute(request("12.5", "Lunch")).block();

    assertThat(repeat.replayed()).isTrue();
    assertThat(repeat.payment()).isSameAs(stores.payment(OPERATION));
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("37.50");
    assertThat(stores.movementsOf(ANA_ID)).hasSize(1);
  }

  @Test
  void theSameOperationWithOtherDataIsAConflict() {
    stores.put(ana("50.00"));
    stores.put(beto("5.00"));
    handler.execute(request("12.50", "Lunch")).block();

    assertThatThrownBy(() -> handler.execute(request("13.00", "Lunch")).block())
        .isInstanceOf(OperationIdConflictException.class);
    assertThatThrownBy(() -> handler.execute(request("12.50", "Dinner")).block())
        .isInstanceOf(OperationIdConflictException.class);
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("37.50");
  }

  @Test
  void twoRequestsWithTheSameOperationAtTheSameTimeAreToldApartByTheStore() {
    stores.put(ana("50.00"));
    stores.put(beto("5.00"));
    Payment rival =
        Payment.request(
            OPERATION, ana("50.00"), beto("5.00"), money("12.50"), "Lunch", "corr-0", START);
    stores.anotherRequestStoresFirst(rival);

    RequestedPayment repeat = handler.execute(request("12.50", "Lunch")).block();

    assertThat(repeat.replayed()).isTrue();
    assertThat(repeat.payment()).isSameAs(rival);
  }

  @Test
  void aPaymentStartsOverWhenAWalletChangedUnderIt() {
    stores.put(ana("50.00"));
    stores.put(beto("5.00"));
    stores.failNextWalletUpdates(2);

    Payment payment = handler.execute(request("12.50", null)).block().payment();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("37.50");
    assertThat(stores.wallet(BETO_ID).getBalance()).isEqualByComparingTo("17.50");
  }

  @Test
  void theBalanceMustCoverThePaymentUnlessTheSenderIsLinked() {
    stores.put(ana("12.49"));
    stores.put(beto("5.00"));

    assertViolation(request("12.50", null), RuleViolation.INSUFFICIENT_FUNDS);
    assertThat(stores.payment(OPERATION)).isNull();
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("12.49");
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void bothMobileNumbersNeedAWalletAndMustBeDifferent() {
    stores.put(ana("50.00"));

    assertThatThrownBy(() -> handler.execute(request("12.50", null)).block())
        .isInstanceOf(WalletNotFoundException.class)
        .hasMessageContaining("receiver");
    assertThatThrownBy(
            () ->
                handler
                    .execute(
                        new RequestPaymentCommand(
                            OPERATION, "999999999", ANA, money("1.00"), null, "corr-1"))
                    .block())
        .isInstanceOf(WalletNotFoundException.class)
        .hasMessageContaining("sender");
    assertViolation(
        new RequestPaymentCommand(OPERATION, ANA, ANA, money("1.00"), null, "corr-1"),
        RuleViolation.SAME_WALLET);
    assertViolation(request("0", null), RuleViolation.INVALID_AMOUNT);
    assertThat(stores.payment(OPERATION)).isNull();
  }

  // ----- limits

  @Test
  void aPaymentAboveTheMaximumIsRefusedBeforeAnythingIsReadOrStored() {
    assertViolation(request("500.01", null), RuleViolation.PAYMENT_LIMIT_EXCEEDED);
    stores.put(ana("600.00"));
    stores.put(beto("0.00"));

    assertViolation(request("500.01", null), RuleViolation.PAYMENT_LIMIT_EXCEEDED);
    assertThat(stores.payment(OPERATION)).isNull();
    assertThat(stores.transactions()).isZero();
    assertThat(handler.execute(request("500.00", null)).block().payment().getAmount())
        .hasToString("500.00");
  }

  @Test
  void whatTheWalletSentTodayPlusTheNewPaymentCannotPassTheDailyMaximum() {
    Wallet sender = ana("600.00");
    stores.put(sender);
    stores.put(beto("0.00"));
    sentAt(START, sender, "500.00", 11);
    sentAt(START, sender, "500.00", 12);
    sentAt(START, sender, "500.00", 13);
    sentAt(START, sender, "490.00", 14);

    assertViolation(request("10.01", null), RuleViolation.DAILY_LIMIT_EXCEEDED);
    assertThat(stores.payment(OPERATION)).isNull();
    assertThat(stores.wallet(ANA_ID)).isSameAs(sender);
    assertThat(publisher.sent()).isEmpty();

    assertThat(handler.execute(request("10.00", null)).block().replayed()).isFalse();
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("590.00");
  }

  @Test
  void rejectedPaymentsOtherDaysAndOtherWalletsDoNotCountForTheDay() {
    Wallet sender = linked(ana("0.00"), ANA_LINK, ANA_CARD);
    Wallet receiver = beto("0.00");
    stores.put(sender);
    stores.put(receiver);
    // 04:00 UTC is 23:00 of the day before in Lima.
    sentAt(START.minusSeconds(11 * 3600), sender, "500.00", 11);
    sentAt(START.minusSeconds(11 * 3600), sender, "500.00", 12);
    Payment refused = sentAt(START, sender, "500.00", 13);
    stores.put(
        refused.fundsMoveRejected(new Rejection("INSUFFICIENT_FUNDS", null), START).orElseThrow());
    stores.put(
        Payment.request(
            new UUID(7L, 15), receiver, sender, money("400.00"), null, "corr-0", START));
    sentAt(START, sender, "500.00", 16);
    sentAt(START, sender, "500.00", 17);
    sentAt(START, sender, "500.00", 18);

    assertThat(handler.execute(request("500.00", null)).block().payment().isPending()).isTrue();
    assertViolation(
        new RequestPaymentCommand(new UUID(7L, 99), ANA, BETO, money("0.01"), null, "corr-1"),
        RuleViolation.DAILY_LIMIT_EXCEEDED);
  }

  @Test
  void aRepeatIsAnsweredWithoutCheckingTheLimitsAgain() {
    Wallet sender = ana("600.00");
    stores.put(sender);
    stores.put(beto("0.00"));
    handler.execute(request("500.00", "Rent")).block();
    sentAt(START, sender, "500.00", 11);
    sentAt(START, sender, "500.00", 12);
    sentAt(START, sender, "500.00", 13);

    RequestedPayment repeat = handler.execute(request("500.00", "Rent")).block();

    assertThat(repeat.replayed()).isTrue();
    assertThat(repeat.payment()).isSameAs(stores.payment(OPERATION));
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("100.00");
  }

  @Test
  void aPaymentTheCardPaysAlsoWritesItsSenderSoTwoOfThemNeverDecideAtOnce() {
    Wallet sender = linked(ana("0.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(beto("0.00"));
    stores.failNextWalletUpdates(1);

    Payment payment = handler.execute(request("12.50", null)).block().payment();

    assertThat(payment.isPending()).isTrue();
    assertThat(stores.wallet(ANA_ID).getVersion()).isEqualTo(sender.getVersion() + 1);
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("0.00");
    // The first attempt lost the write and left nothing; the second one stored the payment.
    assertThat(stores.transactions()).isEqualTo(2);
    assertThat(publisher.sent()).hasSize(1);
  }

  /** A payment the wallet already sent, stored as it would be. */
  private Payment sentAt(Instant when, Wallet sender, String amount, int number) {
    Payment sent =
        Payment.request(
            new UUID(7L, number), sender, beto("0.00"), money(amount), null, "corr-0", when);
    stores.put(sent);
    return sent;
  }

  private static RequestPaymentCommand request(String amount, String description) {
    return new RequestPaymentCommand(OPERATION, ANA, BETO, money(amount), description, "corr-1");
  }

  private void assertViolation(RequestPaymentCommand command, RuleViolation violation) {
    assertThatThrownBy(() -> handler.execute(command).block())
        .isInstanceOfSatisfying(
            YankiRuleViolationException.class,
            exception -> assertThat(exception.violation()).isEqualTo(violation));
  }
}
