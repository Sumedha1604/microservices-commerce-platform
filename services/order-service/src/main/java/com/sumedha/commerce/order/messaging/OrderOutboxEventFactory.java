package com.sumedha.commerce.order.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseLine;
import com.sumedha.commerce.common.events.inventory.InventoryReleaseRequestedEvent;
import com.sumedha.commerce.order.entity.Order;
import com.sumedha.commerce.order.entity.OrderItem;
import com.sumedha.commerce.order.entity.OrderOutboxEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds the durable compensation row from an order and its lines.
 *
 * <p>The envelope is serialized here, inside the business transaction, so the exact bytes that
 * will reach Kafka are the ones committed alongside the cancellation. The event is keyed by
 * {@code orderId}, which both partitions it and gives the per-aggregate ordering guard something
 * to order on.
 */
@Component
public class OrderOutboxEventFactory {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * @param items the order's lines; these are what checkout reserved, and the only durable
     *              record of it, since inventory holds no reservation rows
     */
    public OrderOutboxEvent inventoryReleaseRequested(Order order, List<OrderItem> items, String reason) {
        List<InventoryReleaseLine> lines = items.stream()
                .map(item -> new InventoryReleaseLine(item.getProductId(), item.getQuantity()))
                .toList();

        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        EventEnvelope<InventoryReleaseRequestedEvent> envelope = new EventEnvelope<>(
                eventId, EventTypes.INVENTORY_RELEASE_REQUESTED, EventEnvelope.SCHEMA_VERSION_V1,
                occurredAt, new InventoryReleaseRequestedEvent(order.getId(), reason, lines));

        return new OrderOutboxEvent(order.getId(), eventId, EventTypes.INVENTORY_RELEASE_REQUESTED,
                EventEnvelope.SCHEMA_VERSION_V1, KafkaTopics.ORDER_COMPENSATION_V1,
                order.getId().toString(), objectMapper.writeValueAsString(envelope), occurredAt);
    }
}
