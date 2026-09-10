package com.sumedha.commerce.order.mapper;

import com.sumedha.commerce.order.dto.response.DeadLetterEventDetailResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterEventResponse;
import com.sumedha.commerce.order.entity.DeadLetterEvent;

public final class DeadLetterEventMapper {

    private DeadLetterEventMapper() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static DeadLetterEventResponse toResponse(DeadLetterEvent event) {
        return new DeadLetterEventResponse(
                event.getId(),
                event.getEventId(),
                event.getEventType(),
                event.getSchemaVersion(),
                event.getOrderId(),
                event.getOriginalTopic(),
                event.getOriginalPartition(),
                event.getOriginalOffset(),
                event.getDltTopic(),
                event.getDltPartition(),
                event.getDltOffset(),
                event.getDltTimestamp(),
                event.getEventKey(),
                event.getExceptionClass(),
                event.getExceptionMessage(),
                event.getConsumerGroup(),
                event.getTraceparent(),
                event.getFirstSeenAt(),
                event.getStatus(),
                event.getReplayedAt(),
                event.getReplayCount(),
                event.getLastReplayError());
    }

    public static DeadLetterEventDetailResponse toDetailResponse(DeadLetterEvent event) {
        return new DeadLetterEventDetailResponse(toResponse(event), event.getPayload());
    }
}
