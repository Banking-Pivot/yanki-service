package com.banking.yankiservice.domain.model;

/** How a payment is going. */
public enum PaymentStatus {
  /** Waiting for Transaction Service to move the money of a bank account. */
  PENDING,
  COMPLETED,
  REJECTED
}
