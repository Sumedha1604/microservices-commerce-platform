package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Best-effort read of the envelope metadata inside a dead-lettered payload.
 *
 * <p>Unlike {@link PaymentEventParser}, this <strong>never throws</strong>. A record is on the
 * dead-letter topic precisely because something about it was wrong, and the most valuable
 * inspection records are often the ones that cannot be parsed at all. Each field is extracted
 * independently, so a payload with a good {@code eventId} but a broken payload block still
 * yields a useful row.
 *
 * <p>It reads a loose {@link JsonNode} tree rather than binding {@link EventEnvelope}, because
 * binding enforces non-null invariants that a malformed record will not satisfy.
 */
@Component
public class DeadLetterPayloadInspector {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /** Envelope metadata recovered from a payload. Every component may be null. */
    public record Metadata(UUID eventId, String eventType, Integer schemaVersion, UUID orderId) {

        static Metadata empty() {
            return new Metadata(null, null, null, null);
        }
    }

    public Metadata inspect(String payload) {
        if (payload == null || payload.isBlank()) {
            return Metadata.empty();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (RuntimeException notJson) {
            return Metadata.empty();
        }
        if (root == null || !root.isObject()) {
            return Metadata.empty();
        }

        return new Metadata(
                uuid(root.get("eventId")),
                text(root.get("eventType")),
                integer(root.get("schemaVersion")),
                uuid(path(root.get("payload"), "orderId")));
    }

    private static JsonNode path(JsonNode node, String field) {
        return node == null || !node.isObject() ? null : node.get(field);
    }

    private static String text(JsonNode node) {
        return node == null || !node.isString() ? null : node.stringValue();
    }

    private static Integer integer(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.asInt();
    }

    private static UUID uuid(JsonNode node) {
        String raw = text(node);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
