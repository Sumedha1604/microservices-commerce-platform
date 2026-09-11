package com.sumedha.commerce.search.controller;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.search.dto.response.ProductSearchResult;
import com.sumedha.commerce.search.exception.GlobalExceptionHandler;
import com.sumedha.commerce.search.service.ProductSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductSearchControllerTest {

    private static final String PATH = "/api/v1/search/products";

    MockMvc mvc;
    ProductSearchService service;

    final UUID productId = UUID.randomUUID();
    final UUID categoryId = UUID.randomUUID();
    final UUID brandId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ProductSearchService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProductSearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ProductSearchResult result() {
        return new ProductSearchResult(productId, "PH-100", "Smart Phone X", "smart-phone-x", "A phone",
                "Flagship smartphone", categoryId, brandId, new BigDecimal("999.0000"), "USD", "ACTIVE", 2L,
                Instant.parse("2026-09-11T10:00:00Z"), Instant.parse("2026-09-11T10:00:01Z"));
    }

    @Test
    void searchPassesEveryParameterAndReturnsThePage() throws Exception {
        when(service.search("phone", categoryId, brandId, new BigDecimal("100"), new BigDecimal("1000"), "USD",
                "ACTIVE", "priceAsc", 1, 10)).thenReturn(PageResponse.of(List.of(result()), 1, 10, 11));

        mvc.perform(get(PATH)
                        .param("q", "phone")
                        .param("categoryId", categoryId.toString())
                        .param("brandId", brandId.toString())
                        .param("minPrice", "100")
                        .param("maxPrice", "1000")
                        .param("currency", "USD")
                        .param("status", "ACTIVE")
                        .param("sort", "priceAsc")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.data.items[0].sku").value("PH-100"))
                .andExpect(jsonPath("$.data.items[0].name").value("Smart Phone X"))
                .andExpect(jsonPath("$.data.items[0].categoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$.data.items[0].price").value(999.0))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].version").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasPrevious").value(true));
    }

    @Test
    void defaultsAreBoundedWhenNothingIsGiven() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());

        verify(service).search(null, null, null, null, null, null, null, null, 0, 20);
    }

    @Test
    void malformedTypedParametersAreBadRequests() throws Exception {
        mvc.perform(get(PATH).param("categoryId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'categoryId'"));
        mvc.perform(get(PATH).param("minPrice", "cheap")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("page", "first")).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void serviceValidationFailuresAreBadRequests() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new BadRequestException("minPrice must not be greater than maxPrice"));

        mvc.perform(get(PATH).param("minPrice", "10").param("maxPrice", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("minPrice must not be greater than maxPrice"));
    }

    @Test
    void theSearchApiIsReadOnly() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));

        verifyNoInteractions(service);
    }

    @Test
    void unexpectedErrorsAreSanitized() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("jdbc:postgresql://secret"));

        mvc.perform(get(PATH).param("q", "phone"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
