package com.sumedha.commerce.order.dto.response;

import com.sumedha.commerce.order.enums.DeadLetterStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of an acknowledged replay: where the original record was republished and what the
 * stored record looks like afterwards. {@code eventId} is the one carried by the stored payload -
 * replay never mints a new one.
 */
public record DeadLetterReplayResponse(
        UUID id,
        UUID eventId,
        String replayedToTopic,
        String eventKey,
        int partition,
        long offset,
        DeadLetterStatus status,
        Instant replayedAt,
        int replayCount
) {
}
