package com.banking.yankiservice.application.port.in;

import java.util.UUID;
import reactor.core.publisher.Mono;

/** Leaves a wallet without a debit card. */
public interface UnlinkCardUseCase {

  Mono<Void> execute(UUID walletId);
}
