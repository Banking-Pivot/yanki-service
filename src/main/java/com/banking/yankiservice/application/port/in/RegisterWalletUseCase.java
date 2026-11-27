package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.application.command.RegisterWalletCommand;
import com.banking.yankiservice.domain.model.Wallet;
import reactor.core.publisher.Mono;

/** Opens a wallet. */
public interface RegisterWalletUseCase {

  Mono<Wallet> execute(RegisterWalletCommand command);
}
