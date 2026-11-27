package com.banking.yankiservice.application.handler.query;

import com.banking.yankiservice.application.exception.PaymentNotFoundException;
import com.banking.yankiservice.application.port.in.GetPaymentUseCase;
import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.domain.model.Payment;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Reads one payment, or fails when it does not exist. */
public class GetPaymentHandler implements GetPaymentUseCase {

  private final PaymentStorePort payments;

  public GetPaymentHandler(PaymentStorePort payments) {
    this.payments = Objects.requireNonNull(payments);
  }

  @Override
  public Mono<Payment> execute(UUID paymentId) {
    return payments
        .findById(paymentId)
        .switchIfEmpty(Mono.error(() -> new PaymentNotFoundException(paymentId)));
  }
}
