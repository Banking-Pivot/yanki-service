package com.banking.yankiservice.application.command;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Loads money into the balance of a wallet.
 *
 * @param operationId idempotency key of the client
 */
public record TopUpWalletCommand(UUID walletId, UUID operationId, BigDecimal amount) {}
