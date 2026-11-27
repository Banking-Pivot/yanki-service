package com.banking.yankiservice.application.handler.query;

import static com.banking.yankiservice.support.Fixtures.ANA_ID;
import static com.banking.yankiservice.support.Fixtures.BETO;
import static com.banking.yankiservice.support.Fixtures.BETO_ID;
import static com.banking.yankiservice.support.Fixtures.START;
import static com.banking.yankiservice.support.Fixtures.ana;
import static com.banking.yankiservice.support.Fixtures.beto;
import static com.banking.yankiservice.support.Fixtures.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.banking.yankiservice.application.exception.PaymentNotFoundException;
import com.banking.yankiservice.application.exception.WalletNotFoundException;
import com.banking.yankiservice.application.model.ResultPage;
import com.banking.yankiservice.application.port.out.MovementStorePort;
import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.application.query.ListMovementsQuery;
import com.banking.yankiservice.application.query.ListWalletsQuery;
import com.banking.yankiservice.application.query.PageRequest;
import com.banking.yankiservice.domain.model.Payment;
import com.banking.yankiservice.domain.model.Wallet;
import com.banking.yankiservice.domain.model.WalletMovement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class QueryHandlersTest {

  private static final PageRequest FIRST_PAGE = PageRequest.of(null, null);

  private final WalletStorePort wallets = mock(WalletStorePort.class);
  private final PaymentStorePort payments = mock(PaymentStorePort.class);
  private final MovementStorePort movements = mock(MovementStorePort.class);

  @Test
  void aWalletIsReadByItsId() {
    Wallet wallet = ana("10.00");
    when(wallets.findById(ANA_ID)).thenReturn(Mono.just(wallet));
    when(wallets.findById(BETO_ID)).thenReturn(Mono.empty());
    GetWalletHandler handler = new GetWalletHandler(wallets);

    assertThat(handler.execute(ANA_ID).block()).isSameAs(wallet);
    assertThatThrownBy(() -> handler.execute(BETO_ID).block())
        .isInstanceOf(WalletNotFoundException.class);
  }

  @Test
  void walletsAreListedWithTheFilterAsItCame() {
    ListWalletsQuery query = new ListWalletsQuery(FIRST_PAGE, BETO);
    ResultPage<Wallet> page = ResultPage.of(List.of(beto("0.00")), FIRST_PAGE, 1);
    when(wallets.findPage(query)).thenReturn(Mono.just(page));

    assertThat(new ListWalletsHandler(wallets).execute(query).block()).isSameAs(page);
  }

  @Test
  void theMovementsOfAWalletNeedTheWalletToExist() {
    Wallet wallet = ana("10.00");
    WalletMovement movement =
        WalletMovement.topUp(UUID.randomUUID(), wallet, money("10.00"), UUID.randomUUID(), START);
    ListMovementsQuery known = new ListMovementsQuery(ANA_ID, FIRST_PAGE);
    ListMovementsQuery unknown = new ListMovementsQuery(BETO_ID, FIRST_PAGE);
    ResultPage<WalletMovement> page = ResultPage.of(List.of(movement), FIRST_PAGE, 1);
    when(wallets.findById(ANA_ID)).thenReturn(Mono.just(wallet));
    when(wallets.findById(BETO_ID)).thenReturn(Mono.empty());
    when(movements.findPage(known)).thenReturn(Mono.just(page));
    ListMovementsHandler handler = new ListMovementsHandler(wallets, movements);

    assertThat(handler.execute(known).block()).isSameAs(page);
    assertThatThrownBy(() -> handler.execute(unknown).block())
        .isInstanceOf(WalletNotFoundException.class);
  }

  @Test
  void aPaymentIsReadByItsId() {
    UUID id = UUID.randomUUID();
    Payment payment =
        Payment.request(id, ana("50.00"), beto("0.00"), money("5.00"), null, "corr-1", START);
    when(payments.findById(id)).thenReturn(Mono.just(payment));
    when(payments.findById(ANA_ID)).thenReturn(Mono.empty());
    GetPaymentHandler handler = new GetPaymentHandler(payments);

    assertThat(handler.execute(id).block()).isSameAs(payment);
    assertThatThrownBy(() -> handler.execute(ANA_ID).block())
        .isInstanceOf(PaymentNotFoundException.class)
        .hasMessageContaining(ANA_ID.toString());
  }
}
