package com.banking.yankiservice.domain.model.vo;

import static com.banking.yankiservice.support.Fixtures.LIMITS;
import static com.banking.yankiservice.support.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class PaymentLimitsTest {

  @Test
  void aPaymentMayReachTheMaximumButNotPassIt() {
    assertThatCode(() -> LIMITS.checkPayment(money("500.00"))).doesNotThrowAnyException();
    assertViolation(
        () -> LIMITS.checkPayment(money("500.01")), RuleViolation.PAYMENT_LIMIT_EXCEEDED);
  }

  @Test
  void theDayMayReachTheMaximumButNotPassIt() {
    assertThatCode(() -> LIMITS.checkDaily(money("1500.00"), money("500.00")))
        .doesNotThrowAnyException();
    assertThatCode(() -> LIMITS.checkDaily(money("0"), money("0.01"))).doesNotThrowAnyException();
    assertViolation(
        () -> LIMITS.checkDaily(money("1999.99"), money("0.02")),
        RuleViolation.DAILY_LIMIT_EXCEEDED);
  }

  @Test
  void theDayIsTheCalendarDayOfTheBusinessZone() {
    // 15:00 UTC is 10:00 in Lima; 03:00 UTC still belongs to the day before there.
    Instant morning = Instant.parse("2026-11-20T15:00:00Z");
    Instant lateNight = Instant.parse("2026-11-20T03:00:00Z");

    assertThat(LIMITS.dayStart(morning)).isEqualTo(Instant.parse("2026-11-20T05:00:00Z"));
    assertThat(LIMITS.dayEnd(morning)).isEqualTo(Instant.parse("2026-11-21T05:00:00Z"));
    assertThat(LIMITS.dayStart(lateNight)).isEqualTo(Instant.parse("2026-11-19T05:00:00Z"));
    assertThat(LIMITS.dayEnd(lateNight)).isEqualTo(Instant.parse("2026-11-20T05:00:00Z"));
  }

  @Test
  void everyValueIsRequired() {
    assertThatThrownBy(() -> new PaymentLimits(null, money("1"), LIMITS.zone()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PaymentLimits(money("1"), null, LIMITS.zone()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PaymentLimits(money("1"), money("1"), null))
        .isInstanceOf(NullPointerException.class);
  }

  private static void assertViolation(ThrowingCallable action, RuleViolation violation) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            YankiRuleViolationException.class,
            exception -> assertThat(exception.violation()).isEqualTo(violation));
  }
}
