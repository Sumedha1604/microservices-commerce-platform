package com.sumedha.commerce.product.messaging;

import com.sumedha.commerce.product.config.ProductOutboxProperties;
import com.sumedha.commerce.product.entity.ProductOutboxEvent;
import com.sumedha.commerce.product.repository.ProductOutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes one bounded batch of product outbox rows.
 *
 * <p>Same rules as payment-service's publisher, deliberately: rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED} and stay locked until each bounded Kafka acknowledgement wait and
 * status update commits; a row becomes {@code PUBLISHED} only after the broker acknowledged it; a
 * failure leaves it {@code PENDING} with its {@code eventId} and payload untouched and an
 * exponential backoff. There is no Kafka call inside any product business transaction, and no
 * claim of exactly-once: a crash after the acknowledgement but before the commit republishes the
 * identical record, and consumers deduplicate by {@code eventId}.
 */
@Component
public class ProductOutboxBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(ProductOutboxBatchProcessor.class);
    private static final int LAST_ERROR_MAX_LENGTH = 2_000;

    private final ProductOutboxEventRepository outboxEvents;
    private final ProductEventPublisher eventPublisher;
    private final ProductOutboxProperties properties;
    private final Clock clock;

    @Autowired
    public ProductOutboxBatchProcessor(ProductOutboxEventRepository outboxEvents,
                                       ProductEventPublisher eventPublisher,
                                       ProductOutboxProperties properties) {
        this(outboxEvents, eventPublisher, properties, Clock.systemUTC());
    }

    ProductOutboxBatchProcessor(ProductOutboxEventRepository outboxEvents,
                                ProductEventPublisher eventPublisher,
                                ProductOutboxProperties properties,
                                Clock clock) {
        this.outboxEvents = outboxEvents;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * An interrupt ends the cycle early: the interrupted row counts as a failed attempt, and the
     * rows after it stay exactly as claimed so a shutdown does not burn their retry budget.
     *
     * @return how many rows were actually attempted
     */
    @Transactional
    public int publishNextBatch() {
        List<ProductOutboxEvent> batch = outboxEvents.lockNextBatch(properties.batchSize());
        int attempted = 0;
        for (ProductOutboxEvent event : batch) {
            attempted++;
            if (!publish(event)) {
                log.warn("Product outbox publishing interrupted; {} of {} claimed row(s) left untouched and retryable",
                        batch.size() - attempted, batch.size());
                break;
            }
        }
        return attempted;
    }

    /** @return {@code false} if this thread was interrupted, meaning the batch must stop here. */
    private boolean publish(ProductOutboxEvent event) {
        int attempt = event.getAttemptCount() + 1;
        try {
            SendResult<String, String> result = eventPublisher
                    .publish(event.getTopic(), event.getEventKey(), event.getPayload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished(clock.instant());
            log.info("Product outbox publish succeeded eventId={} eventType={} productId={} attempt={} topic={} partition={} offset={}",
                    event.getEventId(), event.getEventType(), event.getAggregateId(), attempt,
                    result.getRecordMetadata().topic(), result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            return true;
        } catch (Exception failure) {
            Duration backoff = backoffFor(attempt);
            event.markAttemptFailed(errorMessage(failure), clock.instant().plus(backoff));
            log.warn("Product outbox publish failed; retained for retry eventId={} eventType={} productId={} attempt={} nextAttemptIn={}",
                    event.getEventId(), event.getEventType(), event.getAggregateId(), attempt, backoff, failure);
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }
    }

    private Duration backoffFor(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 30);
        Duration candidate;
        try {
            candidate = properties.initialBackoff().multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            return properties.maxBackoff();
        }
        return candidate.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : candidate;
    }

    private static String errorMessage(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
        return message.length() <= LAST_ERROR_MAX_LENGTH ? message : message.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
