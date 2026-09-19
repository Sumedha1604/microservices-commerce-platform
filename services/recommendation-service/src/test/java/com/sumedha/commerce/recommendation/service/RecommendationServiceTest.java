package com.sumedha.commerce.recommendation.service;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.recommendation.dto.response.ProductRecommendation;
import com.sumedha.commerce.recommendation.dto.response.RelatedProductsResponse;
import com.sumedha.commerce.recommendation.metrics.RecommendationMetrics;
import com.sumedha.commerce.recommendation.repository.RecommendationProductRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecommendationServiceTest {

    private RecommendationProductRepository repository;
    private SimpleMeterRegistry registry;
    private RecommendationService service;

    private final UUID sourceId = UUID.randomUUID();
    private final RecommendationCandidate source = new RecommendationCandidate(sourceId, "Source", "source",
            UUID.randomUUID(), null, BigDecimal.TEN, "USD", "ACTIVE", true, false);

    @BeforeEach
    void setUp() {
        repository = mock(RecommendationProductRepository.class);
        registry = new SimpleMeterRegistry();
        service = new RecommendationService(repository, new RecommendationMetrics(registry));
    }

    private double requests(String outcome) {
        return registry.get("recommendation.requests").tag("outcome", outcome).counter().count();
    }

    @Test
    void aKnownSourceReturnsItsRankedRecommendations() {
        ProductRecommendation item = ProductRecommendation.of(new RecommendationCandidate(UUID.randomUUID(), "Sibling",
                "sibling", source.categoryId(), null, BigDecimal.TEN, "USD", "ACTIVE", true, false), true, false, true);
        when(repository.findLive(sourceId)).thenReturn(Optional.of(source));
        when(repository.findRelated(sourceId, 5)).thenReturn(List.of(item));

        RelatedProductsResponse response = service.relatedProducts(sourceId, 5);

        assertEquals(sourceId, response.sourceProductId());
        assertEquals("CONTENT_BASED_V1", response.strategy());
        assertEquals(5, response.limit());
        assertEquals(List.of(item), response.items());
        assertEquals(6, item.score());
        assertEquals(1, requests("success"));
        assertEquals(1, registry.get("recommendation.request.duration").timer().count());
    }

    @Test
    void anEmptyResultIsASuccessNotAnError() {
        when(repository.findLive(sourceId)).thenReturn(Optional.of(source));
        when(repository.findRelated(sourceId, 10)).thenReturn(List.of());

        assertTrue(service.relatedProducts(sourceId, 10).items().isEmpty());
    }

    @Test
    void aMissingOrDeletedSourceIsNotFound() {
        when(repository.findLive(sourceId)).thenReturn(Optional.empty());

        ResourceNotFoundException notFound = assertThrows(ResourceNotFoundException.class,
                () -> service.relatedProducts(sourceId, 10));

        assertTrue(notFound.getMessage().contains(sourceId.toString()));
        verify(repository, never()).findRelated(eq(sourceId), anyInt());
        assertEquals(1, requests("not_found"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51, 1000})
    void aLimitOutsideOneToFiftyIsRejected(int limit) {
        assertThrows(BadRequestException.class, () -> service.relatedProducts(sourceId, limit));

        verifyNoInteractions(repository);
        assertEquals(1, requests("rejected"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void theLimitBoundsAreInclusive(int limit) {
        when(repository.findLive(sourceId)).thenReturn(Optional.of(source));
        when(repository.findRelated(sourceId, limit)).thenReturn(List.of());

        assertEquals(limit, service.relatedProducts(sourceId, limit).limit());
    }
}
