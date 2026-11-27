package com.banking.yankiservice.application.command;

import java.util.UUID;

/**
 * Asks for a wallet to be linked to a debit card.
 *
 * @param correlationId correlation identifier of the request, sent with the question to Card
 *     Service
 */
public record LinkCardCommand(UUID walletId, UUID cardId, String correlationId) {}
