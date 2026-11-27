package com.banking.yankiservice.application.port.in;

import java.util.UUID;
import reactor.core.publisher.Mono;

/** Closes a wallet. */
public interface CloseWalletUseCase {

  Mono<Void> execute(UUID walletId);
}
