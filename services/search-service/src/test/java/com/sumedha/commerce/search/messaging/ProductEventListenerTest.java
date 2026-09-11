package com.sumedha.commerce.search.messaging;

import com.sumedha.commerce.common.events.EventEnvelope;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.product.ProductDeletedEvent;
import com.sumedha.commerce.search.metrics.SearchMetrics;
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

/** The listener's contract with the container and the metrics registry. */
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
        listener = new ProductEventListener(new ProductEventParser(), processor, new SearchMetrics(registry));
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
                ProductProjectionProcessor.Outcome.UPSERTED, "search.projection.upserted",
                ProductProjectionProcessor.Outcome.DELETED, "search.projection.deleted",
                ProductProjectionProcessor.Outcome.STALE_IGNORED, "search.projection.stale.ignored",
                ProductProjectionProcessor.Outcome.DUPLICATE, "search.duplicate.ignored");
        assertEquals(1, count("search.events.received"));
        counters.forEach((candidate, name) -> assertEquals(candidate == outcome ? 1 : 0, count(name), name));
        assertEquals(0, count("search.indexing.failed"));
    }

    @Test
    void anUnreadableRecordIsCountedAndRethrownWithoutReachingTheProcessor() {
        assertThrows(NonRetryableEventException.class, () -> listener.onProductEvent("{not json", "key"));

        assertEquals(1, count("search.events.received"));
        assertEquals(1, count("search.indexing.failed"));
        verifyNoInteractions(processor);
    }

    @Test
    void aTransientFailureIsRethrownSoTheContainerCanRetryIt() {
        when(processor.process(any())).thenThrow(new TransientDataAccessResourceException("db blip"));

        assertThrows(TransientDataAccessResourceException.class, () -> listener.onProductEvent(validRecord, "key"));

        assertEquals(1, count("search.indexing.failed"));
    }

    @Test
    void metricCardinalityIsFixed() {
        assertEquals(2, registry.find("search.queries").counters().size(), "outcome=success|rejected only");
        assertEquals(1, registry.find("search.query.duration").timers().size());
        for (String name : new String[]{"search.events.received", "search.projection.upserted", "search.projection.deleted",
                "search.projection.stale.ignored", "search.duplicate.ignored", "search.indexing.failed"}) {
            assertEquals(1, registry.find(name).counters().size(), name);
        }
    }
}
