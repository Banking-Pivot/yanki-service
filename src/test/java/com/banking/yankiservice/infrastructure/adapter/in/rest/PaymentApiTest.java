package com.banking.yankiservice.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.Funding;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementType;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentStatus;
import com.banking.yankiservice.support.IntegrationTest;
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

/** Payments between two wallets that keep their money here: nothing leaves this service. */
class PaymentApiTest extends IntegrationTest {

  @Test
  void aPaymentBetweenTwoWalletsMovesTheMoneyAtOnce() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "50.00");
    UUID operation = UUID.randomUUID();

    PaymentResponse payment =
        requestPayment(payment(operation))
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .valueEquals("Location", "/api/v1/yanki/payments/" + operation)
            .expectHeader()
            .doesNotExist("Idempotent-Replayed")
            .expectBody(PaymentResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(payment.getId()).isEqualTo(operation);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.getFromWalletId()).isEqualTo(ana);
    assertThat(payment.getToWalletId()).isEqualTo(beto);
    assertThat(payment.getFromPhoneNumber()).isEqualTo(ANA);
    assertThat(payment.getToPhoneNumber()).isEqualTo(BETO);
    assertThat(payment.getAmount()).isEqualByComparingTo("12.50");
    assertThat(payment.getCurrency().getValue()).isEqualTo("PEN");
    assertThat(payment.getDescription()).isEqualTo("Lunch");
    assertThat(payment.getSourceFunding()).isEqualTo(Funding.WALLET);
    assertThat(payment.getDestinationFunding()).isEqualTo(Funding.WALLET);
    assertThat(payment.getSourceCardId()).isNull();
    assertThat(payment.getDestinationCardId()).isNull();
    assertThat(payment.getFee()).isNull();
    assertThat(payment.getRejection()).isNull();
    assertThat(paymentOf(operation).getStatus()).isEqualTo(PaymentStatus.COMPLETED);

    assertThat(balance(ana)).isEqualByComparingTo("37.50");
    assertThat(balance(beto)).isEqualByComparingTo("12.50");
    MovementResponse sent = movements(ana).get(0);
    assertThat(sent.getType()).isEqualTo(MovementType.PAYMENT_SENT);
    assertThat(sent.getFundedBy()).isEqualTo(Funding.WALLET);
    assertThat(sent.getAmount()).isEqualByComparingTo("12.50");
    assertThat(sent.getBalanceAfter()).isEqualByComparingTo("37.50");
    assertThat(sent.getOperationId()).isEqualTo(operation);
    assertThat(sent.getCounterpartyPhoneNumber()).isEqualTo(BETO);
    assertThat(sent.getDescription()).isEqualTo("Lunch");
    MovementResponse received = movements(beto).get(0);
    assertThat(received.getType()).isEqualTo(MovementType.PAYMENT_RECEIVED);
    assertThat(received.getBalanceAfter()).isEqualByComparingTo("12.50");
    assertThat(received.getCounterpartyPhoneNumber()).isEqualTo(ANA);
    assertThat(participants.countCommands("MOVE_FUNDS", operation, Duration.ofSeconds(1))).isZero();
  }

  @Test
  void aPaymentWithoutDescriptionIsAccepted() {
    walletOfAna();
    UUID beto = walletOfBeto();
    funded(beto, "5.00");
    Map<String, Object> request = payment(UUID.randomUUID());
    request.put("fromPhoneNumber", BETO);
    request.put("toPhoneNumber", ANA);
    request.put("amount", new BigDecimal("5.00"));
    request.remove("description");

    requestPayment(request)
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("COMPLETED")
        .jsonPath("$.description")
        .doesNotExist();

    assertThat(balance(beto)).isEqualByComparingTo("0.00");
  }

  @Test
  void aRepeatedPaymentCountsOnceAndAChangedOneIsAConflict() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "50.00");
    UUID operation = UUID.randomUUID();
    requestPayment(payment(operation)).expectStatus().isAccepted();

    requestPayment(payment(operation))
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Idempotent-Replayed", "true")
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo(operation.toString())
        .jsonPath("$.status")
        .isEqualTo("COMPLETED");
    Map<String, Object> changed = payment(operation);
    changed.put("amount", new BigDecimal("12.51"));
    requestPayment(changed)
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("OPERATION_ID_CONFLICT");
    Map<String, Object> otherText = payment(operation);
    otherText.put("description", "Dinner");
    requestPayment(otherText).expectStatus().isEqualTo(409);

    assertThat(balance(ana)).isEqualByComparingTo("37.50");
    assertThat(balance(beto)).isEqualByComparingTo("12.50");
    assertThat(count("payments")).isEqualTo(1);
    assertThat(count("wallet_movements")).isEqualTo(3);
  }

  @Test
  void aPaymentTheBalanceDoesNotCoverIsRefusedAndLeavesNothing() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "12.49");
    UUID operation = UUID.randomUUID();

    requestPayment(payment(operation))
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INSUFFICIENT_FUNDS");

    assertThat(balance(ana)).isEqualByComparingTo("12.49");
    assertThat(balance(beto)).isEqualByComparingTo("0.00");
    assertThat(count("payments")).isZero();
    webTestClient
        .get()
        .uri("/api/v1/yanki/payments/{id}", operation)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("PAYMENT_NOT_FOUND");
    // The identifier is still free: the same request works once there is money.
    funded(ana, "0.01");
    requestPayment(payment(operation)).expectStatus().isAccepted();
    assertThat(balance(ana)).isEqualByComparingTo("0.00");
  }

  @Test
  void aPaymentToOrFromANumberWithoutWalletIsNotFound() {
    UUID ana = walletOfAna();
    funded(ana, "50.00");

    requestPayment(payment(UUID.randomUUID()))
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("WALLET_NOT_FOUND")
        .jsonPath("$.message")
        .isEqualTo("The mobile number of the receiver has no wallet.");
    Map<String, Object> unknownSender = payment(UUID.randomUUID());
    unknownSender.put("fromPhoneNumber", "999999999");
    unknownSender.put("toPhoneNumber", ANA);
    requestPayment(unknownSender)
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("The mobile number of the sender has no wallet.");

    assertThat(balance(ana)).isEqualByComparingTo("50.00");
    assertThat(count("payments")).isZero();
  }

  @Test
  void aWalletCannotPayItself() {
    UUID ana = walletOfAna();
    funded(ana, "50.00");
    Map<String, Object> request = payment(UUID.randomUUID());
    request.put("toPhoneNumber", ANA);

    requestPayment(request)
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("SAME_WALLET");

    assertThat(balance(ana)).isEqualByComparingTo("50.00");
  }

  @Test
  void aPaymentWithWrongDataIsRefused() {
    UUID ana = walletOfAna();
    walletOfBeto();
    funded(ana, "50.00");

    refused("amount", new BigDecimal("0.00"));
    refused("amount", new BigDecimal("-1.00"));
    refused("amount", new BigDecimal("1.005"));
    refused("amount", null);
    refused("operationId", "not-a-uuid");
    refused("operationId", null);
    refused("toPhoneNumber", "12345");
    refused("fromPhoneNumber", null);
    refused("description", "");
    refused("description", "x".repeat(141));
    webTestClient
        .get()
        .uri("/api/v1/yanki/payments/not-a-uuid")
        .exchange()
        .expectStatus()
        .isBadRequest();

    assertThat(balance(ana)).isEqualByComparingTo("50.00");
    assertThat(count("payments")).isZero();
  }

  @Test
  void paymentsAtTheSameTimeNeverSpendMoreThanTheWalletHas() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "50.00");

    List<Integer> statuses =
        Flux.range(0, 8)
            .flatMap(
                index ->
                    Mono.fromCallable(
                            () -> {
                              Map<String, Object> request = payment(UUID.randomUUID());
                              request.put("amount", new BigDecimal("10.00"));
                              return requestPayment(request)
                                  .returnResult(Void.class)
                                  .getStatus()
                                  .value();
                            })
                        .subscribeOn(Schedulers.boundedElastic()),
                8)
            .collectList()
            .block();

    assertThat(statuses).hasSize(8);
    assertThat(statuses).filteredOn(status -> status == 202).hasSize(5);
    assertThat(statuses).filteredOn(status -> status == 422).hasSize(3);
    assertThat(balance(ana)).isEqualByComparingTo("0.00");
    assertThat(balance(beto)).isEqualByComparingTo("50.00");
    assertThat(count("payments")).isEqualTo(5);
    assertThat(movements(beto)).hasSize(5);
  }

  @Test
  void paymentsInBothDirectionsAtTheSameTimeAllGoThrough() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "40.00");
    funded(beto, "40.00");

    List<Integer> statuses =
        Flux.range(0, 8)
            .flatMap(
                index ->
                    Mono.fromCallable(
                            () -> {
                              Map<String, Object> request = payment(UUID.randomUUID());
                              if (index % 2 == 1) {
                                request.put("fromPhoneNumber", BETO);
                                request.put("toPhoneNumber", ANA);
                              }
                              request.put("amount", new BigDecimal("10.00"));
                              return requestPayment(request)
                                  .returnResult(Void.class)
                                  .getStatus()
                                  .value();
                            })
                        .subscribeOn(Schedulers.boundedElastic()),
                8)
            .collectList()
            .block();

    assertThat(statuses).hasSize(8).containsOnly(202);
    assertThat(balance(ana)).isEqualByComparingTo("40.00");
    assertThat(balance(beto)).isEqualByComparingTo("40.00");
    assertThat(count("wallet_movements")).isEqualTo(18);
  }

  // ----- limits

  @Test
  void aPaymentAboveTheMaximumPerPaymentIsRefusedAndLeavesNothing() {
    UUID ana = walletOfAna();
    walletOfBeto();
    funded(ana, "600.00");

    pay(UUID.randomUUID(), ANA, BETO, "500.01")
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("PAYMENT_LIMIT_EXCEEDED");

    assertThat(count("payments")).isZero();
    assertThat(balance(ana)).isEqualByComparingTo("600.00");
    pay(UUID.randomUUID(), ANA, BETO, "500.00").expectStatus().isAccepted();
  }

  @Test
  void aWalletCannotSendMoreThanTheDailyMaximumAndARepeatIsStillAnswered() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "2100.00");
    UUID first = UUID.randomUUID();
    pay(first, ANA, BETO, "500.00").expectStatus().isAccepted();
    pay(UUID.randomUUID(), ANA, BETO, "500.00").expectStatus().isAccepted();
    pay(UUID.randomUUID(), ANA, BETO, "500.00").expectStatus().isAccepted();
    pay(UUID.randomUUID(), ANA, BETO, "499.99").expectStatus().isAccepted();

    pay(UUID.randomUUID(), ANA, BETO, "0.02")
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("DAILY_LIMIT_EXCEEDED");

    assertThat(count("payments")).isEqualTo(4);
    assertThat(balance(ana)).isEqualByComparingTo("100.01");
    // The limit is reached exactly, a repeat is answered as before and the other wallet has its
    // own day.
    pay(UUID.randomUUID(), ANA, BETO, "0.01").expectStatus().isAccepted();
    pay(first, ANA, BETO, "500.00")
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Idempotent-Replayed", "true");
    pay(UUID.randomUUID(), BETO, ANA, "500.00").expectStatus().isAccepted();
    assertThat(balance(beto)).isEqualByComparingTo("1500.00");
  }

  @Test
  void paymentsOfYesterdayDoNotCountForToday() {
    UUID ana = walletOfAna();
    walletOfBeto();
    funded(ana, "2500.00");
    for (int sent = 0; sent < 4; sent++) {
      pay(UUID.randomUUID(), ANA, BETO, "500.00").expectStatus().isAccepted();
    }
    pay(UUID.randomUUID(), ANA, BETO, "500.00").expectStatus().isEqualTo(422);

    databaseClient
        .sql("UPDATE payments SET created_at = created_at - INTERVAL '1 day'")
        .then()
        .block();

    pay(UUID.randomUUID(), ANA, BETO, "500.00").expectStatus().isAccepted();
  }

  private WebTestClient.ResponseSpec pay(UUID operationId, String from, String to, String amount) {
    Map<String, Object> request = payment(operationId);
    request.put("fromPhoneNumber", from);
    request.put("toPhoneNumber", to);
    request.put("amount", new BigDecimal(amount));
    return requestPayment(request);
  }

  private void refused(String field, Object value) {
    Map<String, Object> request = payment(UUID.randomUUID());
    request.put(field, value);
    requestPayment(request)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");
  }
}
