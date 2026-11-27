package com.banking.yankiservice.application.port.in;

import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.query.ListWalletsQuery;
import com.banking.yankiservice.domain.model.Wallet;
import reactor.core.publisher.Mono;

/** Lists wallets, newest first. */
public interface ListWalletsUseCase {

  Mono<ResultPage<Wallet>> execute(ListWalletsQuery query);
}
