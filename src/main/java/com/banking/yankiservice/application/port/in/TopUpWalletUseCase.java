package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.application.command.TopUpWalletCommand;
import com.banking.yankiservice.application.model.RecordedTopUp;
import reactor.core.publisher.Mono;

/** Loads money into the balance of a wallet, once per operationId. */
public interface TopUpWalletUseCase {

  Mono<RecordedTopUp> execute(TopUpWalletCommand command);
}
