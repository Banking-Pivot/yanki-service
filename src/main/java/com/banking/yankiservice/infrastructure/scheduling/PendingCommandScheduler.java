package com.banking.yankiservice.infrastructure.scheduling;

import com.banking.yankiservice.application.port.in.ResendPendingCommandsUseCase;
import com.banking.yankiservice.infrastructure.config.YankiProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs the re-send of unanswered commands every {@code yanki.messaging.resend-interval}, from the
 * moment the service is ready until it stops.
 *
 * <p>Runs never overlap: a tick that arrives while the previous run is still working is dropped. A
 * run that fails is logged and the next tick tries again.
 */
@Component
public class PendingCommandScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(PendingCommandScheduler.class);

  private final ResendPendingCommandsUseCase resend;
  private final Duration interval;
  private Disposable running;

  /** Creates the scheduler with the use case to run and the interval of the settings. */
  public PendingCommandScheduler(ResendPendingCommandsUseCase resend, YankiProperties properties) {
    this.resend = resend;
    this.interval = properties.messaging().resendInterval();
  }

  /** Starts the periodic runs once the service is ready to work. */
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    if (running == null) {
      running =
          Flux.interval(interval).onBackpressureDrop().concatMap(tick -> runOnce(), 1).subscribe();
    }
  }

  /** Stops the periodic runs when the service shuts down. */
  @PreDestroy
  public synchronized void stop() {
    if (running != null) {
      running.dispose();
      running = null;
    }
  }

  private Mono<Long> runOnce() {
    return resend
        .execute()
        .doOnNext(
            sent -> {
              if (sent > 0) {
                LOG.info("Commands sent again. count={}", sent);
              }
            })
        .onErrorResume(
            failure -> {
              LOG.warn(
                  "The re-send of unanswered commands failed, the next run tries again. "
                      + "failureType={}",
                  failure.getClass().getName());
              return Mono.empty();
            });
  }
}
