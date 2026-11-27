package com.banking.yankiservice.infrastructure.adapter.in.kafka;

import com.banking.yankiservice.infrastructure.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Reads the correlation identifier a Kafka message carries. */
final class MessageCorrelation {

  private MessageCorrelation() {}

  /**
   * The identifier to handle the message under.
   *
   * @return the value of its {@code X-Correlation-ID} header when it is usable, a new one otherwise
   */
  static String of(ConsumerRecord<String, String> message) {
    Header header = message.headers().lastHeader(CorrelationId.HEADER);
    return CorrelationId.accept(
        header == null || header.value() == null
            ? null
            : new String(header.value(), StandardCharsets.UTF_8));
  }
}
