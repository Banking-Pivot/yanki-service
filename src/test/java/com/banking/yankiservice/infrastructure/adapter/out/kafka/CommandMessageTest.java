package com.banking.yankiservice.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.yankiservice.domain.model.OutboundCommand;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The exact JSON of the two requests, as the other services read it. */
class CommandMessageTest {

  private static final UUID PAYMENT = UUID.fromString("4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e");
  private static final UUID LINK = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
  private static final UUID SOURCE_CARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID DESTINATION_CARD =
      UUID.fromString("22222222-2222-4222-8222-222222222222");

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void moveFundsCarriesBothCardsWhenBothSidesAreLinked() {
    String text =
        json.writeValueAsString(
            CommandMessage.of(
                new OutboundCommand.MoveFunds(
                    PAYMENT,
                    SOURCE_CARD,
                    DESTINATION_CARD,
                    new BigDecimal("25.50"),
                    "PEN",
                    "Lunch")));

    assertThat(text)
        .isEqualTo(
            "{\"type\":\"MOVE_FUNDS\","
                + "\"transactionId\":\"4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e\","
                + "\"sourceCardId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"destinationCardId\":\"22222222-2222-4222-8222-222222222222\","
                + "\"amount\":25.50,\"currency\":\"PEN\",\"description\":\"Lunch\"}");
  }

  @Test
  void moveFundsLeavesOutTheSideThatUsesTheWalletBalance() {
    String sourceOnly =
        json.writeValueAsString(
            CommandMessage.of(
                new OutboundCommand.MoveFunds(
                    PAYMENT, SOURCE_CARD, null, new BigDecimal("100.00"), "PEN", "Yanki payment")));
    String destinationOnly =
        json.writeValueAsString(
            CommandMessage.of(
                new OutboundCommand.MoveFunds(
                    PAYMENT, null, DESTINATION_CARD, new BigDecimal("0.10"), "PEN", "Rent")));

    assertThat(sourceOnly)
        .isEqualTo(
            "{\"type\":\"MOVE_FUNDS\","
                + "\"transactionId\":\"4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e\","
                + "\"sourceCardId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"amount\":100.00,\"currency\":\"PEN\",\"description\":\"Yanki payment\"}");
    assertThat(destinationOnly)
        .contains("\"destinationCardId\":\"22222222-2222-4222-8222-222222222222\"")
        .contains("\"amount\":0.10")
        .doesNotContain("sourceCardId")
        .doesNotContain("cardId\":null");
  }

  @Test
  void resolveCardSaysWhereToAnswer() {
    String text =
        json.writeValueAsString(
            CommandMessage.of(
                new OutboundCommand.ResolveCard(LINK, SOURCE_CARD), "banking.yanki.replies"));

    assertThat(text)
        .isEqualTo(
            "{\"type\":\"RESOLVE_CARD\","
                + "\"transactionId\":\"7c9e6679-7425-40de-944b-e07fc1f90ae7\","
                + "\"cardId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"replyTo\":\"banking.yanki.replies\"}");
  }
}
