package com.banking.yankiservice.infrastructure.adapter.out.kafka;

import com.banking.yankiservice.infrastructure.config.YankiProperties;
import com.banking.yankiservice.infrastructure.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends one message to Kafka as JSON text, with the correlation identifier in the {@code
 * X-Correlation-ID} header.
 *
 * <p>The Kafka client can block while it looks for the broker, so the send runs away from the
 * threads that serve requests, and it fails after the configured time instead of waiting.
 */
@Component
class KafkaJsonSender {

  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper json;
  private final Duration publishTimeout;

  KafkaJsonSender(
      KafkaTemplate<String, String> kafka, JsonMapper json, YankiProperties properties) {
    this.kafka = kafka;
    this.json = json;
    this.publishTimeout = properties.messaging().publishTimeout();
  }

  /**
   * Sends the body to the topic.
   *
   * @param key what keeps the messages about the same thing in order
   * @return completion once the broker has the message, or an error when it could not be sent
   */
  Mono<Void> send(String topic, UUID key, Object body, String correlationId) {
    return Mono.fromCallable(() -> message(topic, key, body, correlationId))
        .flatMap(message -> Mono.fromFuture(() -> kafka.send(message)))
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(publishTimeout)
        // The broker's confirmation arrives on the thread of the Kafka client; what follows (the
        // response of the request, the next query) must not run there.
        .publishOn(Schedulers.parallel())
        .then();
  }

  private ProducerRecord<String, String> message(
      String topic, UUID key, Object body, String correlationId) {
    ProducerRecord<String, String> message =
        new ProducerRecord<>(topic, key.toString(), json.writeValueAsString(body));
    message.headers().add(CorrelationId.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    return message;
  }
}
