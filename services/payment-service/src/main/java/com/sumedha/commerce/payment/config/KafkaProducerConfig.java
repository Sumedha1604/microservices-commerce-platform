package com.sumedha.commerce.payment.config;

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
 * A concretely typed {@code <String, String>} producer.
 *
 * <p>Spring Boot's auto-configured template is declared as {@code KafkaTemplate<?, ?>}, which
 * will not autowire into a {@code KafkaTemplate<String, String>} dependency, so the factory and
 * template are declared here instead (Boot's {@code @ConditionalOnMissingBean} then backs off).
 * All tuning still comes from {@code spring.kafka.*} via {@link KafkaProperties}.
 *
 * <p>Key and value are both {@code String}: the value is the already-serialized
 * {@code EventEnvelope} JSON, so no serializer can inject Java class-name type headers.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    ProducerFactory<String, String> paymentProducerFactory(
            KafkaProperties properties, KafkaConnectionDetails connectionDetails) {
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getBootstrapServers());
        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new StringSerializer());
    }

    @Bean
    KafkaTemplate<String, String> paymentKafkaTemplate(
            ProducerFactory<String, String> paymentProducerFactory, KafkaProperties properties) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(paymentProducerFactory);
        // Producer spans + W3C traceparent injection (spring.kafka.template.observation-enabled).
        template.setObservationEnabled(properties.getTemplate().isObservationEnabled());
        return template;
    }
}
