package com.banking.yankiservice.application.port.in;

import reactor.core.publisher.Mono;

/** Sends again the requests that have waited too long for an answer. */
public interface ResendPendingCommandsUseCase {

  /**
   * Runs one pass.
   *
   * @return how many requests were sent again
   */
  Mono<Long> execute();
}
