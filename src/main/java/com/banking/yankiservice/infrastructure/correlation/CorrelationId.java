package com.banking.yankiservice.infrastructure.correlation;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.server.ServerWebExchange;

/**
 * The identifier that follows one request through the API Gateway and every service it reaches, so
 * their log lines and error bodies can be matched. A payment or a card link keeps the identifier of
 * the request that started it and writes it in every message it sends.
 */
public final class CorrelationId {

  /** Header that carries the identifier, in HTTP requests and in Kafka messages. */
  public static final String HEADER = "X-Correlation-ID";

  /** Name of the identifier in the logs (MDC), in the Reactor Context and in the exchange. */
  public static final String KEY = "corrId";

  private static final Pattern USABLE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private CorrelationId() {}

  /**
   * The identifier to use for a request or a message.
   *
   * @param received value of the header, or null when the sender sent none
   * @return the received value when it is short and has no characters that could break a log line;
   *     otherwise a new random one
   */
  public static String accept(String received) {
    return received != null && USABLE.matcher(received).matches()
        ? received
        : UUID.randomUUID().toString();
  }

  /**
   * The identifier of the request being served.
   *
   * @param exchange the current request and response
   * @return the identifier {@link CorrelationIdFilter} stored, or a new one when the filter did not
   *     run
   */
  public static String of(ServerWebExchange exchange) {
    String stored = exchange.getAttribute(KEY);
    return stored != null ? stored : accept(null);
  }
}
