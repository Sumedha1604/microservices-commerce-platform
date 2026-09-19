package com.sumedha.commerce.recommendation.messaging;

import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.common.events.product.ProductUpsertedEvent;
import com.sumedha.commerce.recommendation.repository.ProcessedEventRepository;
import com.sumedha.commerce.recommendation.repository.RecommendationProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.TransientDataAccessResourceException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductProjectionProcessorTest {

    private ProcessedEventRepository processedEvents;
    private RecommendationProductRepository products;
    private ProductProjectionProcessor processor;

    private final UUID productId = UUID.randomUUID();
    private final ProductEvent.Upserted upserted = new ProductEvent.Upserted(UUID.randomUUID(),
            new ProductUpsertedEvent(productId, "SKU", "Phone", "phone", null, null, UUID.randomUUID(), null,
                    BigDecimal.TEN, "USD", "ACTIVE", true, 2L, Instant.now()));
    private final ProductEvent.Deleted deleted = new ProductEvent.Deleted(UUID.randomUUID(), new ProductDeletedEvent(productId, 3L));

    @BeforeEach
    void setUp() {
        processedEvents = mock(ProcessedEventRepository.class);
        products = mock(RecommendationProductRepository.class);
        processor = new ProductProjectionProcessor(processedEvents, products);
    }

    @Test
    void anUpsertClaimsTheEventThenAppliesTheState() {
        when(processedEvents.claim(upserted.eventId(), "ProductUpserted", productId)).thenReturn(1);
        when(products.upsert(upserted.eventId(), upserted.payload())).thenReturn(1);

        assertEquals(ProductProjectionProcessor.Outcome.UPSERTED, processor.process(upserted));

        InOrder order = inOrder(processedEvents, products);
        order.verify(processedEvents).claim(upserted.eventId(), "ProductUpserted", productId);
        order.verify(products).upsert(upserted.eventId(), upserted.payload());
    }

    @Test
    void staleUpsertsAndDeletesAreOutcomesNotFailures() {
        when(processedEvents.claim(any(), any(), any())).thenReturn(1);
        when(products.upsert(any(), any())).thenReturn(0);
        when(products.tombstone(any(), any(), anyLong())).thenReturn(0);

        assertEquals(ProductProjectionProcessor.Outcome.STALE_IGNORED, processor.process(upserted));
        assertEquals(ProductProjectionProcessor.Outcome.STALE_IGNORED, processor.process(deleted));
    }

    @Test
    void aDeleteTombstonesTheProduct() {
        when(processedEvents.claim(any(), any(), any())).thenReturn(1);
        when(products.tombstone(deleted.eventId(), productId, 3L)).thenReturn(1);

        assertEquals(ProductProjectionProcessor.Outcome.DELETED, processor.process(deleted));
    }

    @Test
    void aDuplicateEventIdTouchesNoProjection() {
        when(processedEvents.claim(any(), any(), any())).thenReturn(0);

        assertEquals(ProductProjectionProcessor.Outcome.DUPLICATE, processor.process(upserted));
        assertEquals(ProductProjectionProcessor.Outcome.DUPLICATE, processor.process(deleted));
        verifyNoInteractions(products);
    }

    @Test
    void aProjectionFailurePropagatesSoTheClaimRollsBack() {
        when(processedEvents.claim(any(), any(), any())).thenReturn(1);
        when(products.upsert(any(), any())).thenThrow(new TransientDataAccessResourceException("db blip"));

        assertThrows(TransientDataAccessResourceException.class, () -> processor.process(upserted));
    }
}
