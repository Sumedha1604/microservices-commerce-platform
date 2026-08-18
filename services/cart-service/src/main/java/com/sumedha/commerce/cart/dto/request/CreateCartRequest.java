package com.sumedha.commerce.cart.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateCartRequest(
        @NotNull
        UUID userId
) {
}
