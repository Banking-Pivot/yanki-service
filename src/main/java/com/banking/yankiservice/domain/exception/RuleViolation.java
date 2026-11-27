package com.banking.yankiservice.domain.exception;

/** Business rules a well-formed request can break. The name is the stable API error code. */
public enum RuleViolation {
  INVALID_AMOUNT,
  INVALID_DOCUMENT_NUMBER,
  INVALID_PHONE_NUMBER,
  INVALID_IMEI,
  INVALID_EMAIL,
  SAME_WALLET,
  INSUFFICIENT_FUNDS,
  WALLET_LINKED_TO_CARD,
  WALLET_ALREADY_LINKED,
  BALANCE_LIMIT_EXCEEDED,
  CARD_ALREADY_LINKED,
  PAYMENT_LIMIT_EXCEEDED,
  DAILY_LIMIT_EXCEEDED
}
