package com.banking.yankiservice.infrastructure.adapter.in.kafka;

import com.banking.yankiservice.domain.model.vo.InboundReply;
import com.banking.yankiservice.domain.model.vo.Rejection;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * An answer of Card Service or Transaction Service as it travels in Kafka: one JSON object whose
 * {@code type} says what happened. Each type uses some of the fields; fields this service does not
 * read (the accounts of a card, the accounts that moved, the instant) are ignored, so the other
 * services can add data without breaking it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplyMessage(
    String type,
    UUID transactionId,
    BigDecimal fee,
    String code,
    String message,
    String holderDocumentType,
    String holderDocumentNumber) {

  /**
   * The answer in the terms of the domain.
   *
   * @throws IllegalArgumentException when the type is missing or unknown
   * @throws NullPointerException when a field the type needs is missing
   */
  InboundReply toReply() {
    if (type == null) {
      throw new IllegalArgumentException("The reply has no type.");
    }
    return switch (type) {
      case "CARD_RESOLVED" ->
          new InboundReply.CardResolved(transactionId, holderDocumentType, holderDocumentNumber);
      case "CARD_RESOLUTION_REJECTED" ->
          new InboundReply.CardResolutionRejected(transactionId, new Rejection(code, message));
      case "FUNDS_MOVED" -> new InboundReply.FundsMoved(transactionId, fee);
      case "FUNDS_MOVE_REJECTED" ->
          new InboundReply.FundsMoveRejected(transactionId, new Rejection(code, message));
      default -> throw new IllegalArgumentException("Unknown reply type.");
    };
  }
}
