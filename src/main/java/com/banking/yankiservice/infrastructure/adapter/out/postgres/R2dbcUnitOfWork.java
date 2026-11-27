package com.banking.yankiservice.infrastructure.adapter.out.postgres;

import com.banking.yankiservice.application.port.out.UnitOfWorkPort;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Reactive transaction on the R2DBC connection (READ COMMITTED, the default of PostgreSQL). Every
 * statement of the work runs on the same connection; an error signal rolls back, completion
 * commits.
 */
@Component
public class R2dbcUnitOfWork implements UnitOfWorkPort {

  private final TransactionalOperator operator;

  public R2dbcUnitOfWork(ReactiveTransactionManager transactionManager) {
    this.operator = TransactionalOperator.create(transactionManager);
  }

  @Override
  public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
    return operator.transactional(Mono.defer(work));
  }
}
