package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.application.command.LinkCardCommand;
import com.banking.yankiservice.domain.model.Wallet;
import reactor.core.publisher.Mono;

/** Asks for a wallet to be linked to a debit card. */
public interface LinkCardUseCase {

  Mono<Wallet> execute(LinkCardCommand command);
}
