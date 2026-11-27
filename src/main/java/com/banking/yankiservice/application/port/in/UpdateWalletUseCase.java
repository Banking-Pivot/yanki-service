package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.application.command.UpdateWalletCommand;
import com.banking.yankiservice.domain.model.Wallet;
import reactor.core.publisher.Mono;

/** Updates the contact data of a wallet. */
public interface UpdateWalletUseCase {

  Mono<Wallet> execute(UpdateWalletCommand command);
}
