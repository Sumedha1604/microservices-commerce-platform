package com.sumedha.commerce.product.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * A concretely typed {@code <String, String>} producer for the product outbox publisher.
 *
 * <p>Spring Boot's auto-configured template is {@code KafkaTemplate<?, ?>}, which will not autowire
 * into a typed dependency, so the factory and template are declared here and Boot's back off. All
 * tuning still comes from {@code spring.kafka.*}. The value is the already-serialized
 * {@code EventEnvelope} JSON, so no serializer can add Java type headers.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    ProducerFactory<String, String> productProducerFactory(
            KafkaProperties properties, KafkaConnectionDetails connectionDetails) {
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getBootstrapServers());
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer());
    }

    @Bean
    KafkaTemplate<String, String> productKafkaTemplate(
            ProducerFactory<String, String> productProducerFactory, KafkaProperties properties) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(productProducerFactory);
        // Producer spans + W3C traceparent injection (spring.kafka.template.observation-enabled).
        template.setObservationEnabled(properties.getTemplate().isObservationEnabled());
        return template;
    }
}
