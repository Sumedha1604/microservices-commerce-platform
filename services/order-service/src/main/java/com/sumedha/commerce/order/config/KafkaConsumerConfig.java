package com.sumedha.commerce.order.config;

import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.order.messaging.NonRetryableEventException;
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
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer topology for {@code payment.events.v1}.
 *
 * <p>Values are consumed as plain {@code String} with {@link StringDeserializer}: the JSON is
 * parsed by our own code, so no {@code __TypeId__} header, default typing, or class-name driven
 * deserialization is ever involved. A poison-pill record is therefore an ordinary application
 * error routed to the dead-letter topic - it cannot stall the partition inside a deserializer.
 *
 * <p>Beans are declared with concrete {@code <String, String>} generics because Spring Boot's
 * auto-configured {@code ConsumerFactory<?, ?>} / {@code KafkaTemplate<?, ?>} will not autowire
 * into typed dependencies. Declaring them backs Boot's own definitions off; the container factory
 * keeps the conventional {@code kafkaListenerContainerFactory} name so Boot's is not created
 * alongside it. All tuning still comes from {@code spring.kafka.*}.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** Initial delivery plus 2 retries = 3 attempts, then dead-letter. Never unbounded. */
    static final long RETRY_INTERVAL_MS = 1_000L;
    static final long MAX_RETRIES = 2L;

    /** Lets Kafka choose the DLT partition from the (preserved) key rather than copying the index. */
    private static final int PARTITION_BY_KEY = -1;

    /** Bounded retry for DLT <em>ingestion</em> failures (a database blip), then log and skip. */
    static final long DLT_INGEST_RETRY_INTERVAL_MS = 1_000L;
    static final long DLT_INGEST_MAX_RETRIES = 3L;

    @Bean
    ConsumerFactory<String, String> paymentEventConsumerFactory(
            KafkaProperties properties, KafkaConnectionDetails connectionDetails) {
        Map<String, Object> config = new HashMap<>(properties.buildConsumerProperties());
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getBootstrapServers());
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), new StringDeserializer());
    }

    /**
     * Producer used only by the dead-letter recoverer. String serializers round-trip the original
     * key and value bytes unchanged.
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
    CommonErrorHandler paymentEventErrorHandler(KafkaTemplate<String, String> deadLetterKafkaTemplate) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(KafkaTopics.PAYMENT_EVENTS_V1_DLT, PARTITION_BY_KEY));

        ConsumerRecordRecoverer loggingDeadLetter = (record, exception) -> {
            log.error("Dead-lettering record from {}-{}@{} (key={}) to {}: {}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    KafkaTopics.PAYMENT_EVENTS_V1_DLT, exception.toString(), exception);
            deadLetter.accept(record, exception);
        };

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                loggingDeadLetter, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
        // A record that can never succeed must not burn the retry budget - dead-letter it at once.
        errorHandler.addNotRetryableExceptions(NonRetryableEventException.class);
        return errorHandler;
    }

    /**
     * Container factory for the DLT <em>inspection</em> listener.
     *
     * <p>Two things make it deliberately different from the business factory above:
     * <ul>
     *   <li><b>No dead-letter recoverer.</b> Its recoverer only logs, so a failure while capturing
     *       a dead-letter record can never be republished to the dead-letter topic - that would be
     *       a self-feeding loop.</li>
     *   <li><b>Single-threaded.</b> The DLT is low volume and ingestion is pure inserts; one
     *       consumer keeps offset handling and the duplicate check trivial to reason about.</li>
     * </ul>
     *
     * <p>After the bounded retries a record is logged at ERROR and skipped so the partition
     * cannot stall forever. A malformed payload never reaches that path at all - it is stored as
     * inspectable text rather than parsed.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> deadLetterKafkaListenerContainerFactory(
            ConsumerFactory<String, String> paymentEventConsumerFactory) {

        ConsumerRecordRecoverer logOnly = (record, exception) -> log.error(
                "Could not capture dead-letter record {}-{}@{} after {} attempts; skipping it so the "
                        + "DLT partition is not stalled. The record remains on the topic.",
                record.topic(), record.partition(), record.offset(), DLT_INGEST_MAX_RETRIES + 1, exception);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentEventConsumerFactory);
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(logOnly, new FixedBackOff(DLT_INGEST_RETRY_INTERVAL_MS, DLT_INGEST_MAX_RETRIES)));
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> paymentEventConsumerFactory,
            CommonErrorHandler paymentEventErrorHandler,
            KafkaProperties properties) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentEventConsumerFactory);
        factory.setCommonErrorHandler(paymentEventErrorHandler);

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
