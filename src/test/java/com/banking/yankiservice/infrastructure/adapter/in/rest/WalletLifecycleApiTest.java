package com.banking.yankiservice.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentStatus;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletStatus;
import com.banking.yankiservice.support.IntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Updating the contact data of a wallet and closing it, through the REST API. */
class WalletLifecycleApiTest extends IntegrationTest {

  private static final String NEW_IMEI = "490154203237518";

  // ----- updating the contact data

  @Test
  void anUpdateReplacesTheImeiAndTheEmailAndKeepsTheRest() {
    UUID ana = walletOfAna();
    funded(ana, "10.00");
    WalletResponse before = wallet(ana);

    WalletResponse updated =
        update(ana, contact(NEW_IMEI, "Ana.New@Example.com"))
            .expectStatus()
            .isOk()
            .expectBody(WalletResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(updated.getId()).isEqualTo(ana);
    assertThat(updated.getStatus()).isEqualTo(WalletStatus.ACTIVE);
    assertThat(updated.getImei()).isEqualTo(NEW_IMEI);
    assertThat(updated.getEmail()).isEqualTo("ana.new@example.com");
    assertThat(updated.getPhoneNumber()).isEqualTo(ANA);
    assertThat(updated.getDocumentNumber()).isEqualTo("45678912");
    assertThat(updated.getBalance()).isEqualByComparingTo("10.00");
    assertThat(updated.getCreatedAt()).isEqualTo(before.getCreatedAt());
    assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(before.getUpdatedAt());
    WalletResponse read = wallet(ana);
    assertThat(read.getImei()).isEqualTo(NEW_IMEI);
    assertThat(read.getEmail()).isEqualTo("ana.new@example.com");
    assertThat(read.getUpdatedAt()).isEqualTo(updated.getUpdatedAt());
  }

  @Test
  void anUpdateWithWrongDataIsRefusedAndChangesNothing() {
    UUID ana = walletOfAna();

    invalid(ana, contact("35693803564380", "ana@example.com"));
    invalid(ana, contact("35693803564380A", "ana@example.com"));
    invalid(ana, contact(NEW_IMEI, "not-an-email"));
    invalid(ana, contact(NEW_IMEI, null));
    invalid(ana, contact(null, "ana@example.com"));
    Map<String, Object> withPhone = contact(NEW_IMEI, "ana@example.com");
    withPhone.put("phoneNumber", BETO);
    invalid(ana, withPhone);
    webTestClient
        .put()
        .uri("/api/v1/yanki/wallets/{id}", ana)
        .contentType(MediaType.TEXT_PLAIN)
        .bodyValue("imei")
        .exchange()
        .expectStatus()
        .isEqualTo(415);
    update(UUID.randomUUID(), contact(NEW_IMEI, "ana@example.com"))
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("WALLET_NOT_FOUND");

    WalletResponse read = wallet(ana);
    assertThat(read.getImei()).isEqualTo("356938035643809");
    assertThat(read.getEmail()).isEqualTo("user" + ANA + "@example.com");
    assertThat(read.getPhoneNumber()).isEqualTo(ANA);
  }

  // ----- closing a wallet

  @Test
  void anEmptyWalletIsClosedAndStaysReadableWithItsMovements() {
    UUID ana = walletOfAna();
    final UUID beto = walletOfBeto();
    funded(ana, "12.50");
    requestedPayment();

    close(ana).expectStatus().isNoContent().expectBody().isEmpty();
    close(ana).expectStatus().isNoContent();

    WalletResponse closed = wallet(ana);
    assertThat(closed.getStatus()).isEqualTo(WalletStatus.CLOSED);
    assertThat(closed.getPhoneNumber()).isEqualTo(ANA);
    assertThat(closed.getBalance()).isEqualByComparingTo("0.00");
    assertThat(movements(ana)).hasSize(2);
    assertThat(wallet(beto).getStatus()).isEqualTo(WalletStatus.ACTIVE);
    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets?phoneNumber={phone}", ANA)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].status")
        .isEqualTo("CLOSED");
    close(UUID.randomUUID())
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("WALLET_NOT_FOUND");
  }

