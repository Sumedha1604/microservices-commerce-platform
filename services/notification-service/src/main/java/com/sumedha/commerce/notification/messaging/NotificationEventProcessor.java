package com.sumedha.commerce.notification.messaging;

import com.sumedha.commerce.notification.entity.Notification;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.repository.NotificationRepository;
import com.sumedha.commerce.notification.repository.ProcessedEventRepository;
import com.sumedha.commerce.notification.service.NotificationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records the notification for one payment event, and records that the event was handled, in one
 * transaction.
 *
 * <p><strong>Claim first.</strong> The {@code processed_event} row is claimed with a guarded insert
 * ({@code on conflict do nothing}) <em>before</em> anything else. A second worker holding the same
 * {@code eventId} waits on that insert until the first transaction finishes, then sees the id taken
 * and returns {@link Outcome#DUPLICATE} having written nothing. A duplicate is an outcome, never an
 * exception, so a genuine integrity failure can never be acknowledged away as "probably a
 * duplicate".
 *
 * <p><strong>One transaction.</strong> The claim and the notification insert commit together or
 * not at all: if the insert fails, the claim rolls back with it and the redelivery (or an operator
 * replay) is processed rather than swallowed. The Kafka offset is committed by the container only
 * after this method has returned, i.e. after the commit.
 */
@Service
public class NotificationEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventProcessor.class);

    /** What one delivery actually did. */
    public enum Outcome {
        /** The notification was recorded and the event claimed. */
        CREATED,
        /** This exact eventId was already handled; nothing was written. */
        DUPLICATE
    }

    /** The outcome plus, for {@link Outcome#CREATED}, what was written. */
    public record Result(Outcome outcome, UUID notificationId, NotificationType notificationType) {

        static Result duplicate() {
            return new Result(Outcome.DUPLICATE, null, null);
        }

        static Result created(Notification notification) {
            return new Result(Outcome.CREATED, notification.getId(), notification.getNotificationType());
        }
    }

    private final ProcessedEventRepository processedEvents;
    private final NotificationRepository notifications;
    private final NotificationFactory factory;

    public NotificationEventProcessor(ProcessedEventRepository processedEvents,
                                      NotificationRepository notifications,
                                      NotificationFactory factory) {
        this.processedEvents = processedEvents;
        this.notifications = notifications;
        this.factory = factory;
    }

    @Transactional
    public Result process(PaymentEvent event) {
        if (processedEvents.insertIfAbsent(event.eventId(), event.eventType(), event.orderId()) == 0) {
            log.info("Notification duplicate ignored eventId={} eventType={} orderId={}",
                    event.eventId(), event.eventType(), event.orderId());
            return Result.duplicate();
        }

        // saveAndFlush so a constraint failure surfaces here, inside the transaction holding the
        // claim, and rolls the claim back with it.
        Notification notification = notifications.saveAndFlush(factory.create(event));

        log.info("Notification created notificationId={} eventId={} eventType={} orderId={} "
                        + "notificationType={} channel={} status={}",
                notification.getId(), event.eventId(), event.eventType(), event.orderId(),
                notification.getNotificationType(), notification.getChannel(), notification.getStatus());
        return Result.created(notification);
    }
}
