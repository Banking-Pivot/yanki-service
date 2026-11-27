package com.banking.yankiservice.infrastructure.adapter.out.kafka;

import com.banking.yankiservice.application.port.out.CommandPublisherPort;
import com.banking.yankiservice.domain.model.OutboundCommand;
import com.banking.yankiservice.infrastructure.config.YankiProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Sends requests to Kafka as JSON text.
 *
 * <p>MOVE_FUNDS goes to Transaction Service keyed by the payment, so a repeat follows the first one
 * in the same partition. RESOLVE_CARD goes to Card Service keyed by the card, and says where the
 * answer goes because Card Service has more than one requester.
 */
@Component
public class KafkaCommandPublisher implements CommandPublisherPort {

  private final KafkaJsonSender sender;
  private final YankiProperties.Messaging messaging;

  KafkaCommandPublisher(KafkaJsonSender sender, YankiProperties properties) {
    this.sender = sender;
    this.messaging = properties.messaging();
  }

  @Override
  public Mono<Void> publish(OutboundCommand command, String correlationId) {
    if (command instanceof OutboundCommand.MoveFunds movement) {
      return sender.send(
          messaging.transactionCommandsTopic(),
          movement.transactionId(),
          CommandMessage.of(movement),
          correlationId);
    }
    OutboundCommand.ResolveCard resolution = (OutboundCommand.ResolveCard) command;
    return sender.send(
        messaging.cardCommandsTopic(),
        resolution.cardId(),
        CommandMessage.of(resolution, messaging.repliesTopic()),
        correlationId);
  }
}
