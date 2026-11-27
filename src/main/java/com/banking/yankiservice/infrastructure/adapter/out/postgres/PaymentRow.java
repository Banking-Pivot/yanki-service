package com.banking.yankiservice.infrastructure.adapter.out.postgres;

import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.enums.PaymentStatus;
import com.banking.yankiservice.domain.model.vo.Rejection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row of {@code payments}. Enums are stored by name. {@code version} is managed by the domain and
 * checked by the adapter on every update.
 */
@Table("payments")
public record PaymentRow(
    @Id UUID id,
    UUID fromWalletId,
    UUID toWalletId,
    String fromPhoneNumber,
    String toPhoneNumber,
    BigDecimal amount,
    String currency,
    String description,
    UUID sourceCardId,
    UUID destinationCardId,
    PaymentStatus status,
    BigDecimal fee,
    String rejectionCode,
    String rejectionMessage,
    String correlationId,
    Instant commandSentAt,
    int commandAttempts,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  static PaymentRow of(Payment payment) {
    Rejection rejection = payment.getRejection();
    return new PaymentRow(
        payment.getId(),
        payment.getFromWalletId(),
        payment.getToWalletId(),
        payment.getFromPhoneNumber(),
        payment.getToPhoneNumber(),
        payment.getAmount(),
        Wallet.CURRENCY,
        payment.getDescription(),
        payment.getSourceCardId(),
        payment.getDestinationCardId(),
        payment.getStatus(),
        payment.getFee(),
        rejection == null ? null : rejection.code(),
        rejection == null ? null : rejection.message(),
        payment.getCorrelationId(),
        payment.getCommandSentAt(),
        payment.getCommandAttempts(),
        payment.getCreatedAt(),
        payment.getUpdatedAt(),
        payment.getVersion());
  }

  Payment toPayment() {
    return Payment.builder()
        .id(id)
        .fromWalletId(fromWalletId)
        .toWalletId(toWalletId)
        .fromPhoneNumber(fromPhoneNumber)
        .toPhoneNumber(toPhoneNumber)
        .amount(amount)
        .description(description)
        .sourceCardId(sourceCardId)
        .destinationCardId(destinationCardId)
        .status(status)
        .fee(fee)
        .rejection(rejectionCode == null ? null : new Rejection(rejectionCode, rejectionMessage))
        .correlationId(correlationId)
        .commandSentAt(commandSentAt)
        .commandAttempts(commandAttempts)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .version(version)
        .build();
  }
}
