package com.banking.yankiservice.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of Yanki Service, served by the Config Server under {@code yanki}.
 *
 * @param messaging topics and times of the conversation with the other services
 * @param limits how much a wallet may pay
 * @param businessZone time zone whose calendar day is the day of the daily limit
 */
@Validated
@ConfigurationProperties("yanki")
public record YankiProperties(
    @NotNull @Valid @DefaultValue Messaging messaging,
    @NotNull @Valid @DefaultValue Limits limits,
    @DefaultValue("America/Lima") @NotNull ZoneId businessZone) {

  /**
   * How much a wallet may pay.
   *
   * @param paymentMax most one payment can move
   * @param dailySentMax most a wallet can send in one day of the business zone, counting every
   *     payment that was not rejected
   */
  public record Limits(
      @DefaultValue("500.00") @NotNull @Positive BigDecimal paymentMax,
      @DefaultValue("2000.00") @NotNull @Positive BigDecimal dailySentMax) {}

  /**
   * How requests go out and answers come in.
   *
   * @param transactionCommandsTopic topic Transaction Service reads MOVE_FUNDS from
   * @param cardCommandsTopic topic Card Service reads RESOLVE_CARD from
   * @param repliesTopic topic both services answer on; it travels in RESOLVE_CARD as replyTo
   * @param publishTimeout longest a send may take before it counts as failed
   * @param retryInterval wait between the three retries of an answer that could not be stored
   * @param resendInterval how often the payments and card links that still wait are looked at
   * @param resendAfter how long a request waits for its answer before it is sent again
   * @param resendBatchSize most payments, and most card links, sent again in one look
   */
  public record Messaging(
      @DefaultValue("banking.transaction.commands") @NotBlank String transactionCommandsTopic,
      @DefaultValue("banking.card.commands") @NotBlank String cardCommandsTopic,
      @DefaultValue("banking.yanki.replies") @NotBlank String repliesTopic,
      @DefaultValue("3s") @NotNull Duration publishTimeout,
      @DefaultValue("1s") @NotNull Duration retryInterval,
      @DefaultValue("10s") @NotNull Duration resendInterval,
      @DefaultValue("30s") @NotNull Duration resendAfter,
      @DefaultValue("50") @Min(1) int resendBatchSize) {}
}
