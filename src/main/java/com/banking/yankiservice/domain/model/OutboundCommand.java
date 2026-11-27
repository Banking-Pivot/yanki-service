package com.banking.yankiservice.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/** What Yanki Service asks of the services that own cards and bank accounts. */
public sealed interface OutboundCommand {

  /** ID the answer carries back. */
  UUID transactionId();

  /**
   * Asks Transaction Service to move money of the main account of one or two debit cards.
   *
   * @param transactionId the ID of the payment
   * @param sourceCardId card whose main account pays; empty when the sender paid from the wallet
   * @param destinationCardId card whose main account receives; empty when the balance of the
   *     receiving wallet is credited here
   */
  record MoveFunds(
      UUID transactionId,
      UUID sourceCardId,
      UUID destinationCardId,
      BigDecimal amount,
      String currency,
      String description)
      implements OutboundCommand {}

  /**
   * Asks Card Service whether a debit card exists and is active.
   *
   * @param transactionId the ID of the link request
   */
  record ResolveCard(UUID transactionId, UUID cardId) implements OutboundCommand {}
}
