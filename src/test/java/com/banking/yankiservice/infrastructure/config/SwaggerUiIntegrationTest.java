package com.banking.yankiservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.yankiservice.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** Swagger UI shows the contract the code is generated from. */
class SwaggerUiIntegrationTest extends IntegrationTest {

  @Test
  void theContractIsServedAsItIsWritten() {
    webTestClient
        .get()
        .uri(OpenApiContractConfiguration.CONTRACT_PATH)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            contract ->
                assertThat(contract)
                    .contains("/api/v1/yanki/payments")
                    .contains("Idempotent-Replayed")
                    .contains("operationId: requestPayment"));
  }

  @Test
  void swaggerUiPointsAtTheContract() {
    webTestClient
        .get()
        .uri("/v3/api-docs/swagger-config")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.url")
        .isEqualTo(OpenApiContractConfiguration.CONTRACT_PATH);
  }
}
