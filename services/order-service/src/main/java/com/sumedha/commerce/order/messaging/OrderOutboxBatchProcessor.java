package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.order.config.OrderOutboxProperties;
import com.sumedha.commerce.order.entity.OrderOutboxEvent;
import com.sumedha.commerce.order.metrics.OrderCompensationMetrics;
import com.sumedha.commerce.order.repository.OrderOutboxEventRepository;
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
 * Publishes claimed outbox rows to Kafka and records the outcome.
 *
 * <p>Mirrors payment-service's processor, because the failure modes are identical. The two rules
 * that matter: a row becomes {@code PUBLISHED} only after the broker acknowledges it, and a row
 * that fails keeps its {@code eventId} and payload untouched so the retry is byte-identical.
 *
 * <p>This is deliberately the <em>only</em> place a Kafka call happens for compensation. The
 * business transaction that cancels an order never talks to a broker.
 */
@Component
public class OrderOutboxBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderOutboxBatchProcessor.class);
    private static final int LAST_ERROR_MAX_LENGTH = 2_000;

    private final OrderOutboxEventRepository outboxEvents;
    private final OrderEventPublisher eventPublisher;
    private final OrderOutboxProperties properties;
    private final OrderCompensationMetrics metrics;
    private final Clock clock;

    @Autowired
    public OrderOutboxBatchProcessor(OrderOutboxEventRepository outboxEvents,
                                     OrderEventPublisher eventPublisher,
                                     OrderOutboxProperties properties,
                                     OrderCompensationMetrics metrics) {
        this(outboxEvents, eventPublisher, properties, metrics, Clock.systemUTC());
    }

    OrderOutboxBatchProcessor(OrderOutboxEventRepository outboxEvents,
                              OrderEventPublisher eventPublisher,
                              OrderOutboxProperties properties,
                              OrderCompensationMetrics metrics,
                              Clock clock) {
        this.outboxEvents = outboxEvents;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Locks a bounded PostgreSQL batch with SKIP LOCKED and holds those row locks until the
     * bounded Kafka acknowledgement waits and the status updates commit.
     *
     * <p>An interrupt ends the cycle early: the interrupted row is recorded as a failed attempt
     * like any other, and the rows after it are left exactly as claimed - still {@code PENDING},
     * {@code attempt_count} untouched, no backoff imposed - so a shutdown does not consume retry
     * budget for work that was never attempted.
     *
     * @return how many rows were actually attempted, which is the batch size unless interrupted
     */
    @Transactional
    public int publishNextBatch() {
        List<OrderOutboxEvent> batch = outboxEvents.lockNextBatch(properties.batchSize());
        int attempted = 0;
        for (OrderOutboxEvent event : batch) {
            attempted++;
            if (!publish(event)) {
                log.warn("Compensation outbox publishing interrupted; {} of {} claimed row(s) left untouched and retryable",
                        batch.size() - attempted, batch.size());
                break;
            }
        }
        return attempted;
    }

    /** @return {@code false} if this thread was interrupted, meaning the batch must stop here. */
    private boolean publish(OrderOutboxEvent event) {
        int attempt = event.getAttemptCount() + 1;
        try {
            SendResult<String, String> result = eventPublisher
                    .publish(event.getTopic(), event.getEventKey(), event.getPayload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished(clock.instant());
            metrics.publishSucceeded();
            log.info("Compensation publish succeeded eventId={} eventType={} orderId={} attempt={} topic={} partition={} offset={}",
                    event.getEventId(), event.getEventType(), event.getAggregateId(), attempt,
                    result.getRecordMetadata().topic(), result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            return true;
        } catch (Exception failure) {
            Duration backoff = backoffFor(attempt);
            event.markAttemptFailed(errorMessage(failure), clock.instant().plus(backoff));
            metrics.publishFailed();
            log.warn("Compensation publish failed; retained for retry eventId={} eventType={} orderId={} attempt={} nextAttemptIn={}",
                    event.getEventId(), event.getEventType(), event.getAggregateId(), attempt, backoff, failure);
            if (failure instanceof InterruptedException) {
                // Restore the flag for whoever is shutting this thread down, then stop the batch.
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
