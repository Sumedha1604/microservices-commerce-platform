package com.sumedha.commerce.inventory.messaging;

import com.sumedha.commerce.common.events.inventory.InventoryReleaseRequestedEvent;

import java.util.UUID;

/**
 * A validated compensation request: the envelope's identity plus the payload the consumer trusts.
 *
 * <p>Separated from the wire record so nothing downstream has to re-check what the parser already
 * guaranteed - by the time this exists, the order id is present and every line has a positive
 * quantity and a product.
 */
public record InventoryReleaseCommand(UUID eventId, String eventType,
                                      InventoryReleaseRequestedEvent payload) {

    public UUID orderId() {
        return payload.orderId();
    }
}
