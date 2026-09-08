package com.sumedha.commerce.payment.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "payment.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PaymentOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxScheduler.class);
    private final PaymentOutboxBatchProcessor processor;

    public PaymentOutboxScheduler(PaymentOutboxBatchProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval:1s}")
    public void publishPendingEvents() {
        try {
            processor.publishNextBatch();
        } catch (RuntimeException failure) {
            log.error("Outbox polling cycle failed; pending events remain durable", failure);
        }
    }
}
