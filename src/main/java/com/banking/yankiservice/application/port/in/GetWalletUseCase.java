package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.domain.model.Wallet;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Reads one wallet. */
public interface GetWalletUseCase {

  Mono<Wallet> execute(UUID walletId);
}
