package com.banking.yankiservice.domain.model;

import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/**
 * A payment from one wallet to another, asked for with their mobile numbers.
 *
 * <p>Where the money is on each side is decided when the payment is requested and never changes:
 * the balance of the wallet, or the main account of the debit card the wallet was linked to at that
 * moment.
 *
 * <ul>
 *   <li>No card on either side: both balances change with the request and the payment is born
 *       {@code COMPLETED}.
 *   <li>A card on any side: the payment is {@code PENDING} until Transaction Service answers the
 *       MOVE_FUNDS request, then {@code COMPLETED} or {@code REJECTED}.
 * </ul>
 *
 * <p>An answer moves the payment only while it is pending; a repeat changes nothing.
 *
 * <p>Instances are immutable; every change returns a new one with the next {@code version}.
 */
@Getter
@Builder(toBuilder = true)
public final class Payment {

  /** What Transaction Service is told when the client gave no description. */
  static final String DEFAULT_DESCRIPTION = "Yanki payment";

  /** The operationId of the request; also the transactionId of MOVE_FUNDS. */
  private final UUID id;

  private final UUID fromWalletId;
  private final UUID toWalletId;
  private final String fromPhoneNumber;
  private final String toPhoneNumber;
  private final BigDecimal amount;
  private final String description;

  /** Card whose main account pays; null when the sender pays from the wallet balance. */
  private final UUID sourceCardId;

  /** Card whose main account receives; null when the wallet balance of the receiver does. */
  private final UUID destinationCardId;

  private final PaymentStatus status;

  /** Movement fee the source account paid, when Transaction Service reported one. */
  private final BigDecimal fee;

  private final Rejection rejection;

  /** Correlation identifier of the request, written in every message of the payment. */
  private final String correlationId;

  /** When MOVE_FUNDS was last sent. */
  private final Instant commandSentAt;

  /** How many times MOVE_FUNDS was sent again. */
  private final int commandAttempts;

  private final Instant createdAt;
  private final Instant updatedAt;
  private final long version;

  /**
   * A new payment between two wallets, with the side of each one decided from its card link.
   *
   * @param id the operationId of the request
   * @param amount already validated with {@link Amounts#positive}
   * @param description free text of the client; blank becomes none
   * @throws YankiRuleViolationException {@code SAME_WALLET} when both wallets are the same
   */
  public static Payment request(
      UUID id,
      Wallet from,
      Wallet to,
      BigDecimal amount,
      String description,
      String correlationId,
      Instant now) {
    if (from.getId().equals(to.getId())) {
      throw sameWallet();
    }
    UUID sourceCard = from.linkedCardId().orElse(null);
    UUID destinationCard = to.linkedCardId().orElse(null);
    boolean local = sourceCard == null && destinationCard == null;
    return builder()
        .id(Objects.requireNonNull(id, "id"))
        .fromWalletId(from.getId())
        .toWalletId(to.getId())
        .fromPhoneNumber(from.getPhoneNumber())
        .toPhoneNumber(to.getPhoneNumber())
        .amount(Objects.requireNonNull(amount, "amount"))
        .description(normalized(description))
        .sourceCardId(sourceCard)
        .destinationCardId(destinationCard)
        .status(local ? PaymentStatus.COMPLETED : PaymentStatus.PENDING)
        .correlationId(Objects.requireNonNull(correlationId, "correlationId"))
        .commandSentAt(Objects.requireNonNull(now, "now"))
        .commandAttempts(0)
        .createdAt(now)
        .updatedAt(now)
        .version(0)
        .build();
  }

  /** The rule a payment from a mobile number to itself breaks. */
  public static YankiRuleViolationException sameWallet() {
    return new YankiRuleViolationException(
        RuleViolation.SAME_WALLET, "A wallet cannot pay itself.");
  }

  /** Whether a request with these values asks for exactly the same thing as this payment. */
  public boolean sameRequestAs(
      String fromPhone, String toPhone, BigDecimal requestedAmount, String requestedDescription) {
    return fromPhoneNumber.equals(fromPhone)
        && toPhoneNumber.equals(toPhone)
        && amount.compareTo(requestedAmount) == 0
        && Objects.equals(description, normalized(requestedDescription));
  }

  /** Where the money of the sender is. */
  public Funding sourceFunding() {
    return sourceCardId == null ? Funding.WALLET : Funding.CARD;
  }

  /** Where the money of the receiver goes. */
  public Funding destinationFunding() {
    return destinationCardId == null ? Funding.WALLET : Funding.CARD;
  }

  /** Whether the payment still waits for Transaction Service. */
  public boolean isPending() {
    return status == PaymentStatus.PENDING;
  }

  /** Whether no bank account takes part, so this service moves all the money itself. */
  public boolean isLocal() {
    return sourceCardId == null && destinationCardId == null;
  }

  /**
   * The request to Transaction Service the payment is waiting on, to send or to send again.
   *
   * @return empty once the payment is final
   */
  public Optional<OutboundCommand.MoveFunds> nextCommand() {
    if (status != PaymentStatus.PENDING) {
      return Optional.empty();
    }
    String sentDescription = description == null ? DEFAULT_DESCRIPTION : description;
    return Optional.of(
        new OutboundCommand.MoveFunds(
            id, sourceCardId, destinationCardId, amount, Wallet.CURRENCY, sentDescription));
  }

  /**
   * Transaction Service moved the money of the bank accounts.
   *
   * @param reportedFee movement fee the source account paid, or null
   * @return the completed payment, or empty when it is not pending
   */
  public Optional<Payment> fundsMoved(BigDecimal reportedFee, Instant now) {
    return status == PaymentStatus.PENDING
        ? Optional.of(changed(now).status(PaymentStatus.COMPLETED).fee(reportedFee).build())
        : Optional.empty();
  }

  /**
   * Transaction Service could not move the money.
   *
   * @return the rejected payment, or empty when it is not pending
   */
  public Optional<Payment> fundsMoveRejected(Rejection reason, Instant now) {
    return status == PaymentStatus.PENDING
        ? Optional.of(changed(now).status(PaymentStatus.REJECTED).rejection(reason).build())
        : Optional.empty();
  }

  /** The same payment after MOVE_FUNDS was sent again. */
  public Payment commandResent(Instant now) {
    return toBuilder()
        .commandSentAt(Objects.requireNonNull(now, "now"))
        .commandAttempts(commandAttempts + 1)
        .version(version + 1)
        .build();
  }

  private PaymentBuilder changed(Instant now) {
    return toBuilder().updatedAt(Objects.requireNonNull(now, "now")).version(version + 1);
  }

  private static String normalized(String description) {
    return description == null || description.isBlank() ? null : description.strip();
  }
}
