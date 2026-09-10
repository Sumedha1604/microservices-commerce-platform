package com.sumedha.commerce.notification.metrics;

import com.sumedha.commerce.notification.enums.NotificationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Counters for the payment-event consumer, on the existing Prometheus registry.
 *
 * <p>Cardinality is fixed by construction: every counter is pre-registered, and the only tag is
 * {@code type}, drawn from the {@link NotificationType} enum. Nothing is derived from event ids,
 * order ids, user ids or error text.
 */
@Component
public class NotificationMetrics {

    private final Counter received;
    private final Map<NotificationType, Counter> created = new EnumMap<>(NotificationType.class);
    private final Counter duplicateIgnored;
    private final Counter failed;

    public NotificationMetrics(MeterRegistry registry) {
        this.received = Counter.builder("notification.events.received")
                .description("Payment events received from payment.events.v1")
                .register(registry);
        for (NotificationType type : NotificationType.values()) {
            // Not "notification.created": the Prometheus client reserves the _created suffix for
            // counter creation timestamps and strips it, which would export this as notification_total.
            created.put(type, Counter.builder("notification.persisted")
                    .description("Notifications durably recorded")
                    .tag("type", type.name())
                    .register(registry));
        }
        this.duplicateIgnored = Counter.builder("notification.duplicate.ignored")
                .description("Payment events ignored because the eventId was already handled")
                .register(registry);
        this.failed = Counter.builder("notification.failed")
                .description("Processing attempts that failed and were retried or dead-lettered")
                .register(registry);
    }

    public void received() { received.increment(); }
    public void created(NotificationType type) { created.get(type).increment(); }
    public void duplicateIgnored() { duplicateIgnored.increment(); }
    public void failed() { failed.increment(); }
}
