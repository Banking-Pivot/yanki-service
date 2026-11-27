package com.banking.yankiservice.application.port.out;

import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Runs several writes so that all of them happen or none does. */
public interface UnitOfWorkPort {

  /**
   * Runs the work in one database transaction.
   *
   * @param work the writes; an error undoes all of them
   * @return what the work returned, once everything is stored
   */
  <T> Mono<T> inTransaction(Supplier<Mono<T>> work);
}
