package com.banking.yankiservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AmountsTest {

  @Test
  void aValidAmountComesBackWithTwoDecimals() {
    assertThat(Amounts.positive(new BigDecimal("25.5"))).hasToString("25.50");
    assertThat(Amounts.positive(new BigDecimal("7"))).hasToString("7.00");
    assertThat(Amounts.positive(Amounts.MAX_AMOUNT)).isEqualByComparingTo(Amounts.MAX_AMOUNT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "-1", "10.001", "1000000000000.00"})
  void anAmountMustBePositiveWithTwoDecimalsAndWithinTheMaximum(String amount) {
    assertInvalid(new BigDecimal(amount));
  }

  @Test
  void aMissingAmountIsInvalid() {
    assertInvalid(null);
  }

  private static void assertInvalid(BigDecimal amount) {
    assertThatThrownBy(() -> Amounts.positive(amount))
        .isInstanceOfSatisfying(
            YankiRuleViolationException.class,
            exception -> assertThat(exception.violation()).isEqualTo(RuleViolation.INVALID_AMOUNT));
  }
}
