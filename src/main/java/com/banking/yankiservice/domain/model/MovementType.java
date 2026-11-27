package com.banking.yankiservice.domain.model;

/** What a movement of a wallet records. */
public enum MovementType {
  TOP_UP,
  PAYMENT_SENT,
  PAYMENT_RECEIVED,
  /** A payment sent from the wallet balance that was rejected afterwards and came back. */
  REFUND
}
