package com.sumedha.commerce.search.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the raw record value into a validated {@link ProductEvent}.
 *
 * <p>Two reads of the same string, as notification-service does: the first binds
 * {@code EventEnvelope<JsonNode>} (rejecting a missing {@code eventId}, {@code eventType},
 * {@code occurredAt} or {@code payload}) and exposes the raw payload tree; the second binds the whole
 * envelope with the payload type named by the {@code eventType} string, straight from the text so a
 * price keeps its exact decimal scale. No {@code __TypeId__} header, default typing or Java class
 * name from the record is ever used.
 *
 * <p>The tree is also what proves {@code version} was actually sent: the payload record declares it
 * as a primitive, which would silently read a missing value as {@code 0} - and version 0 is a valid,
 * applicable state.
 *
 * <p>Every rejection is a {@link NonRetryableEventException}.
 */
@Component
public class ProductEventParser {

    private static final TypeReference<EventEnvelope<JsonNode>> ENVELOPE = new TypeReference<>() {
    };
    private static final TypeReference<EventEnvelope<ProductUpsertedEvent>> UPSERTED = new TypeReference<>() {
    };
    private static final TypeReference<EventEnvelope<ProductDeletedEvent>> DELETED = new TypeReference<>() {
    };

    /** product-service's {@code ProductStatus} values. */
    static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "INACTIVE", "DISCONTINUED");

    private static final Pattern CURRENCY = Pattern.compile("[A-Za-z]{3}");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public ProductEvent parse(String value) {
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
            case EventTypes.PRODUCT_UPSERTED -> upserted(envelope, bind(value, UPSERTED, envelope, "ProductUpsertedEvent"));
            case EventTypes.PRODUCT_DELETED -> deleted(envelope, bind(value, DELETED, envelope, "ProductDeletedEvent"));
            default -> throw new NonRetryableEventException(
                    "Unsupported eventType '" + envelope.eventType() + "' for event " + envelope.eventId());
        };
    }

    private <T> T bind(String value, TypeReference<EventEnvelope<T>> type, EventEnvelope<JsonNode> envelope,
                       String payloadName) {
        if (!envelope.payload().isObject()) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId() + " is not an object");
        }
        try {
            return objectMapper.readValue(value, type).payload();
        } catch (RuntimeException e) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId()
                    + " does not match " + payloadName, e);
        }
    }

    private static ProductEvent upserted(EventEnvelope<JsonNode> envelope, ProductUpsertedEvent payload) {
        String id = "Payload of event " + envelope.eventId();
        requireProductAndVersion(envelope, payload.productId(), payload.version());
        requireText(id, "sku", payload.sku());
        requireText(id, "name", payload.name());
        requireText(id, "slug", payload.slug());
        if (payload.categoryId() == null) {
            throw new NonRetryableEventException(id + " has no categoryId");
        }
        if (payload.price() == null || payload.price().signum() < 0) {
            throw new NonRetryableEventException(id + " has a missing or negative price");
        }
        if (payload.currency() == null || !CURRENCY.matcher(payload.currency()).matches()) {
            throw new NonRetryableEventException(id + " has no valid 3-letter currency");
        }
        if (payload.status() == null || !STATUSES.contains(payload.status())) {
            throw new NonRetryableEventException(id + " has unknown status '" + payload.status() + "'");
        }
        if (!envelope.payload().hasNonNull("active") || !envelope.payload().get("active").isBoolean()) {
            throw new NonRetryableEventException(id + " has no boolean active flag");
        }
        return new ProductEvent.Upserted(envelope.eventId(), payload);
    }

    private static ProductEvent deleted(EventEnvelope<JsonNode> envelope, ProductDeletedEvent payload) {
        requireProductAndVersion(envelope, payload.productId(), payload.version());
        return new ProductEvent.Deleted(envelope.eventId(), payload);
    }

    private static void requireProductAndVersion(EventEnvelope<JsonNode> envelope, Object productId, long version) {
        String id = "Payload of event " + envelope.eventId();
        if (productId == null) {
            throw new NonRetryableEventException(id + " has no productId");
        }
        JsonNode versionNode = envelope.payload().get("version");
        if (versionNode == null || !versionNode.isIntegralNumber() || version < 0) {
            throw new NonRetryableEventException(id + " has no valid non-negative version");
        }
    }

    private static void requireText(String id, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new NonRetryableEventException(id + " has no " + field);
        }
    }
}
