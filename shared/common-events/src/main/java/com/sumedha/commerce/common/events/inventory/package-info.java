/**
 * Inventory-compensation event payloads published on {@code order.compensation.v1}.
 *
 * <p>Owned by order-service (publisher), consumed by inventory-service. The direction is
 * deliberate: inventory-service must never consume payment events, because "a payment failed"
 * is not an inventory concern. order-service owns the order lifecycle, so it is the service
 * that decides a reservation is no longer wanted and says so explicitly.
 */
package com.sumedha.commerce.common.events.inventory;
