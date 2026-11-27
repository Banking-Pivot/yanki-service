package com.banking.yankiservice.infrastructure.adapter.out.kafka;

import com.banking.yankiservice.domain.model.vo.OutboundCommand;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A request as it travels in Kafka: one JSON object whose {@code type} says what is asked. Fields
 * that do not apply to a type are left out.
 *
 * @param transactionId the payment (MOVE_FUNDS) or the link request (RESOLVE_CARD); the answer
 *     carries it back
 * @param cardId for RESOLVE_CARD, the card to look up
 * @param sourceCardId for MOVE_FUNDS, the card whose main account pays; left out when the sender
 *     paid from the wallet balance
 * @param destinationCardId for MOVE_FUNDS, the card whose main account receives; left out when the
 *     receiving wallet is credited here
 * @param replyTo for RESOLVE_CARD, the topic Card Service answers on
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandMessage(
    String type,
    UUID transactionId,
    UUID cardId,
    UUID sourceCardId,
    UUID destinationCardId,
    BigDecimal amount,
    String currency,
    String description,
    String replyTo) {

  static final String MOVE_FUNDS = "MOVE_FUNDS";
  static final String RESOLVE_CARD = "RESOLVE_CARD";

  static CommandMessage of(OutboundCommand.MoveFunds command) {
    return new CommandMessage(
        MOVE_FUNDS,
        command.transactionId(),
        null,
        command.sourceCardId(),
        command.destinationCardId(),
        command.amount(),
        command.currency(),
        command.description(),
        null);
  }

  static CommandMessage of(OutboundCommand.ResolveCard command, String replyTo) {
    return new CommandMessage(
        RESOLVE_CARD,
        command.transactionId(),
        command.cardId(),
        null,
        null,
        null,
        null,
        null,
        replyTo);
  }
}
