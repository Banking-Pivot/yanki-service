package com.banking.yankiservice.application.command;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sends a payment from one mobile number to another.
 *
 * @param operationId idempotency key of the client; it becomes the ID of the payment
 * @param correlationId correlation identifier of the request, sent with every message of the
 *     payment
 */
public record RequestPaymentCommand(
    UUID operationId,
    String fromPhoneNumber,
    String toPhoneNumber,
    BigDecimal amount,
    String description,
    String correlationId) {}
