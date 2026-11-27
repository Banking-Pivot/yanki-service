package com.banking.yankiservice.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One thing that happened to a wallet: a top-up, a payment sent or received, or a refund.
 *
 * @param fundedBy WALLET when the movement changed the balance of the wallet, CARD when the money
 *     moved in the main account of its debit card
 * @param balanceAfter balance of the wallet after the movement; one funded by CARD leaves it as it
 *     was
 * @param operationId the operationId of the top-up, or the ID of the payment
 * @param counterpartyPhoneNumber the other mobile number of a payment; null for a top-up
 */
public record WalletMovement(
    UUID id,
    UUID walletId,
    MovementType type,
    Funding fundedBy,
    BigDecimal amount,
    BigDecimal balanceAfter,
    UUID operationId,
    String counterpartyPhoneNumber,
    String description,
    Instant occurredAt) {

  /** The top-up that left the wallet as given. */
  public static WalletMovement topUp(
      UUID id, Wallet after, BigDecimal amount, UUID operationId, Instant now) {
    return new WalletMovement(
        id,
        after.getId(),
        MovementType.TOP_UP,
        Funding.WALLET,
        amount,
        after.getBalance(),
        operationId,
        null,
        null,
        now);
  }

  /** The payment as the sender sees it; {@code sender} is the wallet after the movement. */
  public static WalletMovement sent(UUID id, Wallet sender, Payment payment, Instant now) {
    return of(
        id,
        sender,
        MovementType.PAYMENT_SENT,
        payment.sourceFunding(),
        payment,
        payment.getToPhoneNumber(),
        now);
  }

  /** The payment as the receiver sees it; {@code receiver} is the wallet after the movement. */
  public static WalletMovement received(UUID id, Wallet receiver, Payment payment, Instant now) {
    return of(
        id,
        receiver,
        MovementType.PAYMENT_RECEIVED,
        payment.destinationFunding(),
        payment,
        payment.getFromPhoneNumber(),
        now);
  }

  /** The amount of a rejected payment back in the balance of the sender. */
  public static WalletMovement refund(UUID id, Wallet sender, Payment payment, Instant now) {
    return of(
        id, sender, MovementType.REFUND, Funding.WALLET, payment, payment.getToPhoneNumber(), now);
  }

  private static WalletMovement of(
      UUID id,
      Wallet wallet,
      MovementType type,
      Funding fundedBy,
      Payment payment,
      String counterparty,
      Instant now) {
    return new WalletMovement(
        id,
        wallet.getId(),
        type,
        fundedBy,
        payment.getAmount(),
        wallet.getBalance(),
        payment.getId(),
        counterparty,
        payment.getDescription(),
        now);
  }
}
