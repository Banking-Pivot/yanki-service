package com.banking.yankiservice.domain.model;

import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * How much a wallet may pay: a maximum per payment and a maximum sent per day.
 *
 * @param paymentMax most one payment can move
 * @param dailySentMax most a wallet can send in one day, counting every payment not rejected
 * @param zone time zone whose calendar day is the day of the daily limit
 */
public record PaymentLimits(BigDecimal paymentMax, BigDecimal dailySentMax, ZoneId zone) {

  /** All the values are required. */
  public PaymentLimits {
    Objects.requireNonNull(paymentMax, "paymentMax");
    Objects.requireNonNull(dailySentMax, "dailySentMax");
    Objects.requireNonNull(zone, "zone");
  }

  /**
   * Checks one payment against the maximum per payment.
   *
   * @throws YankiRuleViolationException {@code PAYMENT_LIMIT_EXCEEDED} when the amount is above it
   */
  public void checkPayment(BigDecimal amount) {
    if (amount.compareTo(paymentMax) > 0) {
      throw new YankiRuleViolationException(
          RuleViolation.PAYMENT_LIMIT_EXCEEDED,
          "The amount exceeds the maximum of " + paymentMax.toPlainString() + " per payment.");
    }
  }

  /**
   * Checks a new payment against the maximum a wallet can send in a day.
   *
   * @param sentToday what the wallet already sent today in payments that were not rejected
   * @throws YankiRuleViolationException {@code DAILY_LIMIT_EXCEEDED} when the day would pass it
   */
  public void checkDaily(BigDecimal sentToday, BigDecimal amount) {
    if (sentToday.add(amount).compareTo(dailySentMax) > 0) {
      throw new YankiRuleViolationException(
          RuleViolation.DAILY_LIMIT_EXCEEDED,
          "The wallet would send more than the maximum of "
              + dailySentMax.toPlainString()
              + " per day.");
    }
  }

  /** The first instant of the day the given instant falls in. */
  public Instant dayStart(Instant now) {
    return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
  }

  /** The first instant of the day after the one the given instant falls in. */
  public Instant dayEnd(Instant now) {
    return now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
  }
}
