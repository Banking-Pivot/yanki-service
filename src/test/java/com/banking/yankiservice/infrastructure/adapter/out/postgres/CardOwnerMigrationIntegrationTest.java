package com.banking.yankiservice.infrastructure.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.CardLinkStatus;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletResponse;
import com.banking.yankiservice.support.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * What migration V2 does to a database that already has wallets sharing a debit card: it is run
 * again here over rows written the way they could be before the rule existed.
 */
class CardOwnerMigrationIntegrationTest extends IntegrationTest {

  private static final String MIGRATION = "db/migration/V2__card_owner_and_payment_limits.sql";

  @Test
  void walletsThatSharedACardKeepOneOfThemAndTheRuleHoldsAfterwards() throws IOException {
    UUID linkedLater = walletOfAna();
    UUID waiting = walletOfBeto();
    UUID linkedFirst = registered("999999999", "40000001");
    UUID alone = registered("988888888", "40000002");
    UUID shared = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    run("DROP INDEX uk_wallets_active_card");
    run("DROP INDEX ix_payments_sender_day");
    holds(linkedLater, shared, "LINKED", "2026-11-20T15:00:03Z");
    holds(waiting, shared, "PENDING", "2026-11-20T15:00:01Z");
    holds(linkedFirst, shared, "LINKED", "2026-11-20T15:00:02Z");
    holds(alone, other, "PENDING", "2026-11-20T15:00:00Z");

    for (String statement : statements()) {
      run(statement);
    }

    assertThat(wallet(linkedFirst).getCard().getStatus()).isEqualTo(CardLinkStatus.LINKED);
    assertThat(wallet(alone).getCard().getStatus()).isEqualTo(CardLinkStatus.PENDING);
    for (UUID loser : new UUID[] {linkedLater, waiting}) {
      WalletResponse rejected = wallet(loser);
      assertThat(rejected.getCard().getCardId()).isEqualTo(shared);
      assertThat(rejected.getCard().getStatus()).isEqualTo(CardLinkStatus.REJECTED);
      assertThat(rejected.getCard().getRejection().getCode()).isEqualTo("CARD_ALREADY_LINKED");
    }
    // The index is back: the card of the wallet that kept it is refused to the others.
    linkCard(waiting, shared)
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("CARD_ALREADY_LINKED");
  }

  private void holds(UUID walletId, UUID cardId, String status, String sentAt) {
    databaseClient
        .sql(
            "UPDATE wallets SET card_id = :card, card_link_id = :link, card_link_status = :status,"
                + " card_correlation_id = 'corr-old', card_command_sent_at = :sentAt::timestamptz"
                + " WHERE id = :id")
        .bind("card", cardId)
        .bind("link", UUID.randomUUID())
        .bind("status", status)
        .bind("sentAt", sentAt)
        .bind("id", walletId)
        .then()
        .block();
  }

  private void run(String statement) {
    databaseClient.sql(statement).then().block();
  }

  private static String[] statements() throws IOException {
    String script = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
    return script.replaceAll("(?m)^--.*$", "").strip().split(";\\s*");
  }
}
