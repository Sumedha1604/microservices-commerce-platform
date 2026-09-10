package com.sumedha.commerce.notification.enums;

/**
 * Lifecycle of a notification record.
 *
 * <p>{@link #CREATED} is the only state: the notification has been durably recorded for the
 * {@link NotificationChannel#INTERNAL} channel. There is deliberately no {@code SENT} or
 * {@code DELIVERED} - with no provider attached, nothing is ever sent, and a status claiming
 * otherwise would be false.
 */
public enum NotificationStatus {
    CREATED
}
