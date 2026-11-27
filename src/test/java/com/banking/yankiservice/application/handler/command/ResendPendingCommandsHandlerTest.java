package com.banking.yankiservice.application.handler.command;

import static com.banking.yankiservice.support.Fixtures.ANA_CARD;
import static com.banking.yankiservice.support.Fixtures.ANA_ID;
import static com.banking.yankiservice.support.Fixtures.ANA_LINK;
import static com.banking.yankiservice.support.Fixtures.BETO_CARD;
import static com.banking.yankiservice.support.Fixtures.BETO_ID;
import static com.banking.yankiservice.support.Fixtures.BETO_LINK;
import static com.banking.yankiservice.support.Fixtures.START;
import static com.banking.yankiservice.support.Fixtures.ana;
import static com.banking.yankiservice.support.Fixtures.beto;
import static com.banking.yankiservice.support.Fixtures.clockAt;
import static com.banking.yankiservice.support.Fixtures.linked;
import static com.banking.yankiservice.support.Fixtures.linking;
import static com.banking.yankiservice.support.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.banking.yankiservice.application.port.out.CommandPublisherPort;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.vo.OutboundCommand;
import com.banking.yankiservice.support.InMemoryStores;
import com.banking.yankiservice.support.RecordingPublisher;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** The periodic re-send and the dispatcher it shares with the requests. */
class ResendPendingCommandsHandlerTest {

  private static final UUID OLD = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID RECENT = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID FINISHED = UUID.fromString("00000000-0000-4000-8000-000000000003");

  private final InMemoryStores stores = new InMemoryStores();
  private final RecordingPublisher publisher = new RecordingPublisher();

  @Test
  void onlyTheRequestsThatWaitedTooLongAreSentAgain() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(linking(beto("0.00"), BETO_LINK, BETO_CARD));
    stores.put(pending(OLD, sender));
    stores.put(pending(RECENT, sender).commandResent(START.plusSeconds(50)));
    stores.put(pending(FINISHED, sender).fundsMoved(null, START).orElseThrow());

    Long sent = handlerAt(60, 50).execute().block();

    assertThat(sent).isEqualTo(2);
    assertThat(publisher.sent())
        .extracting(RecordingPublisher.Sent::command)
        .containsExactly(
            new OutboundCommand.MoveFunds(OLD, ANA_CARD, null, money("12.50"), "PEN", "Rent"),
            new OutboundCommand.ResolveCard(BETO_LINK, BETO_CARD));
    assertThat(publisher.sent())
        .extracting(RecordingPublisher.Sent::correlationId)
        .containsExactly("corr-1", "corr-link");
    assertThat(stores.payment(OLD).getCommandAttempts()).isEqualTo(1);
    assertThat(stores.payment(OLD).getCommandSentAt()).isEqualTo(START.plusSeconds(60));
    assertThat(stores.payment(RECENT).getCommandAttempts()).isEqualTo(1);
    assertThat(stores.wallet(BETO_ID).getCardLink().commandAttempts()).isEqualTo(1);
    assertThat(stores.wallet(BETO_ID).getCardLink().commandSentAt())
        .isEqualTo(START.plusSeconds(60));
    assertThat(stores.wallet(ANA_ID)).isSameAs(sender);
  }

  @Test
  void aRequestIsSentAgainForAsLongAsItWaits() {
    stores.put(linking(ana("0.00"), ANA_LINK, ANA_CARD));

    assertThat(handlerAt(31, 50).execute().block()).isEqualTo(1);
    assertThat(handlerAt(40, 50).execute().block()).isZero();
    assertThat(handlerAt(62, 50).execute().block()).isEqualTo(1);
    assertThat(stores.wallet(ANA_ID).getCardLink().commandAttempts()).isEqualTo(2);
  }

  @Test
  void aRunSendsAtMostItsBatchOfEachKind() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(pending(OLD, sender));
    stores.put(pending(RECENT, sender));

    assertThat(handlerAt(60, 1).execute().block()).isEqualTo(1);
    assertThat(handlerAt(60, 1).execute().block()).isEqualTo(1);
    assertThat(handlerAt(60, 1).execute().block()).isZero();
  }

  @Test
  void whatWasAnsweredInTheMeantimeIsNotSentAgain() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(linking(beto("0.00"), BETO_LINK, BETO_CARD));
    stores.put(pending(OLD, sender));
    stores.failNextPaymentUpdates(1);
    stores.failNextWalletUpdates(1);

    assertThat(handlerAt(60, 50).execute().block()).isZero();
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aRunCountsARequestEvenWhenKafkaIsDown() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(pending(OLD, sender));
    publisher.goDown();

    assertThat(handlerAt(60, 50).execute().block()).isEqualTo(1);
    assertThat(stores.payment(OLD).getCommandAttempts()).isEqualTo(1);
  }

  @Test
  void theDispatcherSendsOnlyWhatIsStillWaitingAndSwallowsAFailedSend() {
    CommandPublisherPort port = mock(CommandPublisherPort.class);
    when(port.publish(any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));
    CommandDispatcher dispatcher = new CommandDispatcher(port);
    Wallet waiting = linking(ana("0.00"), ANA_LINK, ANA_CARD);

    dispatcher.dispatch(waiting).block();
    verify(port).publish(new OutboundCommand.ResolveCard(ANA_LINK, ANA_CARD), "corr-link");

    CommandPublisherPort unused = mock(CommandPublisherPort.class);
    CommandDispatcher quiet = new CommandDispatcher(unused);
    quiet.dispatch(ana("0.00")).block();
    quiet.dispatch(linked(ana("0.00"), ANA_LINK, ANA_CARD)).block();
    quiet
        .dispatch(Payment.request(OLD, ana("50.00"), beto("0.00"), money("1.00"), null, "c", START))
        .block();
    verifyNoInteractions(unused);
  }

  private ResendPendingCommandsHandler handlerAt(long secondsAfterStart, int batchSize) {
    return new ResendPendingCommandsHandler(
        stores.wallets(),
        stores.payments(),
        new CommandDispatcher(publisher),
        clockAt(secondsAfterStart),
        Duration.ofSeconds(30),
        batchSize);
  }

  private static Payment pending(UUID id, Wallet sender) {
    return Payment.request(id, sender, beto("0.00"), money("12.50"), "Rent", "corr-1", START);
  }
}
