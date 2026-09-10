package com.sumedha.commerce.common.events.inventory;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * One product line to release, as a quantity against a product.
 *
 * <p>Deliberately keyed by {@code productId} rather than by an inventory row id or a
 * reservation id. There are no reservation records in this platform - a reservation is a
 * counter on the inventory row - and the only durable record of what was reserved for an order
 * is its {@code order_items}. {@code productId} is what those two sides genuinely share, and
 * inventory-service resolves it to its own row (product_id is unique there).
 *
 * @param productId the product whose reservation should be reduced
 * @param quantity  how many units to release; always positive
 */
public record InventoryReleaseLine(
        @JsonProperty("productId") UUID productId,
        @JsonProperty("quantity") int quantity
) {
}
