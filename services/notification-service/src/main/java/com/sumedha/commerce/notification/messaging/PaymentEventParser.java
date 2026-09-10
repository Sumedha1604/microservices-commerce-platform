package com.sumedha.commerce.notification.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.payment.PaymentAuthorizedEvent;
import com.sumedha.commerce.common.events.payment.PaymentFailedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Turns the raw record value into a validated {@link PaymentEvent}.
 *
 * <p>Two reads of the same string. The first binds {@code EventEnvelope<JsonNode>} - the shared v1
 * contract - which rejects a missing {@code eventId}, {@code eventType}, {@code occurredAt} or
 * {@code payload}, and exposes {@code schemaVersion} and {@code eventType} for checking. The second
 * binds the whole envelope again with the payload named explicitly by that {@code eventType}.
 * Binding from the original text rather than from the intermediate tree matters for money: a tree
 * holds {@code 59.97} as a double, while a direct bind keeps it an exact {@link BigDecimal}.
 *
 * <p>No {@code __TypeId__} header, no default typing, no Java class name ever comes off the wire;
 * a {@code "@class"} property is just an unknown field.
 *
 * <p>Every rejection is a {@link NonRetryableEventException}: a record this parser cannot read will
 * never become readable, so it belongs on the dead-letter topic rather than in a retry loop.
 */
@Component
public class PaymentEventParser {

    private static final TypeReference<EventEnvelope<JsonNode>> ENVELOPE = new TypeReference<>() {
    };
    private static final TypeReference<EventEnvelope<PaymentAuthorizedEvent>> AUTHORIZED = new TypeReference<>() {
    };
    private static final TypeReference<EventEnvelope<PaymentFailedEvent>> FAILED = new TypeReference<>() {
    };

    private static final Pattern CURRENCY = Pattern.compile("[A-Za-z]{3}");

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
            case EventTypes.PAYMENT_AUTHORIZED -> authorized(envelope, bind(value, AUTHORIZED, envelope, "PaymentAuthorizedEvent"));
            case EventTypes.PAYMENT_FAILED -> failed(envelope, bind(value, FAILED, envelope, "PaymentFailedEvent"));
            default -> throw new NonRetryableEventException(
                    "Unsupported eventType '" + envelope.eventType() + "' for event " + envelope.eventId());
        };
    }

    private <T> T bind(String value, TypeReference<EventEnvelope<T>> type,
                       EventEnvelope<JsonNode> envelope, String payloadName) {
        try {
            return objectMapper.readValue(value, type).payload();
        } catch (RuntimeException e) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId()
                    + " does not match " + payloadName, e);
        }
    }

    private static PaymentEvent authorized(EventEnvelope<JsonNode> envelope, PaymentAuthorizedEvent payload) {
        requireIdentifiers(envelope, payload.paymentId(), payload.orderId(), payload.userId());
        BigDecimal amount = payload.amount();
        if (amount == null || amount.signum() < 0) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId()
                    + " has a missing or negative amount");
        }
        if (payload.currency() == null || !CURRENCY.matcher(payload.currency()).matches()) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId()
                    + " has no valid 3-letter currency");
        }
        return new PaymentEvent.Authorized(envelope.eventId(), envelope.occurredAt(), payload);
    }

    private static PaymentEvent failed(EventEnvelope<JsonNode> envelope, PaymentFailedEvent payload) {
        requireIdentifiers(envelope, payload.paymentId(), payload.orderId(), payload.userId());
        // failureReason is optional: a failure notification is still true without one.
        return new PaymentEvent.Failed(envelope.eventId(), envelope.occurredAt(), payload);
    }

    private static void requireIdentifiers(EventEnvelope<JsonNode> envelope, Object paymentId,
                                           Object orderId, Object userId) {
        if (paymentId == null) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId() + " has no paymentId");
        }
        if (orderId == null) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId() + " has no orderId");
        }
        if (userId == null) {
            throw new NonRetryableEventException("Payload of event " + envelope.eventId() + " has no userId");
        }
    }
}
