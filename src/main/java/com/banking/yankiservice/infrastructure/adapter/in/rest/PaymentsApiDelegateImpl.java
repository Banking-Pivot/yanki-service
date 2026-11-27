package com.banking.yankiservice.infrastructure.adapter.in.rest;

import com.banking.yankiservice.application.command.RequestPaymentCommand;
import com.banking.yankiservice.application.model.RequestedPayment;
import com.banking.yankiservice.application.port.in.GetPaymentUseCase;
import com.banking.yankiservice.application.port.in.RequestPaymentUseCase;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.api.PaymentsApiDelegate;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentRequest;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentResponse;
import com.banking.yankiservice.infrastructure.correlation.CorrelationId;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Payments by mobile number. A request is answered 202 as soon as the payment is recorded, with
 * where to read it; a payment between two wallets without a card is already complete in that
 * answer. A recognized repeat answers the same with {@code Idempotent-Replayed: true} and the
 * payment in its current state.
 */
@Component
@RequiredArgsConstructor
public class PaymentsApiDelegateImpl implements PaymentsApiDelegate {

  static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
  static final String PAYMENTS_PATH = "/api/v1/yanki/payments/";

  private final RequestPaymentUseCase requestPayment;
  private final GetPaymentUseCase getPayment;
  private final YankiRestMapper mapper;

  @Override
  public Mono<ResponseEntity<PaymentResponse>> requestPayment(
      Mono<PaymentRequest> request, ServerWebExchange exchange) {
    return request
        .map(
            body ->
                new RequestPaymentCommand(
                    body.getOperationId(),
                    body.getFromPhoneNumber(),
                    body.getToPhoneNumber(),
                    body.getAmount(),
                    body.getDescription(),
                    CorrelationId.of(exchange)))
        .flatMap(requestPayment::execute)
        .map(this::accepted);
  }

  @Override
  public Mono<ResponseEntity<PaymentResponse>> getPayment(
      UUID paymentId, ServerWebExchange exchange) {
    return getPayment.execute(paymentId).map(mapper::toResponse).map(ResponseEntity::ok);
  }

  private ResponseEntity<PaymentResponse> accepted(RequestedPayment requested) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.accepted().location(URI.create(PAYMENTS_PATH + requested.payment().getId()));
    if (requested.replayed()) {
      response.header(IDEMPOTENT_REPLAYED, "true");
    }
    return response.body(mapper.toResponse(requested.payment()));
  }
}
