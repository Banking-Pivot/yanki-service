package com.banking.yankiservice.domain.model;

import static com.banking.yankiservice.support.Fixtures.ANA;
import static com.banking.yankiservice.support.Fixtures.ANA_CARD;
import static com.banking.yankiservice.support.Fixtures.ANA_LINK;
import static com.banking.yankiservice.support.Fixtures.BETO;
import static com.banking.yankiservice.support.Fixtures.BETO_CARD;
import static com.banking.yankiservice.support.Fixtures.BETO_LINK;
import static com.banking.yankiservice.support.Fixtures.START;
import static com.banking.yankiservice.support.Fixtures.ana;
import static com.banking.yankiservice.support.Fixtures.beto;
import static com.banking.yankiservice.support.Fixtures.linked;
import static com.banking.yankiservice.support.Fixtures.linking;
import static com.banking.yankiservice.support.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import com.banking.yankiservice.domain.model.enums.Funding;
import com.banking.yankiservice.domain.model.enums.PaymentStatus;
import com.banking.yankiservice.domain.model.vo.OutboundCommand;
import com.banking.yankiservice.domain.model.vo.Rejection;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

  private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final Instant LATER = START.plusSeconds(5);

  @Test
  void aPaymentBetweenTwoWalletsWithoutACardIsBornComplete() {
    Payment payment = request(ana("50.00"), beto("0.00"), " Lunch ");

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.isLocal()).isTrue();
    assertThat(payment.isPending()).isFalse();
    assertThat(payment.sourceFunding()).isEqualTo(Funding.WALLET);
    assertThat(payment.destinationFunding()).isEqualTo(Funding.WALLET);
    assertThat(payment.nextCommand()).isEmpty();
    assertThat(payment.getFromPhoneNumber()).isEqualTo(ANA);
    assertThat(payment.getToPhoneNumber()).isEqualTo(BETO);
    assertThat(payment.getDescription()).isEqualTo("Lunch");
    assertThat(payment.fundsMoved(null, LATER)).isEmpty();
    assertThat(payment.fundsMoveRejected(new Rejection("X", "x"), LATER)).isEmpty();
  }

  @Test
  void aLinkedSenderMakesThePaymentWaitForTheMainAccountOfTheCard() {
    Payment payment = request(linked(ana("50.00"), ANA_LINK, ANA_CARD), beto("0.00"), null);

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.sourceFunding()).isEqualTo(Funding.CARD);
    assertThat(payment.destinationFunding()).isEqualTo(Funding.WALLET);
    assertThat(payment.nextCommand())
        .contains(
            new OutboundCommand.MoveFunds(
                ID, ANA_CARD, null, money("12.50"), "PEN", "Yanki payment"));
  }

  @Test
  void aLinkedReceiverGetsThePaymentInTheMainAccountOfTheCard() {
    Payment payment = request(ana("50.00"), linked(beto("0.00"), BETO_LINK, BETO_CARD), "Rent");

    assertThat(payment.sourceFunding()).isEqualTo(Funding.WALLET);
    assertThat(payment.destinationFunding()).isEqualTo(Funding.CARD);
    assertThat(payment.isLocal()).isFalse();
    assertThat(payment.nextCommand())
        .contains(
            new OutboundCommand.MoveFunds(ID, null, BETO_CARD, money("12.50"), "PEN", "Rent"));
  }

  @Test
  void bothCardsTravelWhenBothSidesAreLinked() {
    Payment payment =
        request(
            linked(ana("0.00"), ANA_LINK, ANA_CARD),
            linked(beto("0.00"), BETO_LINK, BETO_CARD),
            "Rent");

    assertThat(payment.nextCommand().orElseThrow().sourceCardId()).isEqualTo(ANA_CARD);
    assertThat(payment.nextCommand().orElseThrow().destinationCardId()).isEqualTo(BETO_CARD);
  }

  @Test
  void aCardThatIsOnlyRequestedDoesNotCountYet() {
    Payment payment = request(linking(ana("50.00"), ANA_LINK, ANA_CARD), beto("0.00"), null);

    assertThat(payment.isLocal()).isTrue();
    assertThat(payment.getSourceCardId()).isNull();
  }

  @Test
  void aWalletCannotPayItself() {
    assertThatThrownBy(() -> request(ana("50.00"), ana("50.00"), null))
        .isInstanceOfSatisfying(
            YankiRuleViolationException.class,
            exception -> assertThat(exception.violation()).isEqualTo(RuleViolation.SAME_WALLET));
  }

  @Test
  void theAnswerCompletesThePaymentOnce() {
    Payment pending = request(linked(ana("0.00"), ANA_LINK, ANA_CARD), beto("0.00"), null);

    Payment completed = pending.fundsMoved(money("1.50"), LATER).orElseThrow();

    assertThat(completed.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(completed.getFee()).isEqualByComparingTo("1.50");
    assertThat(completed.getUpdatedAt()).isEqualTo(LATER);
    assertThat(completed.getVersion()).isEqualTo(1);
    assertThat(completed.nextCommand()).isEmpty();
    assertThat(completed.fundsMoved(money("1.50"), LATER)).isEmpty();
    assertThat(completed.fundsMoveRejected(new Rejection("X", "x"), LATER)).isEmpty();
  }

  @Test
  void aRejectionEndsThePaymentWithItsReason() {
    Payment pending = request(ana("50.00"), linked(beto("0.00"), BETO_LINK, BETO_CARD), null);

    Payment rejected =
        pending
            .fundsMoveRejected(new Rejection("CARD_NOT_ACTIVE", "The card is blocked."), LATER)
            .orElseThrow();

    assertThat(rejected.getStatus()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(rejected.getRejection().code()).isEqualTo("CARD_NOT_ACTIVE");
    assertThat(rejected.getRejection().message()).isEqualTo("The card is blocked.");
    assertThat(rejected.fundsMoved(null, LATER)).isEmpty();
  }

  @Test
  void sendingTheRequestAgainIsCounted() {
    Payment pending = request(linked(ana("0.00"), ANA_LINK, ANA_CARD), beto("0.00"), null);

    Payment resent = pending.commandResent(LATER);

    assertThat(resent.getCommandAttempts()).isEqualTo(1);
    assertThat(resent.getCommandSentAt()).isEqualTo(LATER);
    assertThat(resent.getVersion()).isEqualTo(1);
    assertThat(resent.getUpdatedAt()).isEqualTo(START);
    assertThat(resent.nextCommand()).isEqualTo(pending.nextCommand());
  }

  @Test
  void theSameRequestIsRecognizedWhateverTheScaleOfTheAmount() {
    Payment payment = request(ana("50.00"), beto("0.00"), "Lunch");

    assertThat(payment.sameRequestAs(ANA, BETO, money("12.5"), " Lunch")).isTrue();
    assertThat(payment.sameRequestAs(ANA, BETO, money("12.51"), "Lunch")).isFalse();
    assertThat(payment.sameRequestAs(BETO, ANA, money("12.50"), "Lunch")).isFalse();
    assertThat(payment.sameRequestAs(ANA, "999999999", money("12.50"), "Lunch")).isFalse();
    assertThat(payment.sameRequestAs(ANA, BETO, money("12.50"), null)).isFalse();
  }

  private static Payment request(Wallet from, Wallet to, String description) {
    return Payment.request(ID, from, to, money("12.50"), description, "corr-1", START);
  }
}
