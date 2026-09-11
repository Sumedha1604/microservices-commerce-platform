package com.sumedha.commerce.product.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "product.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProductOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProductOutboxScheduler.class);
    private final ProductOutboxBatchProcessor processor;

    public ProductOutboxScheduler(ProductOutboxBatchProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${product.outbox.poll-interval:1s}")
    public void publishPendingEvents() {
        try {
            processor.publishNextBatch();
        } catch (RuntimeException failure) {
            log.error("Product outbox polling cycle failed; pending events remain durable", failure);
        }
    }
}
