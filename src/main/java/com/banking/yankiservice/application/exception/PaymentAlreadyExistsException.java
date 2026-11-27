package com.banking.yankiservice.application.exception;

import java.util.UUID;

/** A payment with that ID was stored first, by a request that arrived at the same time. */
public class PaymentAlreadyExistsException extends RuntimeException {

  public PaymentAlreadyExistsException(UUID paymentId, Throwable cause) {
    super("Payment " + paymentId + " already exists.", cause);
  }
}
