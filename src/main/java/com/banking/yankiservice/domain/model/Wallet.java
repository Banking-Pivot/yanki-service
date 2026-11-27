package com.banking.yankiservice.domain.model;

import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Getter;

/**
 * A mobile wallet: who owns it, its own balance in PEN and the debit card it may be linked to.
 *
 * <p>The balance is the money this service keeps for the wallet. It only changes through {@link
 * #topUp}, {@link #debit} and {@link #credit}, and it is never negative. While the wallet is linked
 * to a debit card its payments move the main account of the card instead; the balance stays as it
 * was until the wallet is unlinked.
 *
 * <p>The link to a card goes {@code PENDING} to {@code LINKED} or {@code REJECTED} with the answer
 * of Card Service, and an answer only counts for the request it was asked for. A card is linked
 * only when its holder has the identity document of the wallet.
 *
 * <p>Instances are immutable; every change returns a new one with the next {@code version}.
 */
@Getter
@Builder(toBuilder = true)
public final class Wallet {

  /** Currency of every wallet. */
  public static final String CURRENCY = "PEN";

  /** Why a link is rejected when the card belongs to somebody who is not the wallet's owner. */
  public static final String CARD_NOT_OWNED = "CARD_NOT_OWNED";

  private static final Pattern PHONE_NUMBER = Pattern.compile("9\\d{8}");
  private static final Pattern IMEI_DIGITS = Pattern.compile("\\d{15}");
  private static final int MAX_EMAIL = 254;

  private final UUID id;
  private final DocumentType documentType;
  private final String documentNumber;
  private final String phoneNumber;
  private final String imei;
  private final String email;
  private final BigDecimal balance;

  /** The card the wallet asked to be linked to; null when it has none. */
  private final CardLink cardLink;

  private final Instant createdAt;
  private final Instant updatedAt;
  private final long version;

  /**
   * A new wallet with balance zero and no card.
   *
   * @param documentNumber number of the identity document; letters are stored in upper case
   * @param phoneNumber Peruvian mobile number: nine digits starting with 9
   * @param imei IMEI of the phone: 15 digits
   * @param email stored in lower case
   * @throws YankiRuleViolationException when a value does not have the shape it must have
   */
  public static Wallet register(
      UUID id,
      DocumentType documentType,
      String documentNumber,
      String phoneNumber,
      String imei,
      String email,
      Instant now) {
    return builder()
        .id(Objects.requireNonNull(id, "id"))
        .documentType(Objects.requireNonNull(documentType, "documentType"))
        .documentNumber(documentType.normalize(documentNumber))
        .phoneNumber(shaped(phoneNumber, PHONE_NUMBER, RuleViolation.INVALID_PHONE_NUMBER))
        .imei(shaped(imei, IMEI_DIGITS, RuleViolation.INVALID_IMEI))
        .email(normalizedEmail(email))
        .balance(BigDecimal.ZERO.setScale(Amounts.SCALE))
        .createdAt(Objects.requireNonNull(now, "now"))
        .updatedAt(now)
        .version(0)
        .build();
  }

  /**
   * Loads money into the balance.
   *
   * @throws YankiRuleViolationException {@code WALLET_LINKED_TO_CARD} while the wallet is linked,
   *     {@code BALANCE_LIMIT_EXCEEDED} when the balance would pass the maximum
   */
  public Wallet topUp(BigDecimal amount, Instant now) {
    if (isLinkedToCard()) {
      throw new YankiRuleViolationException(
          RuleViolation.WALLET_LINKED_TO_CARD,
          "The wallet is linked to a debit card: its money is in the bank account.");
    }
    if (balance.add(amount).compareTo(Amounts.MAX_AMOUNT) > 0) {
      throw new YankiRuleViolationException(
          RuleViolation.BALANCE_LIMIT_EXCEEDED, "The balance would exceed the maximum.");
    }
    return credit(amount, now);
  }

  /** Adds a payment received, or one that came back, to the balance. */
  public Wallet credit(BigDecimal amount, Instant now) {
    return changed(now).balance(balance.add(amount)).build();
  }

  /**
   * Takes a payment sent out of the balance.
   *
   * @throws YankiRuleViolationException {@code INSUFFICIENT_FUNDS} when the balance is not enough
   */
  public Wallet debit(BigDecimal amount, Instant now) {
    if (balance.compareTo(amount) < 0) {
      throw new YankiRuleViolationException(
          RuleViolation.INSUFFICIENT_FUNDS, "The wallet balance does not cover the amount.");
    }
    return changed(now).balance(balance.subtract(amount)).build();
  }

