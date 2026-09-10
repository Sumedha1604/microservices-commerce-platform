package com.sumedha.commerce.order.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the compensation outbox. Disabled by {@code order.outbox.enabled=false} so tests can
 * drive {@link OrderOutboxBatchProcessor} by hand without a scheduler racing them.
 */
@Component
@ConditionalOnProperty(prefix = "order.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderOutboxScheduler.class);
    private final OrderOutboxBatchProcessor processor;

    public OrderOutboxScheduler(OrderOutboxBatchProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${order.outbox.poll-interval:1s}")
    public void publishPendingEvents() {
        try {
            processor.publishNextBatch();
        } catch (RuntimeException failure) {
            log.error("Compensation outbox polling cycle failed; pending events remain durable", failure);
        }
    }
}
