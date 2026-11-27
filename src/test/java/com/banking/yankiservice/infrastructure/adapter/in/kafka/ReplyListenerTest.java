package com.banking.yankiservice.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.banking.yankiservice.application.port.in.ApplyReplyUseCase;
import com.banking.yankiservice.domain.model.vo.InboundReply;
import com.banking.yankiservice.domain.model.vo.Rejection;
import com.banking.yankiservice.infrastructure.config.YankiProperties;
import com.banking.yankiservice.infrastructure.correlation.CorrelationId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** How the answers are read from Kafka and handed to the use case. */
class ReplyListenerTest {

  private static final UUID ID = UUID.fromString("4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e");
  private static final YankiProperties PROPERTIES =
      new YankiProperties(
          new YankiProperties.Messaging(
              "banking.transaction.commands",
              "banking.card.commands",
              "banking.yanki.replies",
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              Duration.ofSeconds(10),
              Duration.ofSeconds(30),
              50),
          new YankiProperties.Limits(new BigDecimal("500.00"), new BigDecimal("2000.00")),
          ZoneId.of("America/Lima"));

  private final ApplyReplyUseCase applyReply = mock(ApplyReplyUseCase.class);
  private final ReplyListener listener =
      new ReplyListener(applyReply, JsonMapper.builder().build(), PROPERTIES);

  @Test
  void theListenerReadsTheTopicOfTheSettings() {
    assertThat(listener.getRepliesTopic()).isEqualTo("banking.yanki.replies");
  }

  @Test
  void fundsMovedIsReadWithItsFeeAndTheFieldsItDoesNotUseAreIgnored() {
    when(applyReply.execute(any())).thenReturn(Mono.empty());

    listener.onReply(
        message(
            "{\"type\":\"FUNDS_MOVED\",\"transactionId\":\""
                + ID
                + "\",\"sourceAccountId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"amount\":25.50,\"fee\":1.50,\"occurredAt\":\"2026-11-20T15:00:00Z\","
                + "\"somethingNew\":true}",
            "corr-7"));

    verify(applyReply).execute(new InboundReply.FundsMoved(ID, new BigDecimal("1.50")));
  }

  @Test
  void theOtherAnswersAreReadToo() {
    when(applyReply.execute(any())).thenReturn(Mono.empty());

    listener.onReply(
        message(
            "{\"type\":\"FUNDS_MOVE_REJECTED\",\"transactionId\":\""
                + ID
                + "\",\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"Not enough balance.\"}",
            null));
    listener.onReply(
        message(
            "{\"type\":\"CARD_RESOLVED\",\"transactionId\":\""
                + ID
                + "\",\"cardId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"customerId\":\"c-1\",\"mainAccountId\":\"22222222-2222-4222-8222-222222222222\","
                + "\"accountIds\":[\"22222222-2222-4222-8222-222222222222\"],"
                + "\"holderDocumentType\":\"DNI\",\"holderDocumentNumber\":\"45678912\"}",
            null));
    listener.onReply(
        message(
            "{\"type\":\"CARD_RESOLUTION_REJECTED\",\"transactionId\":\""
                + ID
                + "\",\"cardId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"code\":\"CARD_NOT_ACTIVE\",\"message\":\"The card is blocked.\"}",
            null));

    verify(applyReply)
        .execute(
            new InboundReply.FundsMoveRejected(
                ID, new Rejection("INSUFFICIENT_FUNDS", "Not enough balance.")));
    verify(applyReply).execute(new InboundReply.CardResolved(ID, "DNI", "45678912"));
    verify(applyReply)
        .execute(
            new InboundReply.CardResolutionRejected(
                ID, new Rejection("CARD_NOT_ACTIVE", "The card is blocked.")));
  }

  @Test
  void theCorrelationIdentifierOfTheHeaderTravelsWithTheAnswer() {
    AtomicReference<String> seen = new AtomicReference<>();
    when(applyReply.execute(any()))
        .thenReturn(
            Mono.deferContextual(
                context -> {
                  seen.set(context.get(CorrelationId.KEY));
                  return Mono.empty();
                }));

    listener.onReply(
        message("{\"type\":\"CARD_RESOLVED\",\"transactionId\":\"" + ID + "\"}", "corr-7"));

    assertThat(seen).hasValue("corr-7");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "{}",
        "{\"type\":\"SOMETHING_ELSE\",\"transactionId\":\"4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e\"}",
        "{\"type\":\"FUNDS_MOVED\"}",
        "{\"type\":\"FUNDS_MOVE_REJECTED\",\"transactionId\":\"4a6c8e0b-2d4f-4b1a-9c3e-5f7a9b1c3d5e\"}",
        "{\"type\":\"CARD_RESOLVED\",\"transactionId\":\"not-a-uuid\"}"
      })
  void anAnswerThatCannotBeReadIsSkipped(String value) {
    listener.onReply(message(value, "corr-7"));

    verifyNoInteractions(applyReply);
  }

  @Test
  void anAnswerThatCannotBeStoredFailsSoTheContainerRetriesIt() {
    when(applyReply.execute(any()))
        .thenReturn(Mono.error(new IllegalStateException("database down")));

    assertThatThrownBy(
            () ->
                listener.onReply(
                    message("{\"type\":\"CARD_RESOLVED\",\"transactionId\":\"" + ID + "\"}", null)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ConsumerRecord<String, String> message(String value, String correlationId) {
    ConsumerRecord<String, String> message =
        new ConsumerRecord<>("banking.yanki.replies", 0, 7L, ID.toString(), value);
    if (correlationId != null) {
      message.headers().add(CorrelationId.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    }
    return message;
  }
}
