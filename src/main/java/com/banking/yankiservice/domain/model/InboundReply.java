package com.banking.yankiservice.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** What Card Service and Transaction Service answer to the requests of Yanki Service. */
public sealed interface InboundReply {

  /** Name of the ID every answer carries, for the message of a missing value. */
  String TRANSACTION_ID = "transactionId";

  /** ID of the link request or of the payment the answer is for. */
  UUID transactionId();

  /**
   * Card Service: the card exists and is active.
   *
   * @param holderDocumentType type of the identity document of the card holder, or null when the
   *     card was issued before Card Service kept it
   * @param holderDocumentNumber number of that document, or null
   */
  record CardResolved(UUID transactionId, String holderDocumentType, String holderDocumentNumber)
      implements InboundReply {
    public CardResolved {
      Objects.requireNonNull(transactionId, TRANSACTION_ID);
    }
  }

  /** Card Service: the card does not exist or cannot be used. */
  record CardResolutionRejected(UUID transactionId, Rejection rejection) implements InboundReply {
    /** Both values are required. */
    public CardResolutionRejected {
      Objects.requireNonNull(transactionId, TRANSACTION_ID);
      Objects.requireNonNull(rejection, "rejection");
    }
  }

  /**
   * Transaction Service: the money of the bank accounts moved.
   *
   * @param fee movement fee the source account paid, or null when it reported none
   */
  record FundsMoved(UUID transactionId, BigDecimal fee) implements InboundReply {
    public FundsMoved {
      Objects.requireNonNull(transactionId, TRANSACTION_ID);
    }
  }

  /** Transaction Service: no money of a bank account moved, or what moved was returned. */
  record FundsMoveRejected(UUID transactionId, Rejection rejection) implements InboundReply {
    /** Both values are required. */
    public FundsMoveRejected {
      Objects.requireNonNull(transactionId, TRANSACTION_ID);
      Objects.requireNonNull(rejection, "rejection");
    }
  }
}
