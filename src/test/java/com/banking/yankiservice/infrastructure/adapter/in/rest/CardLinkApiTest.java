package com.banking.yankiservice.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.CardLinkStatus;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletResponse;
import com.banking.yankiservice.support.IntegrationTest;
import com.banking.yankiservice.support.TestParticipants;
import com.banking.yankiservice.support.TestParticipants.ReceivedCommand;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

/** The link between a wallet and a debit card, checked with Card Service through Kafka. */
class CardLinkApiTest extends IntegrationTest {

  @Autowired private WalletStorePort walletStore;

  @Test
  void aLinkWaitsForCardServiceAndTheRequestSaysWhereToAnswer() {
    UUID wallet = walletOfAna();
    UUID card = UUID.randomUUID();

    WalletResponse waiting =
        webTestClient
            .put()
            .uri("/api/v1/yanki/wallets/{id}/card", wallet)
            .header("X-Correlation-ID", "link-2026-11-20")
            .bodyValue(Map.of("cardId", card.toString()))
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectBody(WalletResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(waiting.getCard().getCardId()).isEqualTo(card);
    assertThat(waiting.getCard().getStatus()).isEqualTo(CardLinkStatus.PENDING);
    assertThat(waiting.getCard().getRejection()).isNull();
    ReceivedCommand command = participants.awaitCommandAbout("RESOLVE_CARD", "cardId", card);
    String link = command.field("transactionId");
    assertThat(command.topic()).isEqualTo(TestParticipants.CARD_COMMANDS);
    assertThat(command.key()).isEqualTo(card.toString());
    assertThat(command.correlationId()).isEqualTo("link-2026-11-20");
    assertThat(command.text())
        .isEqualTo(
            "{\"type\":\"RESOLVE_CARD\",\"transactionId\":\""
                + link
                + "\",\"cardId\":\""
                + card
                + "\",\"replyTo\":\"banking.yanki.replies\"}");

    resolveCard(wallet, UUID.fromString(link), card);

    WalletResponse linked = awaitCard(wallet, CardLinkStatus.LINKED);
    assertThat(linked.getCard().getCardId()).isEqualTo(card);
    assertThat(linked.getCard().getRejection()).isNull();
    assertThat(linked.getUpdatedAt()).isAfter(waiting.getCreatedAt());
  }

  @Test
  void aCardThatCardServiceRefusesLeavesItsReasonAndAnotherCanBeTried() {
    UUID wallet = walletOfAna();
    UUID blocked = UUID.randomUUID();
    UUID link = requestedLink(wallet, blocked);

    participants.cardRejected(link, blocked, "CARD_NOT_ACTIVE", "The card is blocked.");

    WalletResponse rejected = awaitCard(wallet, CardLinkStatus.REJECTED);
    assertThat(rejected.getCard().getCardId()).isEqualTo(blocked);
    assertThat(rejected.getCard().getRejection().getCode()).isEqualTo("CARD_NOT_ACTIVE");
    assertThat(rejected.getCard().getRejection().getMessage()).isEqualTo("The card is blocked.");
    // The wallet keeps working with its own balance.
    funded(wallet, "10.00");

    UUID good = linkedCard(wallet);
    assertThat(wallet(wallet).getCard().getCardId()).isEqualTo(good);
    assertThat(wallet(wallet).getCard().getRejection()).isNull();
  }

  @Test
  void theSameCardCanBeAskedForAgainAfterARefusal() {
    UUID wallet = walletOfAna();
    UUID card = UUID.randomUUID();
    UUID first = requestedLink(wallet, card);
    participants.cardRejected(first, card, "CARD_NOT_FOUND", "The card does not exist.");
    awaitCard(wallet, CardLinkStatus.REJECTED);

    linkCard(wallet, card)
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.card.status")
        .isEqualTo("PENDING")
        .jsonPath("$.card.rejection")
        .doesNotExist();
    ReceivedCommand second = participants.awaitCommandAbout("RESOLVE_CARD", "cardId", card, 2);

    assertThat(second.field("transactionId")).isNotEqualTo(first.toString());
    // The old answer, delivered again, does not touch the new request.
    participants.cardRejected(first, card, "CARD_NOT_FOUND", "The card does not exist.");
    resolveCard(wallet, UUID.fromString(second.field("transactionId")), card);
    awaitCard(wallet, CardLinkStatus.LINKED);
  }

  @Test
  void askingAgainForTheSameCardWhileItIsCheckedOrLinkedChangesNothing() {
    UUID wallet = walletOfAna();
    UUID card = UUID.randomUUID();
    UUID link = requestedLink(wallet, card);

    linkCard(wallet, card)
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.card.status")
        .isEqualTo("PENDING");
    resolveCard(wallet, link, card);
    awaitCard(wallet, CardLinkStatus.LINKED);
    linkCard(wallet, card)
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.card.status")
        .isEqualTo("LINKED");

    assertThat(participants.countCommands("RESOLVE_CARD", link, Duration.ofSeconds(1)))
        .isEqualTo(1);
    assertThat(
            participants.awaitCommandAbout("RESOLVE_CARD", "cardId", card).field("transactionId"))
        .isEqualTo(link.toString());
  }

  @Test
  void aLinkedWalletMustBeUnlinkedBeforeItTakesAnotherCard() {
    UUID wallet = walletOfAna();
    UUID card = linkedCard(wallet);

    linkCard(wallet, UUID.randomUUID())
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("WALLET_ALREADY_LINKED");

    assertThat(wallet(wallet).getCard().getCardId()).isEqualTo(card);
  }

  @Test
  void aCardStillBeingCheckedCanBeReplacedByAnother() {
    UUID wallet = walletOfAna();
    UUID first = UUID.randomUUID();
    UUID abandoned = requestedLink(wallet, first);
    UUID second = UUID.randomUUID();
    UUID wanted = requestedLink(wallet, second);

    // The answer about the first card arrives late and is ignored.
    resolveCard(wallet, abandoned, first);
    resolveCard(wallet, wanted, second);

    assertThat(awaitCard(wallet, CardLinkStatus.LINKED).getCard().getCardId()).isEqualTo(second);
  }

  @Test
  void aLinkedWalletTakesNoTopUpsAndUnlinkingBringsThemBack() {
    UUID wallet = walletOfAna();
    funded(wallet, "20.00");
    linkedCard(wallet);
    UUID operation = UUID.randomUUID();

    topUp(wallet, operation, "10.00")
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("WALLET_LINKED_TO_CARD");
    assertThat(balance(wallet)).isEqualByComparingTo("20.00");

    unlink(wallet);

    WalletResponse unlinked = wallet(wallet);
    assertThat(unlinked.getCard()).isNull();
    assertThat(unlinked.getBalance()).isEqualByComparingTo("20.00");
    topUp(wallet, operation, "10.00").expectStatus().isCreated();
    assertThat(balance(wallet)).isEqualByComparingTo("30.00");
    // Unlinking a wallet without a card is not an error.
    unlink(wallet);
  }

  @Test
  void unlinkingWhileTheCardIsCheckedDropsTheRequest() {
    UUID wallet = walletOfAna();
    UUID card = UUID.randomUUID();
    UUID link = requestedLink(wallet, card);

    unlink(wallet);
    resolveCard(wallet, link, card);
    // A second link that does get its answer shows the first answer was read and ignored.
    UUID other = UUID.randomUUID();
    UUID again = requestedLink(wallet, other);
    participants.cardRejected(again, other, "CARD_NOT_FOUND", "The card does not exist.");

    WalletResponse result = awaitCard(wallet, CardLinkStatus.REJECTED);
    assertThat(result.getCard().getCardId()).isEqualTo(other);
  }

  @Test
  void aLinkRequestWithoutACardIsRefused() {
    UUID wallet = walletOfAna();

    webTestClient
        .put()
        .uri("/api/v1/yanki/wallets/{id}/card", wallet)
        .bodyValue(Map.of("cardId", "not-a-uuid"))
        .exchange()
        .expectStatus()
        .isBadRequest();
    webTestClient
        .put()
        .uri("/api/v1/yanki/wallets/{id}/card", wallet)
        .bodyValue(Map.of())
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");

    assertThat(wallet(wallet).getCard()).isNull();
  }

  private void unlink(UUID wallet) {
    webTestClient
        .delete()
        .uri("/api/v1/yanki/wallets/{id}/card", wallet)
        .exchange()
        .expectStatus()
        .isNoContent();
  }

  // ----- whose card it is

  @Test
  void aCardOfSomebodyElseIsNotLinkedAndTheWalletKeepsItsBalance() {
    UUID wallet = walletOfAna();
    funded(wallet, "10.00");
    UUID card = UUID.randomUUID();

    participants.cardResolved(requestedLink(wallet, card), card, "DNI", "41234567");

    WalletResponse rejected = awaitCard(wallet, CardLinkStatus.REJECTED);
    assertThat(rejected.getCard().getCardId()).isEqualTo(card);
    assertThat(rejected.getCard().getRejection().getCode()).isEqualTo("CARD_NOT_OWNED");
    assertThat(rejected.getBalance()).isEqualByComparingTo("10.00");
    // Not linked: the wallet still takes top-ups.
    funded(wallet, "5.00");
  }

  @Test
  void aCardWhoseHolderCardServiceDoesNotSayIsNotLinked() {
    UUID wallet = walletOfAna();
    UUID card = UUID.randomUUID();

    participants.cardResolved(requestedLink(wallet, card), card, null, null);

    assertThat(awaitCard(wallet, CardLinkStatus.REJECTED).getCard().getRejection().getCode())
        .isEqualTo("CARD_NOT_OWNED");
  }

  @Test
  void theHolderIsMatchedWhateverTheCaseOfTheDocumentType() {
    UUID wallet = walletOfAna();
    UUID card = UUID.randomUUID();

    participants.cardResolved(requestedLink(wallet, card), card, "dni", " 45678912 ");

    awaitCard(wallet, CardLinkStatus.LINKED);
  }

  @Test
  void aCardLinkedToAWalletOrBeingCheckedForItIsRefusedToAnother() {
    UUID wallet = walletOfAna();
    UUID other = walletOfBeto();
    UUID card = UUID.randomUUID();
    UUID link = requestedLink(wallet, card);

    linkCard(other, card)
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("CARD_ALREADY_LINKED");
    resolveCard(wallet, link, card);
    awaitCard(wallet, CardLinkStatus.LINKED);
    linkCard(other, card)
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("CARD_ALREADY_LINKED");

    assertThat(wallet(other).getCard()).isNull();
    assertThat(participants.countCommands("RESOLVE_CARD", link, Duration.ofSeconds(1)))
        .isEqualTo(1);
    // Once the first wallet lets the card go, the other one may ask for it.
    unlink(wallet);
    linkCard(other, card).expectStatus().isAccepted();
  }

  @Test
  void aCardRejectedForOneWalletCanBeAskedForByAnother() {
    UUID wallet = walletOfAna();
    UUID other = walletOfBeto();
    UUID card = UUID.randomUUID();
    participants.cardResolved(requestedLink(wallet, card), card, "DNI", "41234567");
    awaitCard(wallet, CardLinkStatus.REJECTED);

    linkCard(other, card).expectStatus().isAccepted();
    resolveCard(
        other,
        UUID.fromString(
            participants
                .awaitCommandAbout("RESOLVE_CARD", "cardId", card, 2)
                .field("transactionId")),
        card);

    awaitCard(other, CardLinkStatus.LINKED);
    // And now the first wallet cannot ask for it again.
    linkCard(wallet, card)
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("CARD_ALREADY_LINKED");
  }

  @Test
  void theDatabaseItselfRefusesASecondWalletForACard() {
    UUID wallet = walletOfAna();
    UUID card = UUID.randomUUID();
    requestedLink(wallet, card);
    Wallet other = walletStore.findById(walletOfBeto()).block();

    // Written without the check of the use case, as two requests at the same time would.
    Mono<Void> write =
        walletStore.update(
            other.requestCardLink(UUID.randomUUID(), card, "corr-race", Instant.now()),
            other.getVersion());

    assertThatThrownBy(write::block)
        .isInstanceOfSatisfying(
            YankiRuleViolationException.class,
            refused ->
                assertThat(refused.violation()).isEqualTo(RuleViolation.CARD_ALREADY_LINKED));
    assertThat(wallet(other.getId()).getCard()).isNull();
    assertThat(walletStore.isCardHeldByAnother(card, other.getId()).block()).isTrue();
    assertThat(walletStore.isCardHeldByAnother(card, wallet).block()).isFalse();
  }
}
