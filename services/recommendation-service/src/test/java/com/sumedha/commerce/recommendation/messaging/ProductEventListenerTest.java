package com.sumedha.commerce.recommendation.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.recommendation.metrics.RecommendationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductEventListenerTest {

    private SimpleMeterRegistry registry;
    private ProductProjectionProcessor processor;
    private ProductEventListener listener;

    private final String validRecord = JsonMapper.builder().build().writeValueAsString(new EventEnvelope<>(
            UUID.randomUUID(), EventTypes.PRODUCT_DELETED, 1, Instant.now(), new ProductDeletedEvent(UUID.randomUUID(), 1L)));

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        processor = mock(ProductProjectionProcessor.class);
        listener = new ProductEventListener(new ProductEventParser(), processor, new RecommendationMetrics(registry));
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    @ParameterizedTest
    @EnumSource(ProductProjectionProcessor.Outcome.class)
    void everyOutcomeIsCountedUnderItsOwnCounter(ProductProjectionProcessor.Outcome outcome) {
        when(processor.process(any())).thenReturn(outcome);

        listener.onProductEvent(validRecord, "key");

        Map<ProductProjectionProcessor.Outcome, String> counters = Map.of(
                ProductProjectionProcessor.Outcome.UPSERTED, "recommendation.projection.upserted",
                ProductProjectionProcessor.Outcome.DELETED, "recommendation.projection.deleted",
                ProductProjectionProcessor.Outcome.STALE_IGNORED, "recommendation.stale.ignored",
                ProductProjectionProcessor.Outcome.DUPLICATE, "recommendation.duplicate.ignored");
        assertEquals(1, count("recommendation.events.received"));
        counters.forEach((candidate, name) -> assertEquals(candidate == outcome ? 1 : 0, count(name), name));
        assertEquals(0, count("recommendation.failed"));
    }

    @Test
    void anUnreadableRecordIsCountedAndRethrownWithoutReachingTheProcessor() {
        assertThrows(NonRetryableEventException.class, () -> listener.onProductEvent("{not json", "key"));

        assertEquals(1, count("recommendation.failed"));
        verifyNoInteractions(processor);
    }

    @Test
    void aTransientFailureIsRethrownForTheContainerToRetry() {
        when(processor.process(any())).thenThrow(new TransientDataAccessResourceException("db blip"));

        assertThrows(TransientDataAccessResourceException.class, () -> listener.onProductEvent(validRecord, "key"));
        assertEquals(1, count("recommendation.failed"));
    }

    @Test
    void metricCardinalityIsFixed() {
        assertEquals(3, registry.find("recommendation.requests").counters().size(), "outcome=success|not_found|rejected");
        assertEquals(1, registry.find("recommendation.request.duration").timers().size());
        for (String name : new String[]{"recommendation.events.received", "recommendation.projection.upserted",
                "recommendation.projection.deleted", "recommendation.duplicate.ignored", "recommendation.stale.ignored",
                "recommendation.failed"}) {
            assertEquals(1, registry.find(name).counters().size(), name);
        }
    }
}