  @Test
  void aWalletWithMoneyOrWithACardIsNotClosed() {
    UUID ana = walletOfAna();
    funded(ana, "0.01");
    refusedClose(ana, "WALLET_HAS_BALANCE");

    UUID beto = walletOfBeto();
    linkCard(beto, UUID.randomUUID()).expectStatus().isAccepted();
    refusedClose(beto, "WALLET_HAS_CARD");
    webTestClient
        .delete()
        .uri("/api/v1/yanki/wallets/{id}/card", beto)
        .exchange()
        .expectStatus()
        .isNoContent();
    close(beto).expectStatus().isNoContent();

    assertThat(wallet(ana).getStatus()).isEqualTo(WalletStatus.ACTIVE);
    assertThat(wallet(beto).getStatus()).isEqualTo(WalletStatus.CLOSED);
  }

  @Test
  void aWalletWithAPaymentInProgressIsClosedOnlyWhenThePaymentIsFinished() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(ana, "12.50");
    linkedCard(beto);
    UUID operation = requestedPayment();
    participants.awaitCommand("MOVE_FUNDS", operation);
    assertThat(balance(ana)).isEqualByComparingTo("0.00");

    refusedClose(ana, "WALLET_HAS_PENDING_PAYMENT");
    participants.fundsMoved(operation, "0.00");
    awaitStatus(operation, PaymentStatus.COMPLETED);

    close(ana).expectStatus().isNoContent();
    assertThat(wallet(ana).getStatus()).isEqualTo(WalletStatus.CLOSED);
  }

  @Test
  void aClosedWalletCanOnlyBeRead() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    funded(beto, "50.00");
    close(ana).expectStatus().isNoContent();
    final WalletResponse closed = wallet(ana);

    refusedAsClosed(topUp(ana, UUID.randomUUID(), "10.00"));
    refusedAsClosed(update(ana, contact(NEW_IMEI, "ana.new@example.com")));
    refusedAsClosed(linkCard(ana, UUID.randomUUID()));
    Map<String, Object> toClosed = payment(UUID.randomUUID());
    toClosed.put("fromPhoneNumber", BETO);
    toClosed.put("toPhoneNumber", ANA);
    refusedAsClosed(requestPayment(toClosed));
    refusedAsClosed(requestPayment(payment(UUID.randomUUID())));
    webTestClient
        .delete()
        .uri("/api/v1/yanki/wallets/{id}/card", ana)
        .exchange()
        .expectStatus()
        .isNoContent();

    WalletResponse read = wallet(ana);
    assertThat(read.getStatus()).isEqualTo(WalletStatus.CLOSED);
    assertThat(read.getImei()).isEqualTo(closed.getImei());
    assertThat(read.getUpdatedAt()).isEqualTo(closed.getUpdatedAt());
    assertThat(read.getCard()).isNull();
    assertThat(balance(beto)).isEqualByComparingTo("50.00");
    assertThat(movements(ana)).isEmpty();
    assertThat(count("payments")).isZero();
  }

  @Test
  void thePhoneAndTheDocumentOfAClosedWalletStayReserved() {
    UUID ana = walletOfAna();
    close(ana).expectStatus().isNoContent();

    post("/api/v1/yanki/wallets", registration(ANA, "40000001"))
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("PHONE_ALREADY_REGISTERED");
    post("/api/v1/yanki/wallets", registration(BETO, "45678912"))
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("DOCUMENT_ALREADY_REGISTERED");
    assertThat(count("wallets")).isEqualTo(1);
  }

  private static Map<String, Object> contact(String imei, String email) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("imei", imei);
    body.put("email", email);
    return body;
  }

  private WebTestClient.ResponseSpec update(UUID walletId, Map<String, Object> body) {
    return webTestClient
        .put()
        .uri("/api/v1/yanki/wallets/{id}", walletId)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private WebTestClient.ResponseSpec close(UUID walletId) {
    return webTestClient.delete().uri("/api/v1/yanki/wallets/{id}", walletId).exchange();
  }

  private void invalid(UUID walletId, Map<String, Object> body) {
    update(walletId, body)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");
  }

  private void refusedClose(UUID walletId, String code) {
    close(walletId).expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo(code);
  }

  private static void refusedAsClosed(WebTestClient.ResponseSpec response) {
    response
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("WALLET_CLOSED");
  }
}
