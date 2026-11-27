package com.banking.yankiservice.domain.exception;

import java.util.Objects;

/** A request that breaks a business rule of Yanki Service. */
public class YankiRuleViolationException extends RuntimeException {

  private final RuleViolation violation;

  public YankiRuleViolationException(RuleViolation violation, String message) {
    super(message);
    this.violation = Objects.requireNonNull(violation, "violation");
  }

  public RuleViolation violation() {
    return violation;
  }
}
