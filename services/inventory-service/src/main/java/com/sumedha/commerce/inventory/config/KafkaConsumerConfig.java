package com.sumedha.commerce.inventory.config;

import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.inventory.messaging.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer topology for {@code order.compensation.v1}.
 *
 * <p>Mirrors order-service's payment-event topology, because the requirements are the same and
 * two consumers in one platform behaving differently under failure would be a maintenance trap.
 *
 * <p>Values are consumed as plain {@code String} with {@link StringDeserializer}: the JSON is
 * parsed by our own code, so no {@code __TypeId__} header, default typing, or class-name driven
 * deserialization is ever involved. A poison-pill record is therefore an ordinary application
 * error routed to the dead-letter topic - it cannot stall the partition inside a deserializer.
 *
 * <p>Beans are declared with concrete {@code <String, String>} generics because Spring Boot's
 * auto-configured {@code ConsumerFactory<?, ?>} / {@code KafkaTemplate<?, ?>} will not autowire
 * into typed dependencies. All tuning still comes from {@code spring.kafka.*}.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** Initial delivery plus 2 retries = 3 attempts, then dead-letter. Never unbounded. */
    static final long RETRY_INTERVAL_MS = 1_000L;
    static final long MAX_RETRIES = 2L;

    /** Lets Kafka choose the DLT partition from the (preserved) key rather than copying the index. */
    private static final int PARTITION_BY_KEY = -1;

    @Bean
    ConsumerFactory<String, String> compensationConsumerFactory(
            KafkaProperties properties, KafkaConnectionDetails connectionDetails) {
        Map<String, Object> config = new HashMap<>(properties.buildConsumerProperties());
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getBootstrapServers());
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), new StringDeserializer());
    }

    /**
     * Producer used only by the dead-letter recoverer. inventory-service publishes no domain
     * events of its own; String serializers round-trip the original key and value bytes unchanged.
     */
    @Bean
    ProducerFactory<String, String> deadLetterProducerFactory(
            KafkaProperties properties, KafkaConnectionDetails connectionDetails) {
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getBootstrapServers());
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer());
    }

    @Bean
    KafkaTemplate<String, String> deadLetterKafkaTemplate(ProducerFactory<String, String> deadLetterProducerFactory) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }

    @Bean
    CommonErrorHandler compensationErrorHandler(KafkaTemplate<String, String> deadLetterKafkaTemplate) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(KafkaTopics.ORDER_COMPENSATION_V1_DLT, PARTITION_BY_KEY));

        ConsumerRecordRecoverer loggingDeadLetter = (record, exception) -> {
            log.error("Dead-lettering compensation record from {}-{}@{} (key={}) to {}: {}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    KafkaTopics.ORDER_COMPENSATION_V1_DLT, exception.toString(), exception);
            deadLetter.accept(record, exception);
        };

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                loggingDeadLetter, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
        // A record that can never succeed must not burn the retry budget - dead-letter it at once.
        // This matters more here than for payments: compensation queued behind a poison record is
        // stock that stays held.
        errorHandler.addNotRetryableExceptions(NonRetryableEventException.class);
        return errorHandler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> compensationConsumerFactory,
            CommonErrorHandler compensationErrorHandler,
            KafkaProperties properties) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(compensationConsumerFactory);
        factory.setCommonErrorHandler(compensationErrorHandler);

        KafkaProperties.Listener listener = properties.getListener();
        factory.setAutoStartup(listener.isAutoStartup());
        if (listener.getConcurrency() != null) {
            factory.setConcurrency(listener.getConcurrency());
        }
        if (listener.getAckMode() != null) {
            factory.getContainerProperties().setAckMode(listener.getAckMode());
        }
        // Consumer spans continuing the producer's W3C traceparent.
        factory.getContainerProperties().setObservationEnabled(listener.isObservationEnabled());
        return factory;
    }
}
