package com.sumedha.commerce.order.service;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.InternalServerException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.order.config.DeadLetterReplayProperties;
import com.sumedha.commerce.order.dto.response.DeadLetterEventDetailResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterEventResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterReplayResponse;
import com.sumedha.commerce.order.entity.DeadLetterEvent;
import com.sumedha.commerce.order.enums.DeadLetterStatus;
import com.sumedha.commerce.order.mapper.DeadLetterEventMapper;
import com.sumedha.commerce.order.messaging.NonRetryableEventException;
import com.sumedha.commerce.order.messaging.PaymentEventParser;
import com.sumedha.commerce.order.metrics.DeadLetterMetrics;
import com.sumedha.commerce.order.repository.DeadLetterEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Read-only inspection plus controlled replay of captured dead-letter records.
 *
 * <p><strong>What replay is.</strong> It reads the stored original value and key and republishes
 * them, byte-for-byte, to the record's own stored original topic. It does not regenerate the
 * {@code eventId}, does not rewrite the payload, and does not touch order state. The record is
 * marked {@code REPLAYED} only after the broker acknowledges the send.
 *
 * <p><strong>What replay is not.</strong> It is not a fix. Consumer-side deduplication remains
 * authoritative: an {@code eventId} already in {@code processed_event} will be recognised as a
 * duplicate and ignored. And if the condition that dead-lettered the event still holds - the
 * order has moved on, the payload is semantically impossible - the replayed record will simply be
 * dead-lettered again, producing a second inspection row. Both outcomes are correct.
 */
@Service
public class DeadLetterAdminService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAdminService.class);

    /**
     * The only topics an operator may republish to. The client never supplies a topic - it comes
     * from the stored record - and this is the second gate on that value, so a row written by a
     * future ingestion path still cannot turn the replay endpoint into an arbitrary producer.
     */
    private static final Set<String> REPLAYABLE_TOPICS = Set.of(KafkaTopics.PAYMENT_EVENTS_V1);

    static final int MAX_PAGE_SIZE = 100;

    private final DeadLetterEventRepository deadLetterEvents;
    private final PaymentEventParser parser;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DeadLetterReplayProperties properties;
    private final DeadLetterMetrics metrics;

    public DeadLetterAdminService(DeadLetterEventRepository deadLetterEvents,
                                  PaymentEventParser parser,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  DeadLetterReplayProperties properties,
                                  DeadLetterMetrics metrics) {
        this.deadLetterEvents = deadLetterEvents;
        this.parser = parser;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public PageResponse<DeadLetterEventResponse> list(UUID eventId, String eventType, UUID orderId,
                                                      Integer partition, DeadLetterStatus status,
                                                      int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }

        // Newest first, with id as a stable tie-breaker so pages cannot repeat or skip rows.
        Page<DeadLetterEvent> found = deadLetterEvents.search(eventId, blankToNull(eventType), orderId,
                partition, status,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("firstSeenAt"), Sort.Order.asc("id"))));

        List<DeadLetterEventResponse> items = found.getContent().stream()
                .map(DeadLetterEventMapper::toResponse)
                .toList();
        return PageResponse.of(items, page, size, found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public DeadLetterEventDetailResponse getById(UUID id) {
        return DeadLetterEventMapper.toDetailResponse(require(id));
    }

    /**
     * Republishes one stored record to its original topic and waits for the acknowledgement.
     *
     * <p>Each status write runs in its own transaction through Spring Data's {@code save}, so no
     * database transaction is held open across the Kafka round trip.
     */
    public DeadLetterReplayResponse replay(UUID id) {
        DeadLetterEvent event = require(id);
        log.info("DLT replay requested id={} eventId={} eventType={} originalTopic={} "
                        + "dlt={}-{}@{} previousReplayCount={}",
                event.getId(), event.getEventId(), event.getEventType(), event.getOriginalTopic(),
                event.getDltTopic(), event.getDltPartition(), event.getDltOffset(), event.getReplayCount());

        String topic = replayTopicOf(event);
        validatePayload(event);

        SendResult<String, String> ack;
        try {
            ack = kafkaTemplate.send(topic, event.getEventKey(), event.getPayload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            event.markReplayFailed(reason);
            deadLetterEvents.save(event);
            metrics.replayFailed();
            log.error("DLT replay failed id={} eventId={} eventType={} originalTopic={} "
                            + "dlt={}-{}@{} replayCount={}",
                    event.getId(), event.getEventId(), event.getEventType(), topic,
                    event.getDltTopic(), event.getDltPartition(), event.getDltOffset(),
                    event.getReplayCount(), failure);
            throw new InternalServerException("Replay of dead-letter record " + id
                    + " was not acknowledged by Kafka; the record is unchanged and can be replayed again");
        }

        event.markReplayed(Instant.now());
        deadLetterEvents.save(event);
        metrics.replaySucceeded();
        log.info("DLT replay succeeded id={} eventId={} eventType={} republishedTo={}-{}@{} replayCount={}",
                event.getId(), event.getEventId(), event.getEventType(),
                ack.getRecordMetadata().topic(), ack.getRecordMetadata().partition(),
                ack.getRecordMetadata().offset(), event.getReplayCount());

        return new DeadLetterReplayResponse(event.getId(), event.getEventId(),
                ack.getRecordMetadata().topic(), event.getEventKey(),
                ack.getRecordMetadata().partition(), ack.getRecordMetadata().offset(),
                event.getStatus(), event.getReplayedAt(), event.getReplayCount());
    }

    private DeadLetterEvent require(UUID id) {
        return deadLetterEvents.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dead-letter record not found: " + id));
    }

    private static String replayTopicOf(DeadLetterEvent event) {
        String topic = event.getOriginalTopic();
        if (topic == null || !REPLAYABLE_TOPICS.contains(topic)) {
            throw new BadRequestException("Dead-letter record " + event.getId()
                    + " has original topic '" + topic + "', which is not replayable");
        }
        return topic;
    }

    /** A payload the consumer provably cannot read must not be put back on the topic. */
    private void validatePayload(DeadLetterEvent event) {
        try {
            parser.parse(event.getPayload());
        } catch (NonRetryableEventException unreadable) {
            throw new BadRequestException("Dead-letter record " + event.getId()
                    + " does not hold a replayable v1 payment event: " + unreadable.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
