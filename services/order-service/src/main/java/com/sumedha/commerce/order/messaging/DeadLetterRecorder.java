package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.order.entity.DeadLetterEvent;
import com.sumedha.commerce.order.repository.DeadLetterEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Turns one dead-lettered Kafka record into one durable inspection row.
 *
 * <p>Persistence only: it performs no order lookup and no order transition, so a dead-lettered
 * event can never reach {@link PaymentEventProcessor} through this path.
 */
@Component
public class DeadLetterRecorder {

    private static final int MESSAGE_MAX_LENGTH = 4_000;

    private final DeadLetterEventRepository deadLetterEvents;
    private final DeadLetterPayloadInspector inspector;

    public DeadLetterRecorder(DeadLetterEventRepository deadLetterEvents,
                              DeadLetterPayloadInspector inspector) {
        this.deadLetterEvents = deadLetterEvents;
        this.inspector = inspector;
    }

    /**
     * @return the stored record, or {@code null} when this DLT coordinate was already captured
     * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent capture won
     *         the race to the {@code (dlt_topic, dlt_partition, dlt_offset)} constraint
     */
    @Transactional
    public DeadLetterEvent record(ConsumerRecord<String, String> record) {
        if (deadLetterEvents.existsByDltTopicAndDltPartitionAndDltOffset(
                record.topic(), record.partition(), record.offset())) {
            return null;
        }

        Headers headers = record.headers();
        DeadLetterPayloadInspector.Metadata metadata = inspector.inspect(record.value());

        DeadLetterEvent event = DeadLetterEvent.builder()
                .eventId(metadata.eventId())
                .eventType(metadata.eventType())
                .schemaVersion(metadata.schemaVersion())
                .orderId(metadata.orderId())
                // Falls back to the DLT topic's own source convention when the header is absent.
                .originalTopic(defaultedOriginalTopic(DeadLetterHeaders.originalTopic(headers), record.topic()))
                .originalPartition(DeadLetterHeaders.originalPartition(headers))
                .originalOffset(DeadLetterHeaders.originalOffset(headers))
                .dltTopic(record.topic())
                .dltPartition(record.partition())
                .dltOffset(record.offset())
                .dltTimestamp(Instant.ofEpochMilli(record.timestamp()))
                .eventKey(record.key())
                .payload(record.value() == null ? "" : record.value())
                .exceptionClass(truncate(DeadLetterHeaders.exceptionClass(headers)))
                .exceptionMessage(truncate(DeadLetterHeaders.exceptionMessage(headers)))
                .consumerGroup(DeadLetterHeaders.consumerGroup(headers))
                .traceparent(DeadLetterHeaders.traceparent(headers))
                .firstSeenAt(Instant.now())
                .build();

        return deadLetterEvents.saveAndFlush(event);
    }

    /** {@code x.DLT} implies {@code x}; only used when the recoverer's header is missing. */
    private static String defaultedOriginalTopic(String fromHeader, String dltTopic) {
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        return dltTopic.endsWith(".DLT") ? dltTopic.substring(0, dltTopic.length() - 4) : dltTopic;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MESSAGE_MAX_LENGTH ? value : value.substring(0, MESSAGE_MAX_LENGTH);
    }
}
