package com.sumedha.commerce.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FailPaymentRequest(
        @NotBlank
        @Size(max = 500)
        String reason
) {
}
