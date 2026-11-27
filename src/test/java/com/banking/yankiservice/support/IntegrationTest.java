package com.banking.yankiservice.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.CardLinkStatus;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentResponse;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.PaymentStatus;
import com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model.WalletResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the whole service on a random port against a real PostgreSQL and a real Kafka in Docker.
 *
 * <p>The application keeps its real {@code spring.config.import}. A stub Config Server serves the
 * settings of {@code stub-config/yanki-service.yml} and the locations of the containers; a real
 * Vault holds the database user and password, which the Config Server never serves. All of them
 * start once, before any Spring context. {@link TestParticipants} plays Card Service and
 * Transaction Service on the same Kafka.
 *
 * <p>Every test starts with empty tables. The topics are not emptied: every test uses its own
 * payment and link IDs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public abstract class IntegrationTest {

  protected static final String ANA = "987654321";
  protected static final String BETO = "912345678";
  protected static final String DATABASE = "yanki_db";
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine").withDatabaseName(DATABASE);
  protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");
  protected static final StubConfigServer CONFIG_SERVER;
  protected static final TestVault VAULT;

  private static TestParticipants connected;

  static {
    POSTGRES.start();
    KAFKA.start();
    // Read by application.yaml through VAULT_URI and VAULT_APPROLE_FILE.
    VAULT =
        TestVault.start(
            "yanki-service",
            Map.of(
                "spring.r2dbc.username", POSTGRES.getUsername(),
                "spring.r2dbc.password", POSTGRES.getPassword(),
                "spring.flyway.user", POSTGRES.getUsername(),
                "spring.flyway.password", POSTGRES.getPassword()));
    Map<String, String> properties = new HashMap<>(yaml("stub-config/yanki-service.yml"));
    properties.put(
        "spring.r2dbc.url",
        "r2dbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + DATABASE);
    properties.put("spring.flyway.url", POSTGRES.getJdbcUrl());
    properties.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    // No registry in the tests: the service must not register with a real Eureka.
    properties.put("eureka.client.enabled", "false");
    CONFIG_SERVER = StubConfigServer.start(properties);
    // Read by application.yaml: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
    System.setProperty("CONFIG_SERVER_URL", CONFIG_SERVER.url());
  }

  @Autowired protected WebTestClient webTestClient;

  @Autowired protected DatabaseClient databaseClient;

  protected TestParticipants participants;

  @BeforeEach
  void startClean() {
    databaseClient.sql("TRUNCATE wallet_movements, payments, wallets").then().block();
    participants = participants();
    webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(15)).build();
  }

  // One connection for all the tests, opened when the first context is up and the topics exist.
  private static synchronized TestParticipants participants() {
    if (connected == null) {
      connected = TestParticipants.connect(KAFKA.getBootstrapServers());
    }
    return connected;
  }

  private static Map<String, String> yaml(String resource) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource(resource));
    Properties loaded = factory.getObject();
    Map<String, String> properties = new HashMap<>();
    loaded.forEach((key, value) -> properties.put(key.toString(), value.toString()));
    return properties;
  }

  // ----- wallets

  /** A valid request to open a wallet; tests change what they need. */
  protected static Map<String, Object> registration(String phoneNumber, String documentNumber) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("documentType", "DNI");
    body.put("documentNumber", documentNumber);
    body.put("phoneNumber", phoneNumber);
    body.put("imei", "356938035643809");
    body.put("email", "user" + phoneNumber + "@example.com");
    return body;
  }

  protected WebTestClient.ResponseSpec post(String uri, Object body, Object... uriVariables) {
    return webTestClient
        .post()
        .uri(uri, uriVariables)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  /** Opens the wallet of Ana and returns its ID. */
  protected UUID walletOfAna() {
    return registered(ANA, "45678912");
  }

  /** Opens the wallet of Beto and returns its ID. */
  protected UUID walletOfBeto() {
    return registered(BETO, "41234567");
  }

  protected UUID registered(String phoneNumber, String documentNumber) {
    return post("/api/v1/yanki/wallets", registration(phoneNumber, documentNumber))
        .expectStatus()
        .isCreated()
        .expectBody(WalletResponse.class)
        .returnResult()
        .getResponseBody()
        .getId();
  }

  protected WalletResponse wallet(UUID walletId) {
    return webTestClient
        .get()
        .uri("/api/v1/yanki/wallets/{id}", walletId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(WalletResponse.class)
        .returnResult()
        .getResponseBody();
  }

  protected BigDecimal balance(UUID walletId) {
    return wallet(walletId).getBalance();
  }

  protected WebTestClient.ResponseSpec topUp(UUID walletId, UUID operationId, String amount) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("operationId", operationId.toString());
    body.put("amount", new BigDecimal(amount));
    return post("/api/v1/yanki/wallets/{id}/top-ups", body, walletId);
  }

  /** Loads the amount into the wallet. */
  protected void funded(UUID walletId, String amount) {
    topUp(walletId, UUID.randomUUID(), amount).expectStatus().isCreated();
  }

  protected List<MovementResponse> movements(UUID walletId) {
    return webTestClient
        .get()
        .uri("/api/v1/yanki/wallets/{id}/movements", walletId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(
            com.banking.yankiservice.infrastructure.adapter.in.rest.generated.model
                .MovementPageResponse.class)
        .returnResult()
        .getResponseBody()
        .getItems();
  }

  // ----- link to a debit card

  protected WebTestClient.ResponseSpec linkCard(UUID walletId, UUID cardId) {
    return webTestClient
        .put()
        .uri("/api/v1/yanki/wallets/{id}/card", walletId)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("cardId", cardId.toString()))
        .exchange();
  }

  /** Asks for the link and returns the ID of the request, read from the RESOLVE_CARD command. */
  protected UUID requestedLink(UUID walletId, UUID cardId) {
    linkCard(walletId, cardId).expectStatus().isAccepted();
    return UUID.fromString(
        participants.awaitCommandAbout("RESOLVE_CARD", "cardId", cardId).field("transactionId"));
  }

  /** Card Service answers that the card exists and that its holder is the owner of the wallet. */
  protected void resolveCard(UUID walletId, UUID linkId, UUID cardId) {
    WalletResponse owner = wallet(walletId);
    participants.cardResolved(
        linkId, cardId, owner.getDocumentType().getValue(), owner.getDocumentNumber());
  }

  /** Links the wallet to a new card of its owner, with Card Service saying yes. */
  protected UUID linkedCard(UUID walletId) {
    UUID cardId = UUID.randomUUID();
    resolveCard(walletId, requestedLink(walletId, cardId), cardId);
    awaitCard(walletId, CardLinkStatus.LINKED);
    return cardId;
  }

  protected WalletResponse awaitCard(UUID walletId, CardLinkStatus status) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              WalletResponse wallet = wallet(walletId);
              assertThat(wallet.getCard()).isNotNull();
              assertThat(wallet.getCard().getStatus()).isEqualTo(status);
            });
    return wallet(walletId);
  }

  // ----- payments

  /** A valid request to pay 12.50 from Ana to Beto; tests change what they need. */
  protected static Map<String, Object> payment(UUID operationId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("operationId", operationId.toString());
    body.put("fromPhoneNumber", ANA);
    body.put("toPhoneNumber", BETO);
    body.put("amount", new BigDecimal("12.50"));
    body.put("description", "Lunch");
    return body;
  }

  protected WebTestClient.ResponseSpec requestPayment(Object body) {
    return post("/api/v1/yanki/payments", body);
  }

  /** Requests the valid payment and returns its ID. */
  protected UUID requestedPayment() {
    UUID operationId = UUID.randomUUID();
    requestPayment(payment(operationId)).expectStatus().isAccepted();
    return operationId;
  }

  protected PaymentResponse paymentOf(UUID paymentId) {
    return webTestClient
        .get()
        .uri("/api/v1/yanki/payments/{id}", paymentId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(PaymentResponse.class)
        .returnResult()
        .getResponseBody();
  }

  /** Waits until the payment reaches the status and returns it. */
  protected PaymentResponse awaitStatus(UUID paymentId, PaymentStatus status) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(paymentOf(paymentId).getStatus()).isEqualTo(status));
    return paymentOf(paymentId);
  }

  protected long count(String table) {
    return databaseClient
        .sql("SELECT COUNT(*) FROM " + table)
        .map(row -> row.get(0, Number.class).longValue())
        .one()
        .block();
  }
}
