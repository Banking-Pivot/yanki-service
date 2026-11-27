package com.banking.yankiservice.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.Funding;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementType;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletResponse;
import com.banking.yankiservice.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Wallets through the REST API: opening one, reading them and loading money. */
class WalletApiTest extends IntegrationTest {

  // ----- opening a wallet

  @Test
  void aWalletIsOpenedWithItsDataAndNoMoney() {
    Map<String, Object> request = registration(ANA, "45678912");
    request.put("email", "Ana.Perez@Example.com");

    WalletResponse created =
        post("/api/v1/yanki/wallets", request)
            .expectStatus()
            .isCreated()
            .expectHeader()
            .value(
                "Location", location -> assertThat(location).startsWith("/api/v1/yanki/wallets/"))
            .expectBody(WalletResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(created.getId()).isNotNull();
    assertThat(created.getDocumentType().getValue()).isEqualTo("DNI");
    assertThat(created.getDocumentNumber()).isEqualTo("45678912");
    assertThat(created.getPhoneNumber()).isEqualTo(ANA);
    assertThat(created.getImei()).isEqualTo("356938035643809");
    assertThat(created.getEmail()).isEqualTo("ana.perez@example.com");
    assertThat(created.getBalance()).isEqualByComparingTo("0.00");
    assertThat(created.getCurrency().getValue()).isEqualTo("PEN");
    assertThat(created.getCard()).isNull();
    assertThat(created.getCreatedAt()).isEqualTo(created.getUpdatedAt());

    WalletResponse read = wallet(created.getId());
    assertThat(read.getPhoneNumber()).isEqualTo(ANA);
    assertThat(read.getBalance()).isEqualByComparingTo("0.00");
    assertThat(read.getCreatedAt()).isEqualTo(created.getCreatedAt());
  }

  @Test
  void aForeignDocumentIsStoredInUpperCase() {
    Map<String, Object> request = registration(ANA, "ab1234567");
    request.put("documentType", "CEX");

    post("/api/v1/yanki/wallets", request)
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.documentType")
        .isEqualTo("CEX")
        .jsonPath("$.documentNumber")
        .isEqualTo("AB1234567");
  }

  @Test
  void aDocumentThatDoesNotMatchItsTypeIsRefused() {
    post("/api/v1/yanki/wallets", registration(ANA, "123456789"))
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_DOCUMENT_NUMBER")
        .jsonPath("$.path")
        .isEqualTo("/api/v1/yanki/wallets");

    Map<String, Object> passport = registration(ANA, "45678912");
    passport.put("documentType", "PASSPORT");
    post("/api/v1/yanki/wallets", passport).expectStatus().isCreated();
    assertThat(count("wallets")).isEqualTo(1);
  }

  @Test
  void aRequestWithWrongDataIsRefusedBeforeAnythingIsStored() {
    refused("phoneNumber", "812345678");
    refused("phoneNumber", "98765432");
    refused("phoneNumber", "+51987654321");
    refused("imei", "35693803564380");
    refused("imei", "35693803564380A");
    refused("email", "not-an-email");
    refused("documentNumber", "123");
    refused("documentType", "RUC");
    refused("documentType", null);
    refused("email", null);

    Map<String, Object> unknownField = registration(ANA, "45678912");
    unknownField.put("balance", 1000);
    post("/api/v1/yanki/wallets", unknownField).expectStatus().isBadRequest();

    webTestClient
        .post()
        .uri("/api/v1/yanki/wallets")
        .contentType(MediaType.TEXT_PLAIN)
        .bodyValue("phone")
        .exchange()
        .expectStatus()
        .isEqualTo(415);
    assertThat(count("wallets")).isZero();
  }

  @Test
  void aPhoneAndADocumentOpenOneWalletOnly() {
    walletOfAna();

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

    Map<String, Object> sameNumberOtherType = registration(BETO, "45678912");
    sameNumberOtherType.put("documentType", "PASSPORT");
    post("/api/v1/yanki/wallets", sameNumberOtherType).expectStatus().isCreated();
    assertThat(count("wallets")).isEqualTo(2);
  }

  // ----- reading wallets

  @Test
  void aWalletThatDoesNotExistIsNotFound() {
    UUID unknown = UUID.randomUUID();

    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets/{id}", unknown)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("WALLET_NOT_FOUND");
    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets/{id}/movements", unknown)
        .exchange()
        .expectStatus()
        .isNotFound();
    topUp(unknown, UUID.randomUUID(), "10.00").expectStatus().isNotFound();
    linkCard(unknown, UUID.randomUUID()).expectStatus().isNotFound();
    webTestClient
        .delete()
        .uri("/api/v1/yanki/wallets/{id}/card", unknown)
        .exchange()
        .expectStatus()
        .isNotFound();
    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets/not-a-uuid")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void walletsAreListedNewestFirstAndFoundByPhone() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();

    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.totalElements")
        .isEqualTo(2)
        .jsonPath("$.totalPages")
        .isEqualTo(1)
        .jsonPath("$.page")
        .isEqualTo(0)
        .jsonPath("$.items[0].id")
        .isEqualTo(beto.toString())
        .jsonPath("$.items[1].id")
        .isEqualTo(ana.toString());
    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets?page=1&size=1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(1)
        .jsonPath("$.items[0].id")
        .isEqualTo(ana.toString())
        .jsonPath("$.totalPages")
        .isEqualTo(2);
    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets?phoneNumber={phone}", ANA)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.totalElements")
        .isEqualTo(1)
        .jsonPath("$.items[0].id")
        .isEqualTo(ana.toString());
    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets?phoneNumber=999999999")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items.length()")
        .isEqualTo(0);
    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets?phoneNumber=123")
        .exchange()
        .expectStatus()
        .isBadRequest();
    webTestClient
        .get()
        .uri("/api/v1/yanki/wallets?size=500")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  // ----- loading money

