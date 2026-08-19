package com.sumedha.commerce.payment.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentRequest(
        @NotNull
        UUID orderId,
        @NotNull
        UUID userId,
        @NotNull
        @DecimalMin("0.00")
        // NUMERIC(19,2) max; bounding the raw value here (before scale-2 HALF_UP
        // normalization) still allows >2 decimal input like 10.005 -> 10.01.
        @DecimalMax("99999999999999999.99")
        BigDecimal amount,
        @NotBlank
        @Pattern(regexp = "[A-Za-z]{3}")
        String currency
) {
}
