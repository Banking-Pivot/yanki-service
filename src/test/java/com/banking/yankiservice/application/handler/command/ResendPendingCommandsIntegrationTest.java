package com.banking.yankiservice.application.handler.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.CardLinkStatus;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentStatus;
import com.banking.yankiservice.support.IntegrationTest;
import com.banking.yankiservice.support.TestParticipants.ReceivedCommand;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The re-send against the real database and the real Kafka. The scheduler of the tests runs once an
 * hour, so the re-send is run here by hand with a clock that is ahead of the requests.
 */
class ResendPendingCommandsIntegrationTest extends IntegrationTest {

  @Autowired private WalletStorePort wallets;
  @Autowired private PaymentStorePort payments;
  @Autowired private CommandDispatcher dispatcher;

  @Test
  void requestsWithoutAnswerAreSentAgainExactlyAsTheFirstTime() {
    UUID ana = walletOfAna();
    UUID beto = walletOfBeto();
    linkedCard(ana);
    UUID operation = requestedPayment();
    UUID card = UUID.randomUUID();
    UUID link = requestedLink(beto, card);
    ReceivedCommand firstMove = participants.awaitCommand("MOVE_FUNDS", operation);
    ReceivedCommand firstResolve = participants.awaitCommand("RESOLVE_CARD", link);

    assertThat(resendAt(Duration.ofSeconds(10))).isZero();
    assertThat(resendAt(Duration.ofMinutes(1))).isEqualTo(2);

    ReceivedCommand secondMove = participants.awaitCommand("MOVE_FUNDS", operation, 2);
    ReceivedCommand secondResolve = participants.awaitCommand("RESOLVE_CARD", link, 2);
    assertThat(secondMove.text()).isEqualTo(firstMove.text());
    assertThat(secondMove.key()).isEqualTo(firstMove.key());
    assertThat(secondMove.correlationId()).isEqualTo(firstMove.correlationId());
    assertThat(secondResolve.text()).isEqualTo(firstResolve.text());
    assertThat(secondResolve.key()).isEqualTo(card.toString());
    assertThat(secondResolve.correlationId()).isEqualTo(firstResolve.correlationId());
    // What was just sent again is left alone for a while.
    assertThat(resendAt(Duration.ofSeconds(70))).isZero();
    assertThat(resendAt(Duration.ofMinutes(2))).isEqualTo(2);

    // The answers still finish both.
    participants.fundsMoved(operation, "0.00");
    resolveCard(beto, link, card);
    awaitStatus(operation, PaymentStatus.COMPLETED);
    awaitCard(beto, CardLinkStatus.LINKED);
    assertThat(resendAt(Duration.ofHours(1))).isZero();
    assertThat(participants.countCommands("MOVE_FUNDS", operation, Duration.ofSeconds(1)))
        .isEqualTo(3);
  }

  private long resendAt(Duration ahead) {
    return new ResendPendingCommandsHandler(
            wallets,
            payments,
            dispatcher,
            Clock.offset(Clock.systemUTC(), ahead),
            Duration.ofSeconds(30),
            50)
        .execute()
        .block();
  }
}
