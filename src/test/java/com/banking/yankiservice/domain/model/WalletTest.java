package com.banking.yankiservice.domain.model;

import static com.banking.yankiservice.support.Fixtures.ANA_CARD;
import static com.banking.yankiservice.support.Fixtures.ANA_ID;
import static com.banking.yankiservice.support.Fixtures.ANA_LINK;
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
import com.banking.yankiservice.domain.model.enums.CardLinkStatus;
import com.banking.yankiservice.domain.model.enums.DocumentType;
import com.banking.yankiservice.domain.model.vo.OutboundCommand;
import com.banking.yankiservice.domain.model.vo.Rejection;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WalletTest {

  private static final Instant LATER = START.plusSeconds(5);

  @Test
  void aNewWalletStartsEmptyWithoutACardAndWithItsDataNormalized() {
    Wallet wallet =
        Wallet.register(
            ANA_ID,
            DocumentType.PASSPORT,
            " ab123456 ",
            " 987654321 ",
            "356938035643809",
            " Ana@Example.COM ",
            START);

    assertThat(wallet.getBalance()).isEqualByComparingTo("0.00");
    assertThat(wallet.getBalance().scale()).isEqualTo(2);
    assertThat(wallet.getDocumentNumber()).isEqualTo("AB123456");
    assertThat(wallet.getPhoneNumber()).isEqualTo("987654321");
    assertThat(wallet.getEmail()).isEqualTo("ana@example.com");
    assertThat(wallet.getCardLink()).isNull();
    assertThat(wallet.isLinkedToCard()).isFalse();
    assertThat(wallet.linkedCardId()).isEmpty();
    assertThat(wallet.nextCommand()).isEmpty();
    assertThat(wallet.getVersion()).isZero();
    assertThat(wallet.getCreatedAt()).isEqualTo(START);
  }

  @ParameterizedTest
  @CsvSource({
    "DNI, 1234567",
    "DNI, 123456789",
    "DNI, 1234567A",
    "CEX, 12345678",
    "CEX, 1234567890123",
    "PASSPORT, 12345",
    "PASSPORT, AB-12345",
  })
  void aDocumentNumberMustHaveTheShapeOfItsType(DocumentType type, String number) {
    assertViolation(
        () ->
            Wallet.register(
                ANA_ID, type, number, "987654321", "356938035643809", "ana@example.com", START),
        RuleViolation.INVALID_DOCUMENT_NUMBER);
  }

  @Test
  void aMissingDocumentNumberIsInvalid() {
    assertViolation(() -> DocumentType.DNI.normalize(null), RuleViolation.INVALID_DOCUMENT_NUMBER);
  }

  @ParameterizedTest
  @ValueSource(strings = {"887654321", "98765432", "9876543210", "98765432a", ""})
  void aMobileNumberHasNineDigitsAndStartsWithNine(String phoneNumber) {
    assertViolation(
        () ->
            Wallet.register(
                ANA_ID,
                DocumentType.DNI,
                "45678912",
                phoneNumber,
                "356938035643809",
                "ana@example.com",
                START),
        RuleViolation.INVALID_PHONE_NUMBER);
  }

  @ParameterizedTest
  @ValueSource(strings = {"35693803564380", "3569380356438099", "35693803564380X"})
  void anImeiHasFifteenDigits(String imei) {
    assertViolation(
        () ->
            Wallet.register(
                ANA_ID, DocumentType.DNI, "45678912", "987654321", imei, "ana@example.com", START),
        RuleViolation.INVALID_IMEI);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"ana", "ana@", "@example.com", "ana@example", "ana@@example.com", "a na@x.com"})
  void anEmailNeedsANameAndADomain(String email) {
    assertViolation(
        () ->
            Wallet.register(
                ANA_ID, DocumentType.DNI, "45678912", "987654321", "356938035643809", email, START),
        RuleViolation.INVALID_EMAIL);
  }

  @Test
  void missingValuesAreInvalidToo() {
    assertViolation(
        () ->
            Wallet.register(
                ANA_ID, DocumentType.DNI, "45678912", null, "356938035643809", "a@b.pe", START),
        RuleViolation.INVALID_PHONE_NUMBER);
    assertViolation(
        () ->
            Wallet.register(
                ANA_ID, DocumentType.DNI, "45678912", "987654321", "356938035643809", null, START),
        RuleViolation.INVALID_EMAIL);
  }

  @Test
  void aTopUpAddsToTheBalanceAndMovesTheVersion() {
    Wallet loaded = ana("10.00").topUp(money("25.50"), LATER);

    assertThat(loaded.getBalance()).isEqualByComparingTo("35.50");
    assertThat(loaded.getVersion()).isEqualTo(2);
    assertThat(loaded.getUpdatedAt()).isEqualTo(LATER);
  }

  @Test
  void aTopUpCannotTakeTheBalanceOverTheMaximum() {
    Wallet full = ana("0.00").topUp(Amounts.MAX_AMOUNT, START);

    assertViolation(() -> full.topUp(money("0.01"), LATER), RuleViolation.BALANCE_LIMIT_EXCEEDED);
  }

  @Test
  void aDebitNeedsEnoughBalanceAndCanEmptyTheWallet() {
    Wallet wallet = ana("20.00");

    assertThat(wallet.debit(money("20.00"), LATER).getBalance()).isEqualByComparingTo("0.00");
    assertViolation(() -> wallet.debit(money("20.01"), LATER), RuleViolation.INSUFFICIENT_FUNDS);
  }

  @Test
  void askingForACardLeavesTheWalletWaitingAndStillPayingFromItsBalance() {
    Wallet waiting = ana("20.00").requestCardLink(ANA_LINK, ANA_CARD, "corr-1", LATER);

    assertThat(waiting.getCardLink().status()).isEqualTo(CardLinkStatus.PENDING);
    assertThat(waiting.getCardLink().correlationId()).isEqualTo("corr-1");
    assertThat(waiting.getCardLink().commandSentAt()).isEqualTo(LATER);
    assertThat(waiting.isLinkedToCard()).isFalse();
    assertThat(waiting.linkedCardId()).isEmpty();
    assertThat(waiting.nextCommand()).contains(new OutboundCommand.ResolveCard(ANA_LINK, ANA_CARD));
    assertThat(waiting.topUp(money("5.00"), LATER).getBalance()).isEqualByComparingTo("25.00");
  }

  @Test
  void theAnswerOfCardServiceLinksTheWalletOnlyForTheRequestItWaitsOn() {
    Wallet waiting = linking(ana("20.00"), ANA_LINK, ANA_CARD);

    assertThat(waiting.cardResolved(BETO_LINK, "DNI", "45678912", LATER)).isEmpty();
    Wallet linked = waiting.cardResolved(ANA_LINK, "DNI", "45678912", LATER).orElseThrow();
    assertThat(linked.isLinkedToCard()).isTrue();
    assertThat(linked.linkedCardId()).contains(ANA_CARD);
    assertThat(linked.nextCommand()).isEmpty();
    assertThat(linked.getBalance()).isEqualByComparingTo("20.00");
    assertThat(linked.cardResolved(ANA_LINK, "DNI", "45678912", LATER)).isEmpty();
    assertThat(linked.cardRejected(ANA_LINK, new Rejection("CARD_NOT_ACTIVE", "late"), LATER))
        .isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"DNI,45678912", "dni,' 45678912 '", "Dni,45678912"})
  void theHolderIsTheOwnerWhateverTheCaseOfTheTypeAndTheSpacesOfTheNumber(
      String type, String number) {
    Wallet waiting = linking(ana("0.00"), ANA_LINK, ANA_CARD);

    assertThat(waiting.cardResolved(ANA_LINK, type, number, LATER).orElseThrow().isLinkedToCard())
        .isTrue();
  }

  @Test
  void lettersOfTheHolderNumberAreComparedInUpperCase() {
    Wallet waiting = linking(beto("0.00"), BETO_LINK, BETO_CARD);

    assertThat(
            waiting
                .cardResolved(BETO_LINK, "cex", "x12345678", LATER)
                .orElseThrow()
                .isLinkedToCard())
        .isTrue();
  }

  @ParameterizedTest
  @CsvSource(
      value = {"DNI,41234567", "CEX,45678912", "DNI,NULL", "NULL,45678912", "NULL,NULL"},
      nullValues = "NULL")
  void aCardHeldBySomebodyElseOrByNobodyKnownIsRejectedAsNotOwned(String type, String number) {
    Wallet waiting = linking(ana("20.00"), ANA_LINK, ANA_CARD);

    Wallet rejected = waiting.cardResolved(ANA_LINK, type, number, LATER).orElseThrow();

    assertThat(rejected.getCardLink().status()).isEqualTo(CardLinkStatus.REJECTED);
    assertThat(rejected.getCardLink().rejection().code()).isEqualTo(Wallet.CARD_NOT_OWNED);
    assertThat(rejected.isLinkedToCard()).isFalse();
    assertThat(rejected.getBalance()).isEqualByComparingTo("20.00");
    assertThat(rejected.getVersion()).isEqualTo(waiting.getVersion() + 1);
  }

  @Test
  void aPaymentTheCardPaysMovesOnlyTheVersion() {
    Wallet linked = linked(ana("20.00"), ANA_LINK, ANA_CARD);

    Wallet after = linked.paymentRequested();

    assertThat(after.getVersion()).isEqualTo(linked.getVersion() + 1);
    assertThat(after.getBalance()).isEqualByComparingTo("20.00");
    assertThat(after.getUpdatedAt()).isEqualTo(linked.getUpdatedAt());
    assertThat(after.getCardLink()).isEqualTo(linked.getCardLink());
  }

  @Test
  void aCardOfAnotherWalletHasItsOwnRule() {
    assertThat(Wallet.cardAlreadyLinked().violation()).isEqualTo(RuleViolation.CARD_ALREADY_LINKED);
  }

  @Test
  void aRejectedCardLeavesTheReasonAndTheWalletCanAskAgain() {
    Wallet rejected =
        linking(ana("20.00"), ANA_LINK, ANA_CARD)
            .cardRejected(ANA_LINK, new Rejection("CARD_NOT_FOUND", null), LATER)
            .orElseThrow();

    assertThat(rejected.getCardLink().status()).isEqualTo(CardLinkStatus.REJECTED);
    assertThat(rejected.getCardLink().rejection().code()).isEqualTo("CARD_NOT_FOUND");
    assertThat(rejected.getCardLink().rejection().message()).isEqualTo("CARD_NOT_FOUND");
    assertThat(rejected.isLinkedToCard()).isFalse();

    Wallet again = rejected.requestCardLink(BETO_LINK, ANA_CARD, "corr-2", LATER);
    assertThat(again.getCardLink().linkId()).isEqualTo(BETO_LINK);
    assertThat(again.getCardLink().status()).isEqualTo(CardLinkStatus.PENDING);
    assertThat(again.getCardLink().rejection()).isNull();
  }

  @Test
  void askingAgainForTheSameCardChangesNothing() {
    Wallet waiting = linking(ana("0.00"), ANA_LINK, ANA_CARD);
    Wallet linked = linked(ana("0.00"), ANA_LINK, ANA_CARD);

    assertThat(waiting.requestCardLink(BETO_LINK, ANA_CARD, "corr-2", LATER)).isSameAs(waiting);
    assertThat(linked.requestCardLink(BETO_LINK, ANA_CARD, "corr-2", LATER)).isSameAs(linked);
  }

  @Test
  void anotherCardReplacesAPendingRequestButNotALinkedCard() {
    Wallet waiting = linking(ana("0.00"), ANA_LINK, ANA_CARD);
    Wallet linked = linked(ana("0.00"), ANA_LINK, ANA_CARD);

    Wallet replaced = waiting.requestCardLink(BETO_LINK, BETO_CARD, "corr-2", LATER);
    assertThat(replaced.getCardLink().cardId()).isEqualTo(BETO_CARD);
    assertThat(replaced.cardResolved(ANA_LINK, "DNI", "45678912", LATER)).isEmpty();
    assertViolation(
        () -> linked.requestCardLink(BETO_LINK, BETO_CARD, "corr-2", LATER),
        RuleViolation.WALLET_ALREADY_LINKED);
  }

  @Test
  void aLinkedWalletCannotBeToppedUpAndKeepsItsBalanceWhenUnlinked() {
    Wallet linked = linked(ana("20.00"), ANA_LINK, ANA_CARD);

    assertViolation(() -> linked.topUp(money("5.00"), LATER), RuleViolation.WALLET_LINKED_TO_CARD);

    Wallet unlinked = linked.unlinkCard(LATER);
    assertThat(unlinked.getCardLink()).isNull();
    assertThat(unlinked.getBalance()).isEqualByComparingTo("20.00");
    assertThat(unlinked.unlinkCard(LATER)).isSameAs(unlinked);
    assertThat(unlinked.topUp(money("5.00"), LATER).getBalance()).isEqualByComparingTo("25.00");
  }

  @Test
  void sendingTheRequestAgainIsCountedWithoutTouchingTheRest() {
    Wallet waiting = linking(ana("20.00"), ANA_LINK, ANA_CARD);

    Wallet resent = waiting.cardCommandResent(LATER);

    assertThat(resent.getCardLink().commandAttempts()).isEqualTo(1);
    assertThat(resent.getCardLink().commandSentAt()).isEqualTo(LATER);
    assertThat(resent.getVersion()).isEqualTo(waiting.getVersion() + 1);
    assertThat(resent.getUpdatedAt()).isEqualTo(waiting.getUpdatedAt());
    assertThat(resent.nextCommand()).isEqualTo(waiting.nextCommand());
  }

  @Test
  void aLongRejectionIsCutToWhatIsStored() {
    Rejection rejection = new Rejection("C".repeat(80), "m".repeat(400));

    assertThat(rejection.code()).hasSize(60);
    assertThat(rejection.message()).hasSize(300);
  }

  private static void assertViolation(ThrowingCallable action, RuleViolation violation) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            YankiRuleViolationException.class,
            exception -> assertThat(exception.violation()).isEqualTo(violation));
  }
}
