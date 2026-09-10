package com.sumedha.commerce.inventory.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseLine;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseRequestedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the raw record value into a validated {@link InventoryReleaseCommand}.
 *
 * <p>The envelope binds to {@code EventEnvelope<JsonNode>} - the shared v1 contract - which
 * validates {@code eventId}, {@code eventType}, {@code occurredAt} and {@code payload}; the
 * payload is then bound to an explicitly named record chosen by the {@code eventType} string. No
 * {@code __TypeId__} header, no default typing, no Java class name ever comes off the wire.
 *
 * <p>Every rejection is a {@link NonRetryableEventException}: a record this parser cannot read
 * will never become readable, so it belongs on the dead-letter topic rather than in a retry loop.
 * That is deliberately strict for a compensation stream - refusing to act on a payload we do not
 * understand is the only safe default when the alternative is moving stock.
 */
@Component
public class InventoryCompensationEventParser {

    private static final TypeReference<EventEnvelope<JsonNode>> ENVELOPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public InventoryReleaseCommand parse(String value) {
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

        if (!EventTypes.INVENTORY_RELEASE_REQUESTED.equals(envelope.eventType())) {
            throw new NonRetryableEventException("Unsupported eventType '" + envelope.eventType()
                    + "' for event " + envelope.eventId());
        }

        InventoryReleaseRequestedEvent payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), InventoryReleaseRequestedEvent.class);
        } catch (RuntimeException e) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId()
                    + " does not match InventoryReleaseRequestedEvent", e);
        }

        validate(envelope, payload);
        return new InventoryReleaseCommand(envelope.eventId(), envelope.eventType(), payload);
    }

    /** Structural validation only - whether the stock actually permits the release is the processor's call. */
    private static void validate(EventEnvelope<JsonNode> envelope, InventoryReleaseRequestedEvent payload) {
        if (payload.orderId() == null) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId() + " has no orderId");
        }
        if (payload.lines() == null || payload.lines().isEmpty()) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId()
                    + " has no lines to release");
        }
        for (InventoryReleaseLine line : payload.lines()) {
            if (line == null || line.productId() == null) {
                throw new NonRetryableEventException("Payload of event " + envelope.eventId()
                        + " has a release line with no productId");
            }
            if (line.quantity() <= 0) {
                throw new NonRetryableEventException("Payload of event " + envelope.eventId()
                        + " releases a non-positive quantity (" + line.quantity() + ") for product "
                        + line.productId());
            }
        }
    }
}
