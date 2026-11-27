package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.domain.model.Payment;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Reads one payment. */
public interface GetPaymentUseCase {

  Mono<Payment> execute(UUID paymentId);
}
