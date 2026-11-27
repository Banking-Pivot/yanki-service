package com.banking.yankiservice.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays Card Service and Transaction Service on a real Kafka: it reads the requests Yanki Service
 * sends and answers them the way those services would.
 *
 * <p>Requests are looked up by payment, link or card, so what other tests left in the topics does
 * not get in the way.
 */
public final class TestParticipants {

  public static final String CARD_COMMANDS = "banking.card.commands";
  public static final String TRANSACTION_COMMANDS = "banking.transaction.commands";
  public static final String REPLIES = "banking.yanki.replies";

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private final KafkaConsumer<String, String> consumer;
  private final KafkaProducer<String, String> producer;
  private final List<ReceivedCommand> seen = new ArrayList<>();
  private final JsonMapper json =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private TestParticipants(String bootstrapServers) {
    Map<String, Object> reading = new LinkedHashMap<>();
    reading.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    reading.put(ConsumerConfig.GROUP_ID_CONFIG, "test-participants-" + UUID.randomUUID());
    reading.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    reading.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    reading.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    reading.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = new KafkaConsumer<>(reading);
    consumer.subscribe(List.of(CARD_COMMANDS, TRANSACTION_COMMANDS));

    Map<String, Object> writing = new LinkedHashMap<>();
    writing.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    writing.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    writing.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producer = new KafkaProducer<>(writing);
  }

  /** Connects once the service created the topics. */
  public static TestParticipants connect(String bootstrapServers) {
    return new TestParticipants(bootstrapServers);
  }

  /** The first request of that type with that transactionId; fails if none arrives. */
  public ReceivedCommand awaitCommand(String type, UUID transactionId) {
    return awaitCommand(type, "transactionId", transactionId, 1);
  }

  /** The n-th request of that type with that transactionId, counting from 1. */
  public ReceivedCommand awaitCommand(String type, UUID transactionId, int occurrence) {
    return awaitCommand(type, "transactionId", transactionId, occurrence);
  }

  /** The first request of that type whose field has that value. */
  public ReceivedCommand awaitCommandAbout(String type, String field, UUID value) {
    return awaitCommand(type, field, value, 1);
  }

  /** The n-th request of that type whose field has that value, counting from 1. */
  public ReceivedCommand awaitCommandAbout(String type, String field, UUID value, int occurrence) {
    return awaitCommand(type, field, value, occurrence);
  }

  private ReceivedCommand awaitCommand(String type, String field, UUID value, int occurrence) {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (true) {
      List<ReceivedCommand> matches = commands(type, field, value);
      if (matches.size() >= occurrence) {
        return matches.get(occurrence - 1);
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "Expected command "
                + type
                + " #"
                + occurrence
                + " with "
                + field
                + " "
                + value
                + " but saw "
                + matches.size());
      }
      poll(Duration.ofMillis(200));
    }
  }

  /** How many requests of that type the transactionId got, after listening for a while longer. */
  public int countCommands(String type, UUID transactionId, Duration listenFor) {
    long deadline = System.nanoTime() + listenFor.toNanos();
    while (System.nanoTime() < deadline) {
      poll(Duration.ofMillis(200));
    }
    return commands(type, "transactionId", transactionId).size();
  }

  // ----- answers of Card Service

  /**
   * The card exists and is active; the answer carries what Card Service really sends.
   *
   * @param holderDocumentType document of the customer that holds the card; null leaves both fields
   *     out, as for a card issued before Card Service kept them
   */
  public void cardResolved(
      UUID linkId, UUID cardId, String holderDocumentType, String holderDocumentNumber) {
    UUID mainAccount = UUID.randomUUID();
    Map<String, Object> body = reply("CARD_RESOLVED", linkId);
    body.put("cardId", cardId.toString());
    body.put("customerId", "6741f0c2a9b3d85e1c7f4a20");
    body.put("mainAccountId", mainAccount.toString());
    body.put("accountIds", List.of(mainAccount.toString()));
    if (holderDocumentType != null) {
      body.put("holderDocumentType", holderDocumentType);
      body.put("holderDocumentNumber", holderDocumentNumber);
    }
    send(linkId, body);
  }

  public void cardRejected(UUID linkId, UUID cardId, String code, String message) {
    Map<String, Object> body = rejected("CARD_RESOLUTION_REJECTED", linkId, code, message);
    body.put("cardId", cardId.toString());
    send(linkId, body);
  }

  // ----- answers of Transaction Service

  /** The money moved; the answer carries what Transaction Service really sends. */
  public void fundsMoved(UUID paymentId, String fee) {
    Map<String, Object> body = reply("FUNDS_MOVED", paymentId);
    body.put("sourceAccountId", UUID.randomUUID().toString());
    body.put("amount", new BigDecimal("12.50"));
    if (fee != null) {
      body.put("fee", new BigDecimal(fee));
    }
    body.put("occurredAt", "2026-11-20T15:00:02Z");
    send(paymentId, body);
  }

  public void fundsMoveRejected(UUID paymentId, String code, String message) {
    send(paymentId, rejected("FUNDS_MOVE_REJECTED", paymentId, code, message));
  }

  /** Puts any text in the replies topic, to send what a well-behaved service never would. */
  public void send(String key, String value, String correlationId) {
    ProducerRecord<String, String> message = new ProducerRecord<>(REPLIES, key, value);
    if (correlationId != null) {
      message.headers().add("X-Correlation-ID", correlationId.getBytes(StandardCharsets.UTF_8));
    }
    try {
      producer.send(message).get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (ExecutionException failed) {
      throw new IllegalStateException(failed.getCause());
    }
  }

  private void send(UUID transactionId, Map<String, Object> body) {
    send(transactionId.toString(), json.writeValueAsString(body), "reply-" + transactionId);
  }

  private static Map<String, Object> reply(String type, UUID transactionId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("transactionId", transactionId.toString());
    return body;
  }

  private static Map<String, Object> rejected(
      String type, UUID transactionId, String code, String message) {
    Map<String, Object> body = reply(type, transactionId);
    body.put("code", code);
    body.put("message", message);
    return body;
  }

  private List<ReceivedCommand> commands(String type, String field, UUID value) {
    return seen.stream()
        .filter(command -> type.equals(command.body().get("type")))
        .filter(command -> value.toString().equals(command.body().get(field)))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private void poll(Duration wait) {
    for (ConsumerRecord<String, String> message : consumer.poll(wait)) {
      Header header = message.headers().lastHeader("X-Correlation-ID");
      seen.add(
          new ReceivedCommand(
              message.topic(),
              message.key(),
              message.value(),
              (Map<String, Object>) json.readValue(message.value(), Map.class),
              header == null ? null : new String(header.value(), StandardCharsets.UTF_8)));
    }
  }

  /**
   * A request as it was read from Kafka.
   *
   * @param text the value exactly as sent
   * @param body the value parsed; decimals are BigDecimal
   * @param correlationId value of the X-Correlation-ID header, or null
   */
  public record ReceivedCommand(
      String topic, String key, String text, Map<String, Object> body, String correlationId) {

    public String field(String name) {
      Object value = body.get(name);
      return value == null ? null : value.toString();
    }
  }
}
