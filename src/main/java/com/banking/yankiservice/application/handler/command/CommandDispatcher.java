package com.banking.yankiservice.application.handler.command;

import com.banking.yankiservice.application.port.out.CommandPublisherPort;
import com.banking.yankiservice.domain.model.OutboundCommand;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.Wallet;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Sends the request a payment or a card link is waiting on, right after it was stored.
 *
 * <p>What is stored is what guarantees the request. When it cannot be sent nothing is lost: {@link
 * ResendPendingCommandsHandler} finds the payment or the link still pending and sends it again. So
 * a failure here is logged and not passed on.
 */
public class CommandDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(CommandDispatcher.class);

  private final CommandPublisherPort publisher;

  public CommandDispatcher(CommandPublisherPort publisher) {
    this.publisher = Objects.requireNonNull(publisher);
  }

  /**
   * Sends MOVE_FUNDS for a payment that waits for Transaction Service.
   *
   * @return completion, also when there is nothing to send or it could not be sent
   */
  public Mono<Void> dispatch(Payment payment) {
    return Mono.justOrEmpty(payment.nextCommand())
        .flatMap(command -> send(command, payment.getCorrelationId()));
  }

  /**
   * Sends RESOLVE_CARD for a wallet that waits for Card Service.
   *
   * @return completion, also when there is nothing to send or it could not be sent
   */
  public Mono<Void> dispatch(Wallet wallet) {
    return Mono.justOrEmpty(wallet.nextCommand())
        .flatMap(command -> send(command, wallet.getCardLink().correlationId()));
  }

  private Mono<Void> send(OutboundCommand command, String correlationId) {
    return publisher
        .publish(command, correlationId)
        .onErrorResume(
            failure -> {
              LOG.warn(
                  "Command not sent, it will be sent again. corrId={}, command={}, "
                      + "transactionId={}, failureType={}",
                  correlationId,
                  command.getClass().getSimpleName(),
                  command.transactionId(),
                  failure.getClass().getName());
              return Mono.empty();
            });
  }
}
