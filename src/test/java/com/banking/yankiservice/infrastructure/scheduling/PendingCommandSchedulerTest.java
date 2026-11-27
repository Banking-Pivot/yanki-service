package com.banking.yankiservice.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.banking.yankiservice.infrastructure.config.YankiProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PendingCommandSchedulerTest {

  private static final YankiProperties EVERY_20_MS =
      new YankiProperties(
          new YankiProperties.Messaging(
              "transaction-commands",
              "card-commands",
              "replies",
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              Duration.ofMillis(20),
              Duration.ofSeconds(30),
              50),
          new YankiProperties.Limits(new BigDecimal("500.00"), new BigDecimal("2000.00")),
          ZoneId.of("America/Lima"));

  @Test
  void theReSendRunsAgainAndAgainUntilTheServiceStops() {
    AtomicInteger runs = new AtomicInteger();
    PendingCommandScheduler scheduler =
        new PendingCommandScheduler(
            () -> Mono.fromSupplier(() -> (long) runs.incrementAndGet()), EVERY_20_MS);

    scheduler.start();
    scheduler.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 3);
    scheduler.stop();
    scheduler.stop();
    int afterStop = runs.get();

    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> true);
    assertThat(runs.get()).isLessThanOrEqualTo(afterStop + 1);
  }

  @Test
  void aRunThatFailsDoesNotStopTheNextOnes() {
    AtomicInteger runs = new AtomicInteger();
    PendingCommandScheduler scheduler =
        new PendingCommandScheduler(
            () ->
                runs.incrementAndGet() == 1
                    ? Mono.error(new IllegalStateException("database down"))
                    : Mono.just(0L),
            EVERY_20_MS);

    scheduler.start();
    try {
      await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 3);
    } finally {
      scheduler.stop();
    }
  }
}
