package com.sumedha.commerce.payment.messaging;

import com.sumedha.commerce.payment.config.PaymentOutboxProperties;
import com.sumedha.commerce.payment.entity.PaymentOutboxEvent;
import com.sumedha.commerce.payment.repository.PaymentOutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class PaymentOutboxBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxBatchProcessor.class);
    private static final int LAST_ERROR_MAX_LENGTH = 2_000;

    private final PaymentOutboxEventRepository outboxEvents;
    private final PaymentEventPublisher eventPublisher;
    private final PaymentOutboxProperties properties;
    private final Clock clock;

    @Autowired
    public PaymentOutboxBatchProcessor(PaymentOutboxEventRepository outboxEvents,
                                       PaymentEventPublisher eventPublisher,
                                       PaymentOutboxProperties properties) {
        this(outboxEvents, eventPublisher, properties, Clock.systemUTC());
    }

    PaymentOutboxBatchProcessor(PaymentOutboxEventRepository outboxEvents,
                                PaymentEventPublisher eventPublisher,
                                PaymentOutboxProperties properties,
                                Clock clock) {
        this.outboxEvents = outboxEvents;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Locks a bounded PostgreSQL batch with SKIP LOCKED and retains those row locks until
     * the corresponding bounded Kafka acknowledgement waits and status updates commit.
     *
     * <p>An interrupt ends the cycle early: the interrupted row is recorded as a failed attempt
     * like any other, and the rows after it are left exactly as claimed - still {@code PENDING},
     * {@code attempt_count} untouched, no backoff imposed - so the shutdown does not consume
     * retry budget for work that was never attempted. Returning normally still commits the
     * transaction, which releases every row lock in the batch.
     *
     * @return how many rows were actually attempted, which is the batch size unless interrupted
     */
    @Transactional
    public int publishNextBatch() {
        List<PaymentOutboxEvent> batch = outboxEvents.lockNextBatch(properties.batchSize());
        int attempted = 0;
        for (PaymentOutboxEvent event : batch) {
            attempted++;
            if (!publish(event)) {
                log.warn("Outbox publishing interrupted; {} of {} claimed row(s) left untouched and retryable",
                        batch.size() - attempted, batch.size());
                break;
            }
        }
        return attempted;
    }

    /** @return {@code false} if this thread was interrupted, meaning the batch must stop here. */
    private boolean publish(PaymentOutboxEvent event) {
        int attempt = event.getAttemptCount() + 1;
        try {
            SendResult<String, String> result = eventPublisher
                    .publish(event.getTopic(), event.getEventKey(), event.getPayload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished(clock.instant());
            log.info("Outbox publish succeeded eventId={} eventType={} aggregateId={} attempt={} topic={} partition={} offset={}",
                    event.getEventId(), event.getEventType(), event.getAggregateId(), attempt,
                    result.getRecordMetadata().topic(), result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            return true;
        } catch (Exception failure) {
            Duration backoff = backoffFor(attempt);
            event.markAttemptFailed(errorMessage(failure), clock.instant().plus(backoff));
            log.warn("Outbox publish failed; retained for retry eventId={} eventType={} aggregateId={} attempt={} nextAttemptIn={}",
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
