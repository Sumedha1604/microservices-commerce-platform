package com.sumedha.commerce.order.dto.response;

import com.sumedha.commerce.order.enums.DeadLetterStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Listing view of a captured dead-letter record. Carries every piece of metadata an operator
 * needs to triage, but deliberately <strong>not</strong> the raw payload - that is only served by
 * the detail endpoint.
 *
 * <p>{@code eventId}, {@code eventType}, {@code schemaVersion} and {@code orderId} are null when
 * the payload could not be parsed; the record is still listed and still inspectable.
 */
public record DeadLetterEventResponse(
        UUID id,
        UUID eventId,
        String eventType,
        Integer schemaVersion,
        UUID orderId,
        String originalTopic,
        Integer originalPartition,
        Long originalOffset,
        String dltTopic,
        int dltPartition,
        long dltOffset,
        Instant dltTimestamp,
        String eventKey,
        String exceptionClass,
        String exceptionMessage,
        String consumerGroup,
        String traceparent,
        Instant firstSeenAt,
        DeadLetterStatus status,
        Instant replayedAt,
        int replayCount,
        String lastReplayError
) {
}
