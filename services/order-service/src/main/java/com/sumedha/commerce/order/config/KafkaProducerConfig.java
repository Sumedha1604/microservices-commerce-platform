package com.sumedha.commerce.order.config;

import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * The producer order-service uses to publish its own events.
 *
 * <p>Separate from the dead-letter template on purpose, for one reason: <b>observation</b>. This
 * template follows {@code spring.kafka.template.observation-enabled}, exactly as payment-service's
 * publisher does, so publishing a compensation event creates a producer span and stamps the W3C
 * {@code traceparent} that inventory-service's consumer then continues. The dead-letter template
 * deliberately keeps observation off, because a dead-letter republish must preserve the
 * <em>original</em> record's headers rather than stamp fresh trace context over them.
 *
 * <p>The underlying producer factory is shared: the wire format (String key, String value) is
 * identical, and only the template-level observation setting differs.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    KafkaTemplate<String, String> compensationKafkaTemplate(
            ProducerFactory<String, String> deadLetterProducerFactory, KafkaProperties properties) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(deadLetterProducerFactory);
        // Producer spans + W3C traceparent injection (spring.kafka.template.observation-enabled).
        template.setObservationEnabled(properties.getTemplate().isObservationEnabled());
        return template;
    }
}
