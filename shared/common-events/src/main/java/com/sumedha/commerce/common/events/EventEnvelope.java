package com.sumedha.commerce.common.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit transport envelope wrapping a single domain-event {@code payload}.
 *
 * <p>The wire format is a flat JSON object with fixed, lower-camelCase keys and no type
 * metadata:
 *
 * <pre>{@code
 * {
 *   "eventId":       "b7d1...-uuid",
 *   "eventType":     "PaymentAuthorized",
 *   "schemaVersion": 1,
 *   "occurredAt":    "2026-09-06T12:34:56.789Z",
 *   "payload":       { ...event-specific fields... }
 * }
 * }</pre>
 *
 * <p>Deserialization is done by naming the concrete payload type at the call site, e.g.
 * {@code mapper.readValue(json, new TypeReference<EventEnvelope<PaymentAuthorizedEvent>>() {})}
 * or {@code constructParametricType(EventEnvelope.class, PaymentAuthorizedEvent.class)}.
 * {@code eventType} tells a consumer which payload type to ask for. Jackson default typing
 * / class-name headers are never used.
 *
 * @param <T> the domain-event payload record type
 */
public record EventEnvelope<T>(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("payload") T payload
) {

    /** Schema version carried by every v1 envelope. */
    public static final int SCHEMA_VERSION_V1 = 1;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    /**
     * Wraps {@code payload} in a fresh v1 envelope with a random {@code eventId} and
     * {@code occurredAt} set to now.
     */
    public static <T> EventEnvelope<T> now(String eventType, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, SCHEMA_VERSION_V1, Instant.now(), payload);
    }
}
