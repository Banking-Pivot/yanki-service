package com.banking.yankiservice.infrastructure.adapter.out.postgres;

import com.banking.yankiservice.domain.model.WalletMovement;
import com.banking.yankiservice.domain.model.enums.Funding;
import com.banking.yankiservice.domain.model.enums.MovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Row of {@code wallet_movements}. Enums are stored by name. A movement never changes. */
@Table("wallet_movements")
public record MovementRow(
    @Id UUID id,
    UUID walletId,
    MovementType movementType,
    Funding fundedBy,
    BigDecimal amount,
    BigDecimal balanceAfter,
    UUID operationId,
    String counterpartyPhoneNumber,
    String description,
    Instant occurredAt) {

  static MovementRow of(WalletMovement movement) {
    return new MovementRow(
        movement.id(),
        movement.walletId(),
        movement.type(),
        movement.fundedBy(),
        movement.amount(),
        movement.balanceAfter(),
        movement.operationId(),
        movement.counterpartyPhoneNumber(),
        movement.description(),
        movement.occurredAt());
  }

  WalletMovement toMovement() {
    return new WalletMovement(
        id,
        walletId,
        movementType,
        fundedBy,
        amount,
        balanceAfter,
        operationId,
        counterpartyPhoneNumber,
        description,
        occurredAt);
  }
}
