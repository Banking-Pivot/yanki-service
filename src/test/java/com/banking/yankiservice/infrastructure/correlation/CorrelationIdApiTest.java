package com.banking.yankiservice.infrastructure.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.yankiservice.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The correlation identifier of a request: header, error body and the command it starts. */
class CorrelationIdApiTest extends IntegrationTest {

  private static final String RECEIVED = "order-2026.11_20-A";

  @Test
  void theIdentifierReceivedGoesBackInTheHeaderAndInTheErrorBody() {
    webTestClient
        .get()
        .uri("/api/v1/yanki/payments/{id}", UUID.randomUUID())
        .header(CorrelationId.HEADER, RECEIVED)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .valueEquals(CorrelationId.HEADER, RECEIVED)
        .expectBody()
        .jsonPath("$.traceId")
        .isEqualTo(RECEIVED);
  }

  @Test
  void aRequestWithoutIdentifierGetsANewOneAndItsCommandCarriesIt() {
    UUID wallet = walletOfAna();
    UUID card = UUID.randomUUID();

    String generated =
        linkCard(wallet, card)
            .expectStatus()
            .isAccepted()
            .returnResult(Void.class)
            .getResponseHeaders()
            .getFirst(CorrelationId.HEADER);

    assertThat(generated).matches("[0-9a-f-]{36}");
    assertThat(participants.awaitCommandAbout("RESOLVE_CARD", "cardId", card).correlationId())
        .isEqualTo(generated);
    assertThat(generatedFor(webTestClient.get().uri("/api/v1/yanki/wallets")))
        .isNotEqualTo(generated);
  }

  @Test
  void anIdentifierThatCouldBreakALogLineIsReplaced() {
    String tooLong = "x".repeat(65);

    assertThat(generatedFor(headed(tooLong))).isNotEqualTo(tooLong);
    assertThat(generatedFor(headed("two words"))).isNotEqualTo("two words");
    assertThat(generatedFor(headed("{\"injected\":true}"))).doesNotContain("injected");
  }

  private WebTestClient.RequestHeadersSpec<?> headed(String identifier) {
    return webTestClient
        .get()
        .uri("/api/v1/yanki/wallets")
        .header(CorrelationId.HEADER, identifier);
  }

  private static String generatedFor(WebTestClient.RequestHeadersSpec<?> request) {
    String identifier =
        request
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Void.class)
            .getResponseHeaders()
            .getFirst(CorrelationId.HEADER);
    assertThat(identifier).matches("[0-9a-f-]{36}");
    return identifier;
  }
}
