package com.banking.yankiservice.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The request of a wallet to be linked to a debit card, and how it went.
 *
 * @param linkId ID of the request: the transactionId of the RESOLVE_CARD command and its answer
 * @param cardId the debit card, as Card Service knows it
 * @param rejection why Card Service did not accept the card; only when REJECTED
 * @param correlationId correlation identifier of the request that asked for the link
 * @param commandSentAt when RESOLVE_CARD was last sent
 * @param commandAttempts how many times RESOLVE_CARD was sent again
 */
public record CardLink(
    UUID linkId,
    UUID cardId,
    CardLinkStatus status,
    Rejection rejection,
    String correlationId,
    Instant commandSentAt,
    int commandAttempts) {

  static CardLink requested(UUID linkId, UUID cardId, String correlationId, Instant now) {
    return new CardLink(linkId, cardId, CardLinkStatus.PENDING, null, correlationId, now, 0);
  }

  CardLink linked() {
    return new CardLink(
        linkId, cardId, CardLinkStatus.LINKED, null, correlationId, commandSentAt, commandAttempts);
  }

  CardLink rejected(Rejection reason) {
    return new CardLink(
        linkId,
        cardId,
        CardLinkStatus.REJECTED,
        reason,
        correlationId,
        commandSentAt,
        commandAttempts);
  }

  CardLink resent(Instant now) {
    return new CardLink(linkId, cardId, status, rejection, correlationId, now, commandAttempts + 1);
  }

  /** Whether Card Service has not answered yet. */
  public boolean isPending() {
    return status == CardLinkStatus.PENDING;
  }

  /** Whether the wallet pays and receives through the card. */
  public boolean isLinked() {
    return status == CardLinkStatus.LINKED;
  }
}
