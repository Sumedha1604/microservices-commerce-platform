package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the raw record value into a validated {@link PaymentEvent}.
 *
 * <p>The envelope is bound to {@code EventEnvelope<JsonNode>} - the shared v1 contract - which
 * validates {@code eventId}, {@code eventType}, {@code occurredAt} and {@code payload} on the way
 * in and leaves the payload as an unbound subtree. The payload is then bound to an explicitly
 * named record chosen by the {@code eventType} string. No {@code __TypeId__} header, no default
 * typing, no Java class name ever comes off the wire.
 *
 * <p>Every rejection is a {@link NonRetryableEventException}: a record this parser cannot read
 * will never become readable, so it must go to the dead-letter topic rather than be retried.
 */
@Component
public class PaymentEventParser {

    private static final TypeReference<EventEnvelope<JsonNode>> ENVELOPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public PaymentEvent parse(String value) {
        if (value == null || value.isBlank()) {
            throw new NonRetryableEventException("Record value is empty");
        }

        EventEnvelope<JsonNode> envelope;
        try {
            envelope = objectMapper.readValue(value, ENVELOPE);
        } catch (RuntimeException e) {
            throw new NonRetryableEventException("Record value is not a valid v1 event envelope", e);
        }

        if (envelope.schemaVersion() != EventEnvelope.SCHEMA_VERSION_V1) {
            throw new NonRetryableEventException("Unsupported schemaVersion " + envelope.schemaVersion()
                    + " for event " + envelope.eventId() + " (this consumer only understands v"
                    + EventEnvelope.SCHEMA_VERSION_V1 + ")");
        }

        return switch (envelope.eventType()) {
            case EventTypes.PAYMENT_AUTHORIZED -> new PaymentEvent.Authorized(
                    envelope.eventId(), payload(envelope, PaymentAuthorizedEvent.class));
            case EventTypes.PAYMENT_FAILED -> new PaymentEvent.Failed(
                    envelope.eventId(), payload(envelope, PaymentFailedEvent.class));
            default -> throw new NonRetryableEventException(
                    "Unsupported eventType '" + envelope.eventType() + "' for event " + envelope.eventId());
        };
    }

    private <T> T payload(EventEnvelope<JsonNode> envelope, Class<T> payloadType) {
        T payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), payloadType);
        } catch (RuntimeException e) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId()
                    + " does not match " + payloadType.getSimpleName(), e);
        }
        if (orderId(payload) == null) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId() + " has no orderId");
        }
        return payload;
    }

    private static Object orderId(Object payload) {
        return payload instanceof PaymentAuthorizedEvent authorized
                ? authorized.orderId()
                : ((PaymentFailedEvent) payload).orderId();
    }
}
