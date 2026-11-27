package com.banking.yankiservice.application.handler.query;

import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.port.in.ListWalletsUseCase;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.application.query.ListWalletsQuery;
import com.banking.yankiservice.domain.model.Wallet;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Lists wallets, newest first; with a mobile number, only the wallet of that number. */
public class ListWalletsHandler implements ListWalletsUseCase {

  private final WalletStorePort wallets;

  public ListWalletsHandler(WalletStorePort wallets) {
    this.wallets = Objects.requireNonNull(wallets);
  }

  @Override
  public Mono<ResultPage<Wallet>> execute(ListWalletsQuery query) {
    return wallets.findPage(query);
  }
}
