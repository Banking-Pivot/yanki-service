package com.banking.yankiservice.application.handler.command;

import static com.banking.yankiservice.support.Fixtures.ANA;
import static com.banking.yankiservice.support.Fixtures.ANA_CARD;
import static com.banking.yankiservice.support.Fixtures.ANA_ID;
import static com.banking.yankiservice.support.Fixtures.ANA_LINK;
import static com.banking.yankiservice.support.Fixtures.BETO;
import static com.banking.yankiservice.support.Fixtures.BETO_CARD;
import static com.banking.yankiservice.support.Fixtures.BETO_ID;
import static com.banking.yankiservice.support.Fixtures.BETO_LINK;
import static com.banking.yankiservice.support.Fixtures.START;
import static com.banking.yankiservice.support.Fixtures.ana;
import static com.banking.yankiservice.support.Fixtures.beto;
import static com.banking.yankiservice.support.Fixtures.clockAt;
import static com.banking.yankiservice.support.Fixtures.ids;
import static com.banking.yankiservice.support.Fixtures.linked;
import static com.banking.yankiservice.support.Fixtures.linking;
import static com.banking.yankiservice.support.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.yankiservice.application.exception.ConcurrentChangeException;
import com.banking.yankiservice.domain.model.CardLinkStatus;
import com.banking.yankiservice.domain.model.Funding;
import com.banking.yankiservice.domain.model.InboundReply;
import com.banking.yankiservice.domain.model.MovementType;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.PaymentStatus;
import com.banking.yankiservice.domain.model.Rejection;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.WalletMovement;
import com.banking.yankiservice.support.InMemoryStores;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplyReplyHandlerTest {

  private static final UUID PAYMENT = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final Rejection BLOCKED = new Rejection("CARD_NOT_ACTIVE", "The card is blocked.");

  private final InMemoryStores stores = new InMemoryStores();
  private final ApplyReplyHandler handler =
      new ApplyReplyHandler(
          stores.wallets(), stores.payments(), stores.movements(), stores, clockAt(30), ids());

  // ----- card links

  @Test
  void aResolvedCardLinksTheWalletThatWaitsForIt() {
    stores.put(linking(ana("10.00"), ANA_LINK, ANA_CARD));

    handler.execute(new InboundReply.CardResolved(ANA_LINK, "DNI", "45678912")).block();

    Wallet wallet = stores.wallet(ANA_ID);
    assertThat(wallet.getCardLink().status()).isEqualTo(CardLinkStatus.LINKED);
    assertThat(wallet.getUpdatedAt()).isEqualTo(START.plusSeconds(30));
    assertThat(wallet.getBalance()).isEqualByComparingTo("10.00");
  }

  @Test
  void aCardOfSomebodyElseOrOfAnUnknownHolderIsNotLinked() {
    stores.put(linking(ana("10.00"), ANA_LINK, ANA_CARD));
    stores.put(linking(beto("5.00"), BETO_LINK, BETO_CARD));

    handler.execute(new InboundReply.CardResolved(ANA_LINK, "DNI", "41234567")).block();
    handler.execute(new InboundReply.CardResolved(BETO_LINK, null, null)).block();

    Wallet ana = stores.wallet(ANA_ID);
    assertThat(ana.getCardLink().status()).isEqualTo(CardLinkStatus.REJECTED);
    assertThat(ana.getCardLink().rejection().code()).isEqualTo("CARD_NOT_OWNED");
    assertThat(ana.isLinkedToCard()).isFalse();
    assertThat(stores.wallet(BETO_ID).getCardLink().rejection().code()).isEqualTo("CARD_NOT_OWNED");
  }

  @Test
  void aRejectedCardLeavesItsReasonInTheWallet() {
    stores.put(linking(ana("10.00"), ANA_LINK, ANA_CARD));

    handler.execute(new InboundReply.CardResolutionRejected(ANA_LINK, BLOCKED)).block();

    assertThat(stores.wallet(ANA_ID).getCardLink().status()).isEqualTo(CardLinkStatus.REJECTED);
    assertThat(stores.wallet(ANA_ID).getCardLink().rejection()).isEqualTo(BLOCKED);
  }

  @Test
  void anAnswerAboutACardNobodyWaitsForIsIgnored() {
    Wallet linked = linked(ana("10.00"), ANA_LINK, ANA_CARD);
    stores.put(linked);

    handler.execute(new InboundReply.CardResolutionRejected(ANA_LINK, BLOCKED)).block();
    handler.execute(new InboundReply.CardResolved(BETO_LINK, "DNI", "45678912")).block();

    assertThat(stores.wallet(ANA_ID)).isSameAs(linked);
  }

  @Test
  void anAnswerIsAppliedAgainWhenTheWalletChangedUnderIt() {
    stores.put(linking(ana("10.00"), ANA_LINK, ANA_CARD));
    stores.failNextWalletUpdates(2);

    handler.execute(new InboundReply.CardResolved(ANA_LINK, "DNI", "45678912")).block();

    assertThat(stores.wallet(ANA_ID).isLinkedToCard()).isTrue();
  }

  // ----- payments

  @Test
  void moneyMovedFromTheSendersAccountGoesIntoTheReceiversWallet() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(beto("5.00"));
    stores.put(pending(sender, beto("5.00")));

    handler.execute(new InboundReply.FundsMoved(PAYMENT, money("1.50"))).block();

    Payment payment = stores.payment(PAYMENT);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.getFee()).isEqualByComparingTo("1.50");
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("50.00");
    assertThat(stores.wallet(BETO_ID).getBalance()).isEqualByComparingTo("17.50");
    WalletMovement sent = stores.movementsOf(ANA_ID).get(0);
    assertThat(sent.type()).isEqualTo(MovementType.PAYMENT_SENT);
    assertThat(sent.fundedBy()).isEqualTo(Funding.CARD);
    assertThat(sent.balanceAfter()).isEqualByComparingTo("50.00");
    assertThat(sent.counterpartyPhoneNumber()).isEqualTo(BETO);
    WalletMovement received = stores.movementsOf(BETO_ID).get(0);
    assertThat(received.type()).isEqualTo(MovementType.PAYMENT_RECEIVED);
    assertThat(received.fundedBy()).isEqualTo(Funding.WALLET);
    assertThat(received.balanceAfter()).isEqualByComparingTo("17.50");
    assertThat(received.counterpartyPhoneNumber()).isEqualTo(ANA);
    assertThat(received.occurredAt()).isEqualTo(START.plusSeconds(30));
    assertThat(stores.transactions()).isEqualTo(1);
  }

  @Test
  void moneyMovedBetweenTwoAccountsChangesNoWalletBalance() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    Wallet receiver = linked(beto("5.00"), BETO_LINK, BETO_CARD);
    stores.put(sender);
    stores.put(receiver);
    stores.put(pending(sender, receiver));

    handler.execute(new InboundReply.FundsMoved(PAYMENT, null)).block();

    assertThat(stores.payment(PAYMENT).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(stores.payment(PAYMENT).getFee()).isNull();
    assertThat(stores.wallet(ANA_ID)).isSameAs(sender);
    assertThat(stores.wallet(BETO_ID)).isSameAs(receiver);
    assertThat(stores.movementsOf(ANA_ID))
        .singleElement()
        .extracting("fundedBy")
        .isEqualTo(Funding.CARD);
    assertThat(stores.movementsOf(BETO_ID))
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.type()).isEqualTo(MovementType.PAYMENT_RECEIVED);
              assertThat(movement.fundedBy()).isEqualTo(Funding.CARD);
              assertThat(movement.balanceAfter()).isEqualByComparingTo("5.00");
            });
  }

  @Test
  void moneyThatReachedTheReceiversAccountOnlyCompletesThePayment() {
    Wallet receiver = linked(beto("5.00"), BETO_LINK, BETO_CARD);
    stores.put(ana("37.50"));
    stores.put(receiver);
    stores.put(pending(ana("50.00"), receiver));

    handler.execute(new InboundReply.FundsMoved(PAYMENT, money("0.00"))).block();

    assertThat(stores.payment(PAYMENT).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("37.50");
    assertThat(stores.movementsOf(ANA_ID)).isEmpty();
    assertThat(stores.movementsOf(BETO_ID)).hasSize(1);
  }

  @Test
  void aRejectedPaymentGivesTheMoneyBackToASenderThatPaidFromTheWallet() {
    Wallet receiver = linked(beto("5.00"), BETO_LINK, BETO_CARD);
    stores.put(ana("37.50"));
    stores.put(receiver);
    stores.put(pending(ana("50.00"), receiver));

    handler.execute(new InboundReply.FundsMoveRejected(PAYMENT, BLOCKED)).block();

    Payment payment = stores.payment(PAYMENT);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(payment.getRejection()).isEqualTo(BLOCKED);
    assertThat(stores.wallet(ANA_ID).getBalance()).isEqualByComparingTo("50.00");
    assertThat(stores.movementsOf(ANA_ID))
        .singleElement()
        .satisfies(
            refund -> {
              assertThat(refund.type()).isEqualTo(MovementType.REFUND);
              assertThat(refund.fundedBy()).isEqualTo(Funding.WALLET);
              assertThat(refund.amount()).isEqualByComparingTo("12.50");
              assertThat(refund.balanceAfter()).isEqualByComparingTo("50.00");
              assertThat(refund.operationId()).isEqualTo(PAYMENT);
            });
    assertThat(stores.wallet(BETO_ID)).isSameAs(receiver);
    assertThat(stores.transactions()).isEqualTo(1);
  }

  @Test
  void aRejectedPaymentOfALinkedSenderMovesNothingHere() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(beto("5.00"));
    stores.put(pending(sender, beto("5.00")));

    handler
        .execute(
            new InboundReply.FundsMoveRejected(
                PAYMENT, new Rejection("INSUFFICIENT_FUNDS", "Not enough balance.")))
        .block();

    assertThat(stores.payment(PAYMENT).getStatus()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(stores.payment(PAYMENT).getRejection().code()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(stores.wallet(ANA_ID)).isSameAs(sender);
    assertThat(stores.wallet(BETO_ID).getBalance()).isEqualByComparingTo("5.00");
    assertThat(stores.movementsOf(ANA_ID)).isEmpty();
  }

  @Test
  void anAnswerDeliveredTwiceCountsOnce() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(beto("5.00"));
    stores.put(pending(sender, beto("5.00")));

    handler.execute(new InboundReply.FundsMoved(PAYMENT, money("1.50"))).block();
    handler.execute(new InboundReply.FundsMoved(PAYMENT, money("1.50"))).block();
    handler.execute(new InboundReply.FundsMoveRejected(PAYMENT, BLOCKED)).block();

    assertThat(stores.payment(PAYMENT).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(stores.wallet(BETO_ID).getBalance()).isEqualByComparingTo("17.50");
    assertThat(stores.movementsOf(BETO_ID)).hasSize(1);
  }

  @Test
  void anAnswerAboutAnUnknownPaymentIsIgnored() {
    handler.execute(new InboundReply.FundsMoved(PAYMENT, null)).block();
    handler.execute(new InboundReply.FundsMoveRejected(PAYMENT, BLOCKED)).block();

    assertThat(stores.payment(PAYMENT)).isNull();
    assertThat(stores.transactions()).isZero();
  }

  @Test
  void anAnswerIsAppliedAgainWhenThePaymentOrTheWalletChangedUnderIt() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(beto("5.00"));
    stores.put(pending(sender, beto("5.00")));
    stores.failNextPaymentUpdates(1);
    stores.failNextWalletUpdates(1);

    handler.execute(new InboundReply.FundsMoved(PAYMENT, null)).block();

    assertThat(stores.payment(PAYMENT).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(stores.wallet(BETO_ID).getBalance()).isEqualByComparingTo("17.50");
    assertThat(stores.movementsOf(BETO_ID)).hasSize(1);
    assertThat(stores.transactions()).isEqualTo(3);
  }

  @Test
  void anAnswerThatKeepsFailingIsPassedOnSoTheListenerRetriesIt() {
    Wallet sender = linked(ana("50.00"), ANA_LINK, ANA_CARD);
    stores.put(sender);
    stores.put(beto("5.00"));
    stores.put(pending(sender, beto("5.00")));
    stores.failNextPaymentUpdates(Conflicts.ATTEMPTS + 1);

    assertThatThrownBy(() -> handler.execute(new InboundReply.FundsMoved(PAYMENT, null)).block())
        .isInstanceOf(ConcurrentChangeException.class);
    assertThat(stores.payment(PAYMENT).isPending()).isTrue();
    assertThat(stores.wallet(BETO_ID).getBalance()).isEqualByComparingTo("5.00");
  }

  private static Payment pending(Wallet from, Wallet to) {
    return Payment.request(PAYMENT, from, to, money("12.50"), "Rent", "corr-1", START);
  }
}
