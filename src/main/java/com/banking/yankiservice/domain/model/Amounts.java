package com.banking.yankiservice.domain.model;

import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money rules of a request: two decimals, never rounded silently, and within what the bank accepts
 * for one operation.
 */
public final class Amounts {

  /** Decimal places of every amount. */
  public static final int SCALE = 2;

  /** Largest amount a single request can move, and largest balance a wallet can hold. */
  public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

  private Amounts() {}

  /** Validates a strictly positive request amount and returns it with scale 2. */
  public static BigDecimal positive(BigDecimal amount) {
    if (amount == null) {
      throw invalid("The amount is required.");
    }
    if (amount.signum() <= 0) {
      throw invalid("The amount must be greater than zero.");
    }
    BigDecimal normalized;
    try {
      normalized = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException exception) {
      throw invalid("The amount can have at most two decimals.");
    }
    if (normalized.compareTo(MAX_AMOUNT) > 0) {
      throw invalid("The amount exceeds the maximum per operation.");
    }
    return normalized;
  }

  private static YankiRuleViolationException invalid(String message) {
    return new YankiRuleViolationException(RuleViolation.INVALID_AMOUNT, message);
  }
}
