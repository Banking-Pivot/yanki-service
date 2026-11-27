package com.banking.yankiservice.infrastructure.adapter.out.postgres;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import com.banking.yankiservice.application.exception.MovementAlreadyExistsException;
import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.port.out.MovementStorePort;
import com.banking.yankiservice.application.query.ListMovementsQuery;
import com.banking.yankiservice.domain.model.WalletMovement;
import com.banking.yankiservice.domain.model.enums.MovementType;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Keeps the movements of the wallets in PostgreSQL through Spring Data, with no SQL written by
 * hand. The table accepts one movement of each type per wallet and operation, which is what makes a
 * top-up sent twice count once.
 */
@Component
public class PostgresMovementAdapter implements MovementStorePort {

  private static final String OPERATION_KEY = "uk_wallet_movements_operation";

  private final R2dbcEntityTemplate template;

  public PostgresMovementAdapter(R2dbcEntityTemplate template) {
    this.template = template;
  }

  @Override
  public Mono<Void> insert(WalletMovement movement) {
    return template
        .insert(MovementRow.of(movement))
        .then()
        .onErrorMap(
            error -> PostgresErrors.isUniqueViolation(error, OPERATION_KEY),
            error -> new MovementAlreadyExistsException(movement.operationId(), error));
  }

  @Override
  public Mono<WalletMovement> findByOperation(UUID walletId, UUID operationId, MovementType type) {
    return template
        .selectOne(
            query(
                where("walletId")
                    .is(walletId)
                    .and("operationId")
                    .is(operationId)
                    .and("movementType")
                    .is(type.name())),
            MovementRow.class)
        .map(MovementRow::toMovement);
  }

  @Override
  public Mono<ResultPage<WalletMovement>> findPage(ListMovementsQuery request) {
    Criteria ofWallet = where("walletId").is(request.walletId());
    return Mono.zip(
            template
                .select(
                    query(ofWallet)
                        .sort(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.asc("id")))
                        .offset(request.pageRequest().offset())
                        .limit(request.pageRequest().size()),
                    MovementRow.class)
                .map(MovementRow::toMovement)
                .collectList(),
            template.count(query(ofWallet), MovementRow.class))
        .map(page -> ResultPage.of(page.getT1(), request.pageRequest(), page.getT2()));
  }
}
