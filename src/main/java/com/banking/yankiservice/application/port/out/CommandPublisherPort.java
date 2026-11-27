package com.banking.yankiservice.application.port.out;

import com.banking.yankiservice.domain.model.OutboundCommand;
import reactor.core.publisher.Mono;

/** Sends requests to Card Service and Transaction Service. */
public interface CommandPublisherPort {

  /**
   * Sends one request.
   *
   * @param correlationId correlation identifier that travels with the message
   * @return completion once the broker has the message, or the error that kept it from getting
   *     there
   */
  Mono<Void> publish(OutboundCommand command, String correlationId);
}