  /**
   * Asks for the wallet to be linked to a debit card.
   *
   * @param linkId ID of this request, used only when a new request is made
   * @return the wallet waiting for Card Service, or this same wallet when it already asked for that
   *     card and the request is pending or linked
   * @throws YankiRuleViolationException {@code WALLET_ALREADY_LINKED} when it is linked to another
   *     card
   */
  public Wallet requestCardLink(UUID linkId, UUID cardId, String correlationId, Instant now) {
    Objects.requireNonNull(cardId, "cardId");
    if (cardLink != null && cardLink.status() != CardLinkStatus.REJECTED) {
      if (cardLink.cardId().equals(cardId)) {
        return this;
      }
      if (cardLink.isLinked()) {
        throw new YankiRuleViolationException(
            RuleViolation.WALLET_ALREADY_LINKED,
            "The wallet is linked to another debit card; unlink it first.");
      }
    }
    return changed(now)
        .cardLink(
            CardLink.requested(
                Objects.requireNonNull(linkId, "linkId"),
                cardId,
                Objects.requireNonNull(correlationId, "correlationId"),
                now))
        .build();
  }

  /**
   * Card Service found the card and said who holds it.
   *
   * @param holderDocumentType type of the identity document of the card holder, or null when Card
   *     Service did not report it
   * @param holderDocumentNumber number of that document, or null
   * @return the wallet linked when the holder has the document of the wallet, the wallet with the
   *     request rejected as {@code CARD_NOT_OWNED} when it does not or the holder is unknown, or
   *     empty when the wallet is not waiting for that answer
   */
  public Optional<Wallet> cardResolved(
      UUID linkId, String holderDocumentType, String holderDocumentNumber, Instant now) {
    if (!waitsFor(linkId)) {
      return Optional.empty();
    }
    CardLink answered =
        isOwner(holderDocumentType, holderDocumentNumber)
            ? cardLink.linked()
            : cardLink.rejected(
                new Rejection(
                    CARD_NOT_OWNED, "The debit card does not belong to the owner of the wallet."));
    return Optional.of(changed(now).cardLink(answered).build());
  }

  /**
   * Card Service did not accept the card.
   *
   * @return the wallet with the rejected request, or empty when it is not waiting for that answer
   */
  public Optional<Wallet> cardRejected(UUID linkId, Rejection reason, Instant now) {
    return waitsFor(linkId)
        ? Optional.of(changed(now).cardLink(cardLink.rejected(reason)).build())
        : Optional.empty();
  }

  /** The wallet without a card, or this same wallet when it has none. */
  public Wallet unlinkCard(Instant now) {
    return cardLink == null ? this : changed(now).cardLink(null).build();
  }

  /**
   * The same wallet after it requested a payment that its debit card pays. Only the version moves:
   * that makes two payments of one wallet decide one after the other, like the ones that change the
   * balance, so the daily limit is checked against everything the wallet already sent.
   */
  public Wallet paymentRequested() {
    return toBuilder().version(version + 1).build();
  }

  /** The rule a link to a card that another wallet holds or waits for breaks. */
  public static YankiRuleViolationException cardAlreadyLinked() {
    return new YankiRuleViolationException(
        RuleViolation.CARD_ALREADY_LINKED, "The debit card is linked to another wallet.");
  }

  /** The same wallet after RESOLVE_CARD was sent again. */
  public Wallet cardCommandResent(Instant now) {
    return toBuilder().cardLink(cardLink.resent(now)).version(version + 1).build();
  }

  /** The request to Card Service the wallet is waiting on, to send or to send again. */
  public Optional<OutboundCommand.ResolveCard> nextCommand() {
    return cardLink != null && cardLink.isPending()
        ? Optional.of(new OutboundCommand.ResolveCard(cardLink.linkId(), cardLink.cardId()))
        : Optional.empty();
  }

  /** Whether the wallet pays and receives through a debit card. */
  public boolean isLinkedToCard() {
    return cardLink != null && cardLink.isLinked();
  }

  /** The card the wallet pays and receives through, when it is linked. */
  public Optional<UUID> linkedCardId() {
    return isLinkedToCard() ? Optional.of(cardLink.cardId()) : Optional.empty();
  }

  private boolean isOwner(String holderDocumentType, String holderDocumentNumber) {
    return holderDocumentType != null
        && holderDocumentNumber != null
        && documentType.name().equalsIgnoreCase(holderDocumentType.strip())
        && documentNumber.equals(holderDocumentNumber.strip().toUpperCase(Locale.ROOT));
  }

  private boolean waitsFor(UUID linkId) {
    return cardLink != null && cardLink.isPending() && cardLink.linkId().equals(linkId);
  }

  private WalletBuilder changed(Instant now) {
    return toBuilder().updatedAt(Objects.requireNonNull(now, "now")).version(version + 1);
  }

  private static String shaped(String value, Pattern shape, RuleViolation violation) {
    String stripped = value == null ? "" : value.strip();
    if (!shape.matcher(stripped).matches()) {
      throw new YankiRuleViolationException(violation, "The value does not have a valid format.");
    }
    return stripped;
  }

  private static String normalizedEmail(String email) {
    String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    int at = normalized.indexOf('@');
    int dot = normalized.lastIndexOf('.');
    boolean valid =
        normalized.length() <= MAX_EMAIL
            && at > 0
            && at == normalized.lastIndexOf('@')
            && dot > at + 1
            && dot < normalized.length() - 1
            && normalized.chars().noneMatch(Character::isWhitespace);
    if (!valid) {
      throw new YankiRuleViolationException(
          RuleViolation.INVALID_EMAIL, "The email does not have a valid format.");
    }
    return normalized;
  }
}
