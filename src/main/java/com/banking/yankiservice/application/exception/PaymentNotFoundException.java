package com.banking.yankiservice.application.exception;

import java.util.UUID;

/** No payment has that ID. */
public class PaymentNotFoundException extends RuntimeException {

  public PaymentNotFoundException(UUID paymentId) {
    super("Payment " + paymentId + " does not exist.");
  }
}
