package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.application.command.RequestPaymentCommand;
import com.banking.yankiservice.application.model.RequestedPayment;
import reactor.core.publisher.Mono;

/** Sends a payment between two mobile numbers, once per operationId. */
public interface RequestPaymentUseCase {

  Mono<RequestedPayment> execute(RequestPaymentCommand command);
}
