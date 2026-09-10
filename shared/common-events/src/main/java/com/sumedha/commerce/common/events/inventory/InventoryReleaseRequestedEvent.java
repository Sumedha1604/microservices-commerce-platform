package com.sumedha.commerce.common.events.inventory;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * order-service asks for the inventory held for an order to be released.
 *
 * <p>This is an <em>intent</em>, not a lifecycle announcement, and that is the whole point of
 * its name. "OrderCancelled" would be a fact about an order that inventory-service would then
 * have to interpret - and it would be the wrong fact to act on, because an order can be
 * cancelled through paths that already released their own inventory synchronously. Naming the
 * intent keeps that decision where the knowledge is.
 *
 * <p>Emitted only when a cancellation actually leaves stock held: today that is the
 * {@code PaymentFailed} consumer path.
 *
 * @param orderId the cancelled order whose reservations should be released (partition key)
 * @param reason  short human-readable cause for logs and audit, never machine-parsed
 * @param lines   the product quantities to release; never empty
 */
public record InventoryReleaseRequestedEvent(
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("reason") String reason,
        @JsonProperty("lines") List<InventoryReleaseLine> lines
) {
}
