package com.sumedha.commerce.recommendation.controller;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.recommendation.dto.response.ProductRecommendation;
import com.sumedha.commerce.recommendation.dto.response.RelatedProductsResponse;
import com.sumedha.commerce.recommendation.exception.GlobalExceptionHandler;
import com.sumedha.commerce.recommendation.service.RecommendationCandidate;
import com.sumedha.commerce.recommendation.service.RecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecommendationControllerTest {

    MockMvc mvc;
    RecommendationService service;

    final UUID sourceId = UUID.randomUUID();
    final UUID candidateId = UUID.randomUUID();
    final UUID categoryId = UUID.randomUUID();
    final UUID brandId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(RecommendationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RecommendationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String path(Object id) {
        return "/api/v1/recommendations/products/" + id;
    }

    @Test
    void aValidProductReturnsScoredRecommendations() throws Exception {
        ProductRecommendation item = ProductRecommendation.of(new RecommendationCandidate(candidateId, "Sibling Phone",
                "sibling-phone", categoryId, brandId, new BigDecimal("110.0000"), "USD", "ACTIVE", true, false), true, true, true);
        when(service.relatedProducts(sourceId, 3)).thenReturn(
                new RelatedProductsResponse(sourceId, RelatedProductsResponse.CONTENT_BASED_V1, 3, List.of(item)));

        mvc.perform(get(path(sourceId)).param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sourceProductId").value(sourceId.toString()))
                .andExpect(jsonPath("$.data.strategy").value("CONTENT_BASED_V1"))
                .andExpect(jsonPath("$.data.limit").value(3))
                .andExpect(jsonPath("$.data.items[0].productId").value(candidateId.toString()))
                .andExpect(jsonPath("$.data.items[0].name").value("Sibling Phone"))
                .andExpect(jsonPath("$.data.items[0].categoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$.data.items[0].brandId").value(brandId.toString()))
                .andExpect(jsonPath("$.data.items[0].price").value(110.0))
                .andExpect(jsonPath("$.data.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.data.items[0].score").value(8))
                .andExpect(jsonPath("$.data.items[0].reasons[0]").value("SAME_CATEGORY"))
                .andExpect(jsonPath("$.data.items[0].reasons[1]").value("SAME_BRAND"))
                .andExpect(jsonPath("$.data.items[0].reasons[2]").value("SIMILAR_PRICE"));
    }

    @Test
    void theDefaultLimitIsTen() throws Exception {
        when(service.relatedProducts(eq(sourceId), anyInt()))
                .thenReturn(new RelatedProductsResponse(sourceId, RelatedProductsResponse.CONTENT_BASED_V1, 10, List.of()));

        mvc.perform(get(path(sourceId))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());

        verify(service).relatedProducts(sourceId, 10);
    }

    @Test
    void aMissingSourceProductIsNotFound() throws Exception {
        when(service.relatedProducts(sourceId, 10))
                .thenThrow(new ResourceNotFoundException("Product not found in the recommendation catalogue: " + sourceId));

        mvc.perform(get(path(sourceId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void anOutOfRangeLimitIsABadRequest() throws Exception {
        when(service.relatedProducts(sourceId, 51)).thenThrow(new BadRequestException("limit must be between 1 and 50"));

        mvc.perform(get(path(sourceId)).param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 50"));
    }

    @Test
    void malformedParametersAreBadRequests() throws Exception {
        mvc.perform(get(path("not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'productId'"));
        mvc.perform(get(path(sourceId)).param("limit", "ten"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'limit'"));

        verifyNoInteractions(service);
    }

    @Test
    void theApiIsReadOnly() throws Exception {
        mvc.perform(post(path(sourceId))).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(service);
    }

    @Test
    void unexpectedErrorsAreSanitized() throws Exception {
        when(service.relatedProducts(sourceId, 10)).thenThrow(new RuntimeException("jdbc:postgresql://secret"));

        mvc.perform(get(path(sourceId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