  @Test
  void aTopUpAddsToTheBalanceAndLeavesItsMovement() {
    UUID wallet = walletOfAna();
    UUID operation = UUID.randomUUID();

    MovementResponse movement =
        topUp(wallet, operation, "100.00")
            .expectStatus()
            .isCreated()
            .expectHeader()
            .doesNotExist("Idempotent-Replayed")
            .expectBody(MovementResponse.class)
            .returnResult()
            .getResponseBody();
    funded(wallet, "0.50");

    assertThat(movement.getWalletId()).isEqualTo(wallet);
    assertThat(movement.getType()).isEqualTo(MovementType.TOP_UP);
    assertThat(movement.getFundedBy()).isEqualTo(Funding.WALLET);
    assertThat(movement.getAmount()).isEqualByComparingTo("100.00");
    assertThat(movement.getBalanceAfter()).isEqualByComparingTo("100.00");
    assertThat(movement.getOperationId()).isEqualTo(operation);
    assertThat(movement.getCounterpartyPhoneNumber()).isNull();
    assertThat(balance(wallet)).isEqualByComparingTo("100.50");
    List<MovementResponse> recorded = movements(wallet);
    assertThat(recorded).hasSize(2);
    assertThat(recorded.get(0).getBalanceAfter()).isEqualByComparingTo("100.50");
    assertThat(recorded.get(1).getId()).isEqualTo(movement.getId());
  }

  @Test
  void aRepeatedTopUpCountsOnceAndAChangedOneIsAConflict() {
    UUID wallet = walletOfAna();
    UUID operation = UUID.randomUUID();
    MovementResponse first =
        topUp(wallet, operation, "100.00")
            .expectBody(MovementResponse.class)
            .returnResult()
            .getResponseBody();

    topUp(wallet, operation, "100.00")
        .expectStatus()
        .isCreated()
        .expectHeader()
        .valueEquals("Idempotent-Replayed", "true")
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo(first.getId().toString())
        .jsonPath("$.balanceAfter")
        .isEqualTo(100.00);
    topUp(wallet, operation, "100.01")
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("OPERATION_ID_CONFLICT");

    assertThat(balance(wallet)).isEqualByComparingTo("100.00");
    assertThat(count("wallet_movements")).isEqualTo(1);
  }

  @Test
  void theSameOperationIdentifierMayBeUsedOnAnotherWallet() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    UUID operation = UUID.randomUUID();

    topUp(ana, operation, "10.00").expectStatus().isCreated();
    topUp(beto, operation, "20.00")
        .expectStatus()
        .isCreated()
        .expectHeader()
        .doesNotExist("Idempotent-Replayed");

    assertThat(balance(ana)).isEqualByComparingTo("10.00");
    assertThat(balance(beto)).isEqualByComparingTo("20.00");
  }

  @Test
  void aTopUpWithAWrongAmountIsRefused() {
    UUID wallet = walletOfAna();

    topUp(wallet, UUID.randomUUID(), "0.00").expectStatus().isBadRequest();
    topUp(wallet, UUID.randomUUID(), "-5.00").expectStatus().isBadRequest();
    topUp(wallet, UUID.randomUUID(), "10.005").expectStatus().isBadRequest();
    post("/api/v1/yanki/wallets/{id}/top-ups", Map.of("amount", 10), wallet)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");

    assertThat(balance(wallet)).isEqualByComparingTo("0.00");
    assertThat(movements(wallet)).isEmpty();
  }

  @Test
  void topUpsAtTheSameTimeAreAllCounted() {
    UUID wallet = walletOfAna();

    List<Integer> statuses =
        reactor.core.publisher.Flux.range(0, 8)
            .flatMap(
                index ->
                    reactor.core.publisher.Mono.fromCallable(
                            () ->
                                topUp(wallet, UUID.randomUUID(), "10.00")
                                    .returnResult(Void.class)
                                    .getStatus()
                                    .value())
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()),
                8)
            .collectList()
            .block();

    assertThat(statuses).hasSize(8).containsOnly(201);
    assertThat(balance(wallet)).isEqualByComparingTo("80.00");
    assertThat(movements(wallet)).hasSize(8);
  }

  private void refused(String field, Object value) {
    Map<String, Object> request = registration(ANA, "45678912");
    request.put(field, value);
    post("/api/v1/yanki/wallets", request)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");
  }
}
