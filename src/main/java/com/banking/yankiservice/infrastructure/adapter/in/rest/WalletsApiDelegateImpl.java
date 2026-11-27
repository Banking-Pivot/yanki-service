package com.banking.yankiservice.infrastructure.adapter.in.rest;

import com.banking.yankiservice.application.command.LinkCardCommand;
import com.banking.yankiservice.application.command.RegisterWalletCommand;
import com.banking.yankiservice.application.command.TopUpWalletCommand;
import com.banking.yankiservice.application.model.RecordedTopUp;
import com.banking.yankiservice.application.port.in.GetWalletUseCase;
import com.banking.yankiservice.application.port.in.LinkCardUseCase;
import com.banking.yankiservice.application.port.in.ListMovementsUseCase;
import com.banking.yankiservice.application.port.in.ListWalletsUseCase;
import com.banking.yankiservice.application.port.in.RegisterWalletUseCase;
import com.banking.yankiservice.application.port.in.TopUpWalletUseCase;
import com.banking.yankiservice.application.port.in.UnlinkCardUseCase;
import com.banking.yankiservice.application.query.ListMovementsQuery;
import com.banking.yankiservice.application.query.ListWalletsQuery;
import com.banking.yankiservice.application.query.PageRequest;
import com.banking.yankiservice.domain.model.enums.DocumentType;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.api.WalletsApiDelegate;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.LinkCardRequest;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementPageResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.RegisterWalletRequest;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.TopUpRequest;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletPageResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletResponse;
import com.banking.yankiservice.infrastructure.correlation.CorrelationId;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Wallets: opening one, reading them, top-ups, the link to a debit card and the movements.
 *
 * <p>A top-up answers 201 with its movement; a recognized repeat answers the same with {@code
 * Idempotent-Replayed: true}. A link request answers 202 with the wallet waiting for Card Service;
 * the result is read from the wallet.
 */
@Component
@RequiredArgsConstructor
public class WalletsApiDelegateImpl implements WalletsApiDelegate {

  static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
  static final String WALLETS_PATH = "/api/v1/yanki/wallets/";

  private final RegisterWalletUseCase registerWallet;
  private final GetWalletUseCase getWallet;
  private final ListWalletsUseCase listWallets;
  private final TopUpWalletUseCase topUpWallet;
  private final LinkCardUseCase linkCard;
  private final UnlinkCardUseCase unlinkCard;
  private final ListMovementsUseCase listMovements;
  private final YankiRestMapper mapper;

  @Override
  public Mono<ResponseEntity<WalletResponse>> registerWallet(
      Mono<RegisterWalletRequest> request, ServerWebExchange exchange) {
    return request
        .map(
            body ->
                new RegisterWalletCommand(
                    DocumentType.valueOf(body.getDocumentType().getValue()),
                    body.getDocumentNumber(),
                    body.getPhoneNumber(),
                    body.getImei(),
                    body.getEmail()))
        .flatMap(registerWallet::execute)
        .map(
            wallet ->
                ResponseEntity.created(URI.create(WALLETS_PATH + wallet.getId()))
                    .body(mapper.toResponse(wallet)));
  }

  @Override
  public Mono<ResponseEntity<WalletPageResponse>> listWallets(
      Integer page, Integer size, String phoneNumber, ServerWebExchange exchange) {
    return Mono.fromCallable(() -> new ListWalletsQuery(PageRequest.of(page, size), phoneNumber))
        .flatMap(listWallets::execute)
        .map(mapper::toWalletPage)
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<WalletResponse>> getWallet(UUID walletId, ServerWebExchange exchange) {
    return getWallet.execute(walletId).map(mapper::toResponse).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<MovementResponse>> topUpWallet(
      UUID walletId, Mono<TopUpRequest> request, ServerWebExchange exchange) {
    return request
        .map(body -> new TopUpWalletCommand(walletId, body.getOperationId(), body.getAmount()))
        .flatMap(topUpWallet::execute)
        .map(this::created);
  }

  @Override
  public Mono<ResponseEntity<WalletResponse>> linkCard(
      UUID walletId, Mono<LinkCardRequest> request, ServerWebExchange exchange) {
    return request
        .map(body -> new LinkCardCommand(walletId, body.getCardId(), CorrelationId.of(exchange)))
        .flatMap(linkCard::execute)
        .map(wallet -> ResponseEntity.accepted().body(mapper.toResponse(wallet)));
  }

  @Override
  public Mono<ResponseEntity<Void>> unlinkCard(UUID walletId, ServerWebExchange exchange) {
    return unlinkCard
        .execute(walletId)
        .then(Mono.fromSupplier(() -> ResponseEntity.noContent().<Void>build()));
  }

  @Override
  public Mono<ResponseEntity<MovementPageResponse>> listWalletMovements(
      UUID walletId, Integer page, Integer size, ServerWebExchange exchange) {
    return Mono.fromCallable(() -> new ListMovementsQuery(walletId, PageRequest.of(page, size)))
        .flatMap(listMovements::execute)
        .map(mapper::toMovementPage)
        .map(ResponseEntity::ok);
  }

  private ResponseEntity<MovementResponse> created(RecordedTopUp recorded) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (recorded.replayed()) {
      response.header(IDEMPOTENT_REPLAYED, "true");
    }
    return response.body(mapper.toResponse(recorded.movement()));
  }
}
