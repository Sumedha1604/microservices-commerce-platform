package com.sumedha.commerce.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthorizePaymentRequest(
        @NotBlank
        @Size(max = 50)
        String provider,
        @NotBlank
        @Size(max = 255)
        String providerReference
) {
}
