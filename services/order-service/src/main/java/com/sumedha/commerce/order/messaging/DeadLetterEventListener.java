package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.order.entity.DeadLetterEvent;
import com.sumedha.commerce.order.metrics.DeadLetterMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Captures {@code payment.events.v1.DLT} into durable, queryable inspection rows.
 *
 * <p>Runs in its own consumer group on its own container factory, so it is completely separate
 * from the business listener: it never calls {@link PaymentEventProcessor}, never transitions an
 * order, and its own failures are never republished to the dead-letter topic (which would be a
 * self-feeding loop).
 *
 * <p>Duplicate-safe and restart-safe. Offsets commit only after the row is committed
 * (ack mode {@code RECORD}), so a crash in between redelivers the record - and the
 * {@code (dlt_topic, dlt_partition, dlt_offset)} uniqueness turns that into a no-op.
 */
@Component
public class DeadLetterEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterEventListener.class);

    private final DeadLetterRecorder recorder;
    private final DeadLetterMetrics metrics;

    public DeadLetterEventListener(DeadLetterRecorder recorder, DeadLetterMetrics metrics) {
        this.recorder = recorder;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_EVENTS_V1_DLT,
            groupId = "${order.dlt.consumer-group:order-service-dlt}",
            containerFactory = "deadLetterKafkaListenerContainerFactory")
    public void onDeadLetterRecord(ConsumerRecord<String, String> record) {
        DeadLetterEvent captured;
        try {
            captured = recorder.record(record);
        } catch (DataIntegrityViolationException alreadyCaptured) {
            // Lost a race against a concurrent capture of the same coordinate; the row exists.
            logDuplicate(record);
            return;
        }

        if (captured == null) {
            logDuplicate(record);
            return;
        }

        metrics.captured();
        // Payload is deliberately absent: it is operator-visible through the admin API instead.
        log.warn("DLT record captured id={} eventId={} eventType={} orderId={} originalTopic={} "
                        + "originalPartition={} originalOffset={} dlt={}-{}@{} exception={}",
                captured.getId(), captured.getEventId(), captured.getEventType(), captured.getOrderId(),
                captured.getOriginalTopic(), captured.getOriginalPartition(), captured.getOriginalOffset(),
                captured.getDltTopic(), captured.getDltPartition(), captured.getDltOffset(),
                captured.getExceptionClass());
    }

    private void logDuplicate(ConsumerRecord<String, String> record) {
        metrics.duplicateIgnored();
        log.debug("DLT record already captured; ignoring redelivery of {}-{}@{}",
                record.topic(), record.partition(), record.offset());
    }
}
