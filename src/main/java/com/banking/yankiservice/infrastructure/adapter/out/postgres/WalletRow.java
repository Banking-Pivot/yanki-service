package com.banking.yankiservice.infrastructure.adapter.out.postgres;

import com.banking.yankiservice.domain.model.CardLink;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.enums.CardLinkStatus;
import com.banking.yankiservice.domain.model.enums.DocumentType;
import com.banking.yankiservice.domain.model.vo.Rejection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row of {@code wallets}. Enums are stored by name. {@code version} is managed by the domain and
 * checked by the adapter on every update. The card columns are all empty or describe one link
 * request; its status is text here because the column is emptied when the wallet is unlinked.
 */
@Table("wallets")
public record WalletRow(
    @Id UUID id,
    DocumentType documentType,
    String documentNumber,
    String phoneNumber,
    String imei,
    String email,
    BigDecimal balance,
    String currency,
    UUID cardId,
    UUID cardLinkId,
    String cardLinkStatus,
    String cardRejectionCode,
    String cardRejectionMessage,
    String cardCorrelationId,
    Instant cardCommandSentAt,
    int cardCommandAttempts,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  static WalletRow of(Wallet wallet) {
    CardLink link = wallet.getCardLink();
    Rejection rejection = link == null ? null : link.rejection();
    return new WalletRow(
        wallet.getId(),
        wallet.getDocumentType(),
        wallet.getDocumentNumber(),
        wallet.getPhoneNumber(),
        wallet.getImei(),
        wallet.getEmail(),
        wallet.getBalance(),
        Wallet.CURRENCY,
        link == null ? null : link.cardId(),
        link == null ? null : link.linkId(),
        link == null ? null : link.status().name(),
        rejection == null ? null : rejection.code(),
        rejection == null ? null : rejection.message(),
        link == null ? null : link.correlationId(),
        link == null ? null : link.commandSentAt(),
        link == null ? 0 : link.commandAttempts(),
        wallet.getCreatedAt(),
        wallet.getUpdatedAt(),
        wallet.getVersion());
  }

  Wallet toWallet() {
    return Wallet.builder()
        .id(id)
        .documentType(documentType)
        .documentNumber(documentNumber)
        .phoneNumber(phoneNumber)
        .imei(imei)
        .email(email)
        .balance(balance)
        .cardLink(cardLink())
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .version(version)
        .build();
  }

  private CardLink cardLink() {
    if (cardLinkId == null) {
      return null;
    }
    return new CardLink(
        cardLinkId,
        cardId,
        CardLinkStatus.valueOf(cardLinkStatus),
        cardRejectionCode == null ? null : new Rejection(cardRejectionCode, cardRejectionMessage),
        cardCorrelationId,
        cardCommandSentAt,
        cardCommandAttempts);
  }
}
