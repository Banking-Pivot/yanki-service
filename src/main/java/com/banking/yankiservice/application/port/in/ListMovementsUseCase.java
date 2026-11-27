package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.query.ListMovementsQuery;
import com.banking.yankiservice.domain.model.WalletMovement;
import reactor.core.publisher.Mono;

/** Lists the movements of a wallet, newest first. */
public interface ListMovementsUseCase {

  Mono<ResultPage<WalletMovement>> execute(ListMovementsQuery query);
}
