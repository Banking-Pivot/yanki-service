package com.banking.yankiservice.infrastructure.config;

import com.banking.yankiservice.application.handler.command.ApplyReplyHandler;
import com.banking.yankiservice.application.handler.command.CommandDispatcher;
import com.banking.yankiservice.application.handler.command.LinkCardHandler;
import com.banking.yankiservice.application.handler.command.RegisterWalletHandler;
import com.banking.yankiservice.application.handler.command.RequestPaymentHandler;
import com.banking.yankiservice.application.handler.command.ResendPendingCommandsHandler;
import com.banking.yankiservice.application.handler.command.TopUpWalletHandler;
import com.banking.yankiservice.application.handler.command.UnlinkCardHandler;
import com.banking.yankiservice.application.handler.query.GetPaymentHandler;
import com.banking.yankiservice.application.handler.query.GetWalletHandler;
import com.banking.yankiservice.application.handler.query.ListMovementsHandler;
import com.banking.yankiservice.application.handler.query.ListWalletsHandler;
import com.banking.yankiservice.application.port.in.ApplyReplyUseCase;
import com.banking.yankiservice.application.port.in.GetPaymentUseCase;
import com.banking.yankiservice.application.port.in.GetWalletUseCase;
import com.banking.yankiservice.application.port.in.LinkCardUseCase;
import com.banking.yankiservice.application.port.in.ListMovementsUseCase;
import com.banking.yankiservice.application.port.in.ListWalletsUseCase;
import com.banking.yankiservice.application.port.in.RegisterWalletUseCase;
import com.banking.yankiservice.application.port.in.RequestPaymentUseCase;
import com.banking.yankiservice.application.port.in.ResendPendingCommandsUseCase;
import com.banking.yankiservice.application.port.in.TopUpWalletUseCase;
import com.banking.yankiservice.application.port.in.UnlinkCardUseCase;
import com.banking.yankiservice.application.port.out.CommandPublisherPort;
import com.banking.yankiservice.application.port.out.MovementStorePort;
import com.banking.yankiservice.application.port.out.PaymentStorePort;
import com.banking.yankiservice.application.port.out.UnitOfWorkPort;
import com.banking.yankiservice.application.port.out.WalletStorePort;
import com.banking.yankiservice.domain.model.vo.PaymentLimits;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the use cases. The application and domain layers have no Spring annotations; this class is
 * where they meet their adapters.
 */
@Configuration
@EnableConfigurationProperties(YankiProperties.class)
public class YankiBeanConfiguration {

  /**
   * The clock of the service, in microseconds: the precision PostgreSQL keeps, so a time answered
   * when something is created is the same time read back later.
   */
  @Bean
  public Clock yankiClock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }

  @Bean
  public CommandDispatcher commandDispatcher(CommandPublisherPort publisher) {
    return new CommandDispatcher(publisher);
  }

  @Bean
  public RegisterWalletUseCase registerWalletUseCase(WalletStorePort wallets, Clock clock) {
    return new RegisterWalletHandler(wallets, clock, UUID::randomUUID);
  }

  @Bean
  public GetWalletUseCase getWalletUseCase(WalletStorePort wallets) {
    return new GetWalletHandler(wallets);
  }

  @Bean
  public ListWalletsUseCase listWalletsUseCase(WalletStorePort wallets) {
    return new ListWalletsHandler(wallets);
  }

  @Bean
  public TopUpWalletUseCase topUpWalletUseCase(
      WalletStorePort wallets,
      MovementStorePort movements,
      UnitOfWorkPort unitOfWork,
      Clock clock) {
    return new TopUpWalletHandler(wallets, movements, unitOfWork, clock, UUID::randomUUID);
  }

  @Bean
  public LinkCardUseCase linkCardUseCase(
      WalletStorePort wallets, CommandDispatcher dispatcher, Clock clock) {
    return new LinkCardHandler(wallets, dispatcher, clock, UUID::randomUUID);
  }

  @Bean
  public UnlinkCardUseCase unlinkCardUseCase(WalletStorePort wallets, Clock clock) {
    return new UnlinkCardHandler(wallets, clock);
  }

  @Bean
  public ListMovementsUseCase listMovementsUseCase(
      WalletStorePort wallets, MovementStorePort movements) {
    return new ListMovementsHandler(wallets, movements);
  }

  /**
   * The payment by mobile number, with everything it writes, the sender of MOVE_FUNDS and the
   * limits of the settings.
   */
  @Bean
  public RequestPaymentUseCase requestPaymentUseCase(
      WalletStorePort wallets,
      PaymentStorePort payments,
      MovementStorePort movements,
      UnitOfWorkPort unitOfWork,
      CommandDispatcher dispatcher,
      Clock clock,
      YankiProperties properties) {
    PaymentLimits limits =
        new PaymentLimits(
            properties.limits().paymentMax(),
            properties.limits().dailySentMax(),
            properties.businessZone());
    return new RequestPaymentHandler(
        wallets, payments, movements, unitOfWork, dispatcher, clock, UUID::randomUUID, limits);
  }

  @Bean
  public GetPaymentUseCase getPaymentUseCase(PaymentStorePort payments) {
    return new GetPaymentHandler(payments);
  }

  /** What applies the answers of Card Service and Transaction Service. */
  @Bean
  public ApplyReplyUseCase applyReplyUseCase(
      WalletStorePort wallets,
      PaymentStorePort payments,
      MovementStorePort movements,
      UnitOfWorkPort unitOfWork,
      Clock clock) {
    return new ApplyReplyHandler(wallets, payments, movements, unitOfWork, clock, UUID::randomUUID);
  }

  /** The re-send of unanswered requests, with the wait and the batch size of the settings. */
  @Bean
  public ResendPendingCommandsUseCase resendPendingCommandsUseCase(
      WalletStorePort wallets,
      PaymentStorePort payments,
      CommandDispatcher dispatcher,
      Clock clock,
      YankiProperties properties) {
    return new ResendPendingCommandsHandler(
        wallets,
        payments,
        dispatcher,
        clock,
        properties.messaging().resendAfter(),
        properties.messaging().resendBatchSize());
  }
}
