package com.sumedha.commerce.notification.enums;

/**
 * Where a notification is addressed.
 *
 * <p>{@link #INTERNAL} is the only channel: the durable notification record is the delivery
 * boundary, and no email, SMS or push provider exists on this platform yet. A future provider
 * adds its own channel (and a migration widening {@code ck_notification_channel}) rather than
 * reinterpreting this one.
 */
public enum NotificationChannel {
    INTERNAL
}
