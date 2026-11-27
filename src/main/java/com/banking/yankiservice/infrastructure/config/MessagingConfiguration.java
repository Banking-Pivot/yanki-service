package com.banking.yankiservice.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka topics and what happens to an answer that cannot be handled.
 *
 * <p>The topics are declared here and in the services on the other side, with the same shape;
 * whichever starts first creates them. Three partitions let several instances share the work, and
 * the key of every message (the card, or the payment or link request it is about) keeps its
 * messages in one partition, so in order.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingConfiguration {

  static final int PARTITIONS = 3;
  static final long RETRIES = 3;

  @Bean
  NewTopic transactionCommandsTopic(YankiProperties properties) {
    return topic(properties.messaging().transactionCommandsTopic());
  }

  @Bean
  NewTopic cardCommandsTopic(YankiProperties properties) {
    return topic(properties.messaging().cardCommandsTopic());
  }

  @Bean
  NewTopic repliesTopic(YankiProperties properties) {
    return topic(properties.messaging().repliesTopic());
  }

  /**
   * An answer that fails to be handled (the database is down, for example) is tried three more
   * times and then skipped with an error in the log. Skipping loses nothing: the payment or the
   * card link keeps waiting, its request is sent again and the other service answers again.
   */
  @Bean
  CommonErrorHandler replyErrorHandler(YankiProperties properties) {
    return new DefaultErrorHandler(
        new FixedBackOff(properties.messaging().retryInterval().toMillis(), RETRIES));
  }

  private static NewTopic topic(String name) {
    return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
  }
}
