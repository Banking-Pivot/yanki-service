package com.banking.yankiservice.infrastructure.adapter.in.kafka;

import com.banking.yankiservice.application.port.in.ApplyReplyUseCase;
import com.banking.yankiservice.domain.model.InboundReply;
import com.banking.yankiservice.infrastructure.config.YankiProperties;
import com.banking.yankiservice.infrastructure.correlation.CorrelationId;
import java.time.Duration;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives the answers of Card Service and Transaction Service and applies them to the card link or
 * the payment they are about.
 *
 * <p>The method waits until the answer is stored before it returns. It runs on the thread of the
 * Kafka consumer, not on one that serves requests, so waiting there is safe and it is what keeps
 * the answers in order: the position in the topic only moves forward once an answer is fully
 * handled. An answer that fails is retried by the container (see MessagingConfiguration).
 *
 * <p>An answer that cannot be read (not JSON, unknown type, missing fields) is logged and skipped:
 * retrying would never fix it.
 */
@Component
public class ReplyListener {

  private static final Logger LOG = LoggerFactory.getLogger(ReplyListener.class);
  private static final Duration LIMIT = Duration.ofSeconds(30);

  private final ApplyReplyUseCase applyReply;
  private final JsonMapper json;
  private final String repliesTopic;

  /** Creates the listener with the use case, the JSON mapper and the name of the topic. */
  public ReplyListener(ApplyReplyUseCase applyReply, JsonMapper json, YankiProperties properties) {
    this.applyReply = applyReply;
    this.json = json;
    this.repliesTopic = properties.messaging().repliesTopic();
  }

  /** Topic the listener reads, taken from the settings. */
  public String getRepliesTopic() {
    return repliesTopic;
  }

  /**
   * Applies one answer. The correlation identifier of its header is in every log line written while
   * it is handled.
   *
   * @param message the record read from the replies topic
   */
  @KafkaListener(topics = "#{__listener.repliesTopic}")
  public void onReply(ConsumerRecord<String, String> message) {
    String correlationId = MessageCorrelation.of(message);
    try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.KEY, correlationId)) {
      read(message)
          .ifPresent(
              reply ->
                  applyReply
                      .execute(reply)
                      .contextWrite(context -> context.put(CorrelationId.KEY, correlationId))
                      .block(LIMIT));
    }
  }

  private Optional<InboundReply> read(ConsumerRecord<String, String> message) {
    try {
      return Optional.of(json.readValue(message.value(), ReplyMessage.class).toReply());
    } catch (JacksonException | IllegalArgumentException | NullPointerException unreadable) {
      // The content is not logged: it may be anything.
      LOG.warn(
          "Reply skipped, it cannot be read. topic={}, partition={}, offset={}, reason={}",
          message.topic(),
          message.partition(),
          message.offset(),
          unreadable.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
