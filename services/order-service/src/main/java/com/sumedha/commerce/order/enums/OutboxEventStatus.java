package com.sumedha.commerce.order.enums;

/**
 * Lifecycle of one durable outbox row.
 *
 * <p>Two states on purpose. A row is either still owed to Kafka ({@code PENDING}) or
 * acknowledged by it ({@code PUBLISHED}); there is no terminal failure state, because giving up
 * on a compensation event would silently strand reserved stock. A row that keeps failing keeps
 * its backoff and stays visible in {@code PENDING}.
 */
public enum OutboxEventStatus {
    PENDING,
    PUBLISHED
}
