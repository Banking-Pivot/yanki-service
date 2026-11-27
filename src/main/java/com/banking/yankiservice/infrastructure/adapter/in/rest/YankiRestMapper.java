package com.banking.yankiservice.infrastructure.adapter.in.rest;

import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.WalletMovement;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.CardLink;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.CardLinkStatus;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.CurrencyCode;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.DocumentType;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.Funding;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementPageResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementType;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentStatus;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.Rejection;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletPageResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/** Turns wallets, movements and payments into the responses of the contract. */
@Component
public class YankiRestMapper {

  /** The wallet as the contract shows it; the card is left out when it has none. */
  public WalletResponse toResponse(Wallet wallet) {
    WalletResponse response =
        new WalletResponse()
            .id(wallet.getId())
            .status(WalletStatus.fromValue(wallet.getStatus().name()))
            .documentType(DocumentType.fromValue(wallet.getDocumentType().name()))
            .documentNumber(wallet.getDocumentNumber())
            .phoneNumber(wallet.getPhoneNumber())
            .imei(wallet.getImei())
            .email(wallet.getEmail())
            .balance(wallet.getBalance())
            .currency(CurrencyCode.fromValue(Wallet.CURRENCY))
            .createdAt(utc(wallet.getCreatedAt()))
            .updatedAt(utc(wallet.getUpdatedAt()));
    if (wallet.getCardLink() != null) {
      response.card(
          new CardLink()
              .cardId(wallet.getCardLink().cardId())
              .status(CardLinkStatus.fromValue(wallet.getCardLink().status().name()))
              .rejection(rejection(wallet.getCardLink().rejection())));
    }
    return response;
  }

  /** The movement as the contract shows it. */
  public MovementResponse toResponse(WalletMovement movement) {
    return new MovementResponse()
        .id(movement.id())
        .walletId(movement.walletId())
        .type(MovementType.fromValue(movement.type().name()))
        .fundedBy(Funding.fromValue(movement.fundedBy().name()))
        .amount(movement.amount())
        .balanceAfter(movement.balanceAfter())
        .operationId(movement.operationId())
        .counterpartyPhoneNumber(movement.counterpartyPhoneNumber())
        .description(movement.description())
        .occurredAt(utc(movement.occurredAt()));
  }

  /** The payment as the contract shows it; fields that do not apply are left out. */
  public PaymentResponse toResponse(Payment payment) {
    return new PaymentResponse()
        .id(payment.getId())
        .status(PaymentStatus.fromValue(payment.getStatus().name()))
        .fromWalletId(payment.getFromWalletId())
        .toWalletId(payment.getToWalletId())
        .fromPhoneNumber(payment.getFromPhoneNumber())
        .toPhoneNumber(payment.getToPhoneNumber())
        .amount(payment.getAmount())
        .currency(CurrencyCode.fromValue(Wallet.CURRENCY))
        .description(payment.getDescription())
        .sourceFunding(Funding.fromValue(payment.sourceFunding().name()))
        .destinationFunding(Funding.fromValue(payment.destinationFunding().name()))
        .sourceCardId(payment.getSourceCardId())
        .destinationCardId(payment.getDestinationCardId())
        .fee(payment.getFee())
        .rejection(rejection(payment.getRejection()))
        .createdAt(utc(payment.getCreatedAt()))
        .updatedAt(utc(payment.getUpdatedAt()));
  }

  /** A page of wallets with its totals. */
  public WalletPageResponse toWalletPage(ResultPage<Wallet> page) {
    return new WalletPageResponse()
        .items(page.items().stream().map(this::toResponse).toList())
        .page(page.page())
        .size(page.size())
        .totalElements(page.totalElements())
        .totalPages(page.totalPages());
  }

  /** A page of movements with its totals. */
  public MovementPageResponse toMovementPage(ResultPage<WalletMovement> page) {
    return new MovementPageResponse()
        .items(page.items().stream().map(this::toResponse).toList())
        .page(page.page())
        .size(page.size())
        .totalElements(page.totalElements())
        .totalPages(page.totalPages());
  }

  private static Rejection rejection(com.banking.yankiservice.domain.model.vo.Rejection rejection) {
    return rejection == null
        ? null
        : new Rejection().code(rejection.code()).message(rejection.message());
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
