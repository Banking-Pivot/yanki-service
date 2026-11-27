package com.banking.yankiservice.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.Funding;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementType;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentStatus;
import com.banking.yankiservice.support.IntegrationTest;
import com.banking.yankiservice.support.TestParticipants;
import com.banking.yankiservice.support.TestParticipants.ReceivedCommand;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Payments where a side uses the bank account behind its debit card: the money moves by Kafka. */
class CardPaymentApiTest extends IntegrationTest {

  // ----- the sender pays from the bank account

  @Test
  void aLinkedSenderPaysFromTheAccountAndTheReceiverGetsItInTheWallet() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "30.00");
    UUID card = linkedCard(ana);
    UUID operation = UUID.randomUUID();

    PaymentResponse requested =
        webTestClient
            .post()
            .uri("/api/v1/yanki/payments")
            .header("X-Correlation-ID", "pay-2026-11-20")
            .bodyValue(payment(operation))
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .valueEquals("Location", "/api/v1/yanki/payments/" + operation)
            .expectBody(PaymentResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(requested.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(requested.getSourceFunding()).isEqualTo(Funding.CARD);
    assertThat(requested.getSourceCardId()).isEqualTo(card);
    assertThat(requested.getDestinationFunding()).isEqualTo(Funding.WALLET);
    assertThat(requested.getDestinationCardId()).isNull();
    ReceivedCommand command = participants.awaitCommand("MOVE_FUNDS", operation);
    assertThat(command.topic()).isEqualTo(TestParticipants.TRANSACTION_COMMANDS);
    assertThat(command.key()).isEqualTo(operation.toString());
    assertThat(command.correlationId()).isEqualTo("pay-2026-11-20");
    assertThat(command.text())
        .isEqualTo(
            "{\"type\":\"MOVE_FUNDS\",\"transactionId\":\""
                + operation
                + "\",\"sourceCardId\":\""
                + card
                + "\",\"amount\":12.50,\"currency\":\"PEN\",\"description\":\"Lunch\"}");
    // Nothing moves here until Transaction Service answers.
    assertThat(balance(ana)).isEqualByComparingTo("30.00");
    assertThat(balance(beto)).isEqualByComparingTo("0.00");
    assertThat(movements(beto)).isEmpty();

    participants.fundsMoved(operation, "1.50");

    PaymentResponse completed = awaitStatus(operation, PaymentStatus.COMPLETED);
    assertThat(completed.getFee()).isEqualByComparingTo("1.50");
    assertThat(completed.getRejection()).isNull();
    assertThat(completed.getUpdatedAt()).isAfter(completed.getCreatedAt());
    assertThat(balance(ana)).isEqualByComparingTo("30.00");
    assertThat(balance(beto)).isEqualByComparingTo("12.50");
    MovementResponse sent = movements(ana).get(0);
    assertThat(sent.getType()).isEqualTo(MovementType.PAYMENT_SENT);
    assertThat(sent.getFundedBy()).isEqualTo(Funding.CARD);
    assertThat(sent.getAmount()).isEqualByComparingTo("12.50");
    assertThat(sent.getBalanceAfter()).isEqualByComparingTo("30.00");
    assertThat(sent.getOperationId()).isEqualTo(operation);
    MovementResponse received = movements(beto).get(0);
    assertThat(received.getType()).isEqualTo(MovementType.PAYMENT_RECEIVED);
    assertThat(received.getFundedBy()).isEqualTo(Funding.WALLET);
    assertThat(received.getBalanceAfter()).isEqualByComparingTo("12.50");
    assertThat(received.getCounterpartyPhoneNumber()).isEqualTo(ANA);
  }

  @Test
  void aLinkedSenderMayPayMoreThanTheWalletHas() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    linkedCard(ana);

    UUID operation = requestedPayment();
    participants.awaitCommand("MOVE_FUNDS", operation);
    participants.fundsMoved(operation, null);

    assertThat(awaitStatus(operation, PaymentStatus.COMPLETED).getFee()).isNull();
    assertThat(balance(ana)).isEqualByComparingTo("0.00");
    assertThat(balance(beto)).isEqualByComparingTo("12.50");
  }

  @Test
  void aPaymentTheAccountCannotCoverIsRejectedAndNothingMovesHere() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "30.00");
    linkedCard(ana);
    UUID operation = requestedPayment();
    participants.awaitCommand("MOVE_FUNDS", operation);

    participants.fundsMoveRejected(
        operation, "INSUFFICIENT_FUNDS", "The account balance does not cover the amount.");

    PaymentResponse rejected = awaitStatus(operation, PaymentStatus.REJECTED);
    assertThat(rejected.getRejection().getCode()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(rejected.getRejection().getMessage())
        .isEqualTo("The account balance does not cover the amount.");
    assertThat(rejected.getFee()).isNull();
    assertThat(balance(ana)).isEqualByComparingTo("30.00");
    assertThat(balance(beto)).isEqualByComparingTo("0.00");
    assertThat(movements(ana)).hasSize(1);
    assertThat(movements(beto)).isEmpty();
  }

  // ----- only the receiver uses the bank account

  @Test
  void aWalletSenderIsDebitedAtOnceAndTheLinkedReceiverGetsItInTheAccount() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "50.00");
    funded(beto, "3.00");
    UUID card = linkedCard(beto);
    UUID operation = UUID.randomUUID();

    PaymentResponse requested =
        requestPayment(payment(operation))
            .expectStatus()
            .isAccepted()
            .expectBody(PaymentResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(requested.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(requested.getSourceFunding()).isEqualTo(Funding.WALLET);
    assertThat(requested.getSourceCardId()).isNull();
    assertThat(requested.getDestinationFunding()).isEqualTo(Funding.CARD);
    assertThat(requested.getDestinationCardId()).isEqualTo(card);
    assertThat(balance(ana)).isEqualByComparingTo("37.50");
    MovementResponse sent = movements(ana).get(0);
    assertThat(sent.getType()).isEqualTo(MovementType.PAYMENT_SENT);
    assertThat(sent.getFundedBy()).isEqualTo(Funding.WALLET);
    assertThat(sent.getBalanceAfter()).isEqualByComparingTo("37.50");
    assertThat(participants.awaitCommand("MOVE_FUNDS", operation).text())
        .isEqualTo(
            "{\"type\":\"MOVE_FUNDS\",\"transactionId\":\""
                + operation
                + "\",\"destinationCardId\":\""
                + card
                + "\",\"amount\":12.50,\"currency\":\"PEN\",\"description\":\"Lunch\"}");

    participants.fundsMoved(operation, "0.00");

    assertThat(awaitStatus(operation, PaymentStatus.COMPLETED).getFee())
        .isEqualByComparingTo("0.00");
    assertThat(balance(ana)).isEqualByComparingTo("37.50");
    assertThat(balance(beto)).isEqualByComparingTo("3.00");
    assertThat(movements(ana)).hasSize(2);
    MovementResponse received = movements(beto).get(0);
    assertThat(received.getType()).isEqualTo(MovementType.PAYMENT_RECEIVED);
    assertThat(received.getFundedBy()).isEqualTo(Funding.CARD);
    assertThat(received.getAmount()).isEqualByComparingTo("12.50");
    assertThat(received.getBalanceAfter()).isEqualByComparingTo("3.00");
  }

  @Test
  void aWalletSenderGetsTheMoneyBackWhenTheReceiversAccountRefusesIt() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "50.00");
    linkedCard(beto);
    UUID operation = requestedPayment();
    participants.awaitCommand("MOVE_FUNDS", operation);
    assertThat(balance(ana)).isEqualByComparingTo("37.50");

    participants.fundsMoveRejected(operation, "CARD_NOT_ACTIVE", "The card is blocked.");

    PaymentResponse rejected = awaitStatus(operation, PaymentStatus.REJECTED);
    assertThat(rejected.getRejection().getCode()).isEqualTo("CARD_NOT_ACTIVE");
    assertThat(balance(ana)).isEqualByComparingTo("50.00");
    assertThat(balance(beto)).isEqualByComparingTo("0.00");
    List<MovementResponse> recorded = movements(ana);
    assertThat(recorded).hasSize(3);
    MovementResponse refund = recorded.get(0);
    assertThat(refund.getType()).isEqualTo(MovementType.REFUND);
    assertThat(refund.getFundedBy()).isEqualTo(Funding.WALLET);
    assertThat(refund.getAmount()).isEqualByComparingTo("12.50");
    assertThat(refund.getBalanceAfter()).isEqualByComparingTo("50.00");
    assertThat(refund.getOperationId()).isEqualTo(operation);
    assertThat(recorded.get(1).getType()).isEqualTo(MovementType.PAYMENT_SENT);
    assertThat(movements(beto)).isEmpty();
  }

  @Test
  void aWalletSenderWithoutEnoughMoneyIsRefusedBeforeAnythingIsSent() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "5.00");
    linkedCard(beto);
    UUID operation = UUID.randomUUID();

    requestPayment(payment(operation))
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INSUFFICIENT_FUNDS");

    assertThat(balance(ana)).isEqualByComparingTo("5.00");
    assertThat(count("payments")).isZero();
    assertThat(participants.countCommands("MOVE_FUNDS", operation, Duration.ofSeconds(1))).isZero();
  }

  // ----- both sides use their bank accounts

  @Test
  void twoLinkedWalletsMoveTheMoneyFromAccountToAccount() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "7.00");
    UUID source = linkedCard(ana);
    UUID destination = linkedCard(beto);
    Map<String, Object> request = payment(UUID.randomUUID());
    request.remove("description");
    UUID operation = UUID.fromString(request.get("operationId").toString());

    requestPayment(request).expectStatus().isAccepted();

    assertThat(participants.awaitCommand("MOVE_FUNDS", operation).text())
        .isEqualTo(
            "{\"type\":\"MOVE_FUNDS\",\"transactionId\":\""
                + operation
                + "\",\"sourceCardId\":\""
                + source
                + "\",\"destinationCardId\":\""
                + destination
                + "\",\"amount\":12.50,\"currency\":\"PEN\",\"description\":\"Yanki payment\"}");
    participants.fundsMoved(operation, "0.00");
    awaitStatus(operation, PaymentStatus.COMPLETED);
    assertThat(balance(ana)).isEqualByComparingTo("7.00");
    assertThat(balance(beto)).isEqualByComparingTo("0.00");
    assertThat(movements(ana).get(0).getFundedBy()).isEqualTo(Funding.CARD);
    assertThat(movements(ana).get(0).getDescription()).isNull();
    assertThat(movements(beto).get(0).getFundedBy()).isEqualTo(Funding.CARD);
  }

  // ----- what was decided when the payment was created stays

  @Test
  void aPaymentKeepsTheSidesItWasCreatedWithEvenIfTheLinksChange() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "30.00");
    linkedCard(ana);
    UUID operation = requestedPayment();
    participants.awaitCommand("MOVE_FUNDS", operation);

    // Before the answer: the sender leaves the card and the receiver takes one.
    webTestClient
        .delete()
        .uri("/api/v1/yanki/wallets/{id}/card", ana)
        .exchange()
        .expectStatus()
        .isNoContent();
    linkedCard(beto);
    participants.fundsMoved(operation, "0.00");

    PaymentResponse completed = awaitStatus(operation, PaymentStatus.COMPLETED);
    assertThat(completed.getSourceFunding()).isEqualTo(Funding.CARD);
    assertThat(completed.getDestinationFunding()).isEqualTo(Funding.WALLET);
    assertThat(balance(ana)).isEqualByComparingTo("30.00");
    assertThat(balance(beto)).isEqualByComparingTo("12.50");
    assertThat(movements(ana).get(0).getFundedBy()).isEqualTo(Funding.CARD);
    assertThat(movements(beto).get(0).getFundedBy()).isEqualTo(Funding.WALLET);
  }

  @Test
  void aWalletWhoseCardIsStillBeingCheckedPaysFromItsBalance() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "30.00");
    requestedLink(ana, UUID.randomUUID());
    UUID operation = UUID.randomUUID();

    requestPayment(payment(operation))
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("COMPLETED")
        .jsonPath("$.sourceFunding")
        .isEqualTo("WALLET");

    assertThat(balance(ana)).isEqualByComparingTo("17.50");
    assertThat(balance(beto)).isEqualByComparingTo("12.50");
  }

  // ----- answers and repeats

  @Test
  void answersDeliveredAgainOrThatCannotBeReadChangeNothing() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    linkedCard(ana);
    UUID operation = requestedPayment();
    participants.awaitCommand("MOVE_FUNDS", operation);

    participants.send(operation.toString(), "this is not json", "noise-1");
    participants.send(operation.toString(), "{\"type\":\"FUNDS_MOVED\"}", null);
    participants.fundsMoved(UUID.randomUUID(), "0.00");
    participants.fundsMoved(operation, "0.30");
    participants.fundsMoved(operation, "0.30");
    participants.fundsMoveRejected(operation, "INSUFFICIENT_FUNDS", "Too late.");
    // A second payment that completes shows everything before it was read.
    UUID next = requestedPayment();
    participants.awaitCommand("MOVE_FUNDS", next);
    participants.fundsMoved(next, "0.00");
    awaitStatus(next, PaymentStatus.COMPLETED);

    PaymentResponse first = paymentOf(operation);
    assertThat(first.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(first.getFee()).isEqualByComparingTo("0.30");
    assertThat(first.getRejection()).isNull();
    assertThat(balance(beto)).isEqualByComparingTo("25.00");
    assertThat(movements(beto)).hasSize(2);
    assertThat(movements(ana)).hasSize(2);
  }

  @Test
  void aRepeatedRequestOfAPaymentInProgressSendsNothingNew() {
    UUID ana = walletOfAna();
    walletOfBeto();
    linkedCard(ana);
    UUID operation = requestedPayment();
    participants.awaitCommand("MOVE_FUNDS", operation);

    requestPayment(payment(operation))
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Idempotent-Replayed", "true")
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("PENDING");

    assertThat(participants.countCommands("MOVE_FUNDS", operation, Duration.ofSeconds(1)))
        .isEqualTo(1);
    assertThat(count("payments")).isEqualTo(1);
  }

  // ----- the daily limit when the card pays

  @Test
  void whatTheCardPaysCountsForTheDayUntilItIsRejected() {
    UUID ana = walletOfAna();
    walletOfBeto();
    linkedCard(ana);
    UUID first =
        fiveHundred()
            .expectStatus()
            .isAccepted()
            .returnResult(PaymentResponse.class)
            .getResponseBody()
            .blockFirst()
            .getId();
    fiveHundred().expectStatus().isAccepted();
    fiveHundred().expectStatus().isAccepted();
    fiveHundred().expectStatus().isAccepted();

    fiveHundred()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("DAILY_LIMIT_EXCEEDED");

    participants.awaitCommand("MOVE_FUNDS", first);
    participants.fundsMoveRejected(first, "INSUFFICIENT_FUNDS", "Not enough balance.");
    awaitStatus(first, PaymentStatus.REJECTED);
    fiveHundred().expectStatus().isAccepted();
    assertThat(count("payments")).isEqualTo(5);
  }

  @Test
  void paymentsOfTheCardAtTheSameTimeNeverPassTheDailyMaximum() {
    UUID ana = walletOfAna();
    walletOfBeto();
    linkedCard(ana);

    List<Integer> statuses =
        Flux.range(0, 8)
            .flatMap(
                index ->
                    Mono.fromCallable(
                            () -> fiveHundred().returnResult(Void.class).getStatus().value())
                        .subscribeOn(Schedulers.boundedElastic()),
                8)
            .collectList()
            .block();

    // Every request was decided against what the others had already stored: four fit in the day,
    // the rest is refused by the limit, or told to try again when it lost too many writes.
    assertThat(statuses).hasSize(8).isSubsetOf(202, 422, 409);
    assertThat(statuses).filteredOn(status -> status == 202).hasSizeBetween(1, 4);
    assertThat(count("payments"))
        .isEqualTo(statuses.stream().filter(status -> status == 202).count());
    assertThat(sentToday()).isLessThanOrEqualTo(new BigDecimal("2000.00"));
    if (!statuses.contains(409)) {
      assertThat(sentToday()).isEqualByComparingTo("2000.00");
    }
  }

  private WebTestClient.ResponseSpec fiveHundred() {
    Map<String, Object> request = payment(UUID.randomUUID());
    request.put("amount", new BigDecimal("500.00"));
    return requestPayment(request);
  }

  private BigDecimal sentToday() {
    return databaseClient
        .sql("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status <> 'REJECTED'")
        .map(row -> row.get(0, BigDecimal.class))
        .one()
        .block();
  }
}
