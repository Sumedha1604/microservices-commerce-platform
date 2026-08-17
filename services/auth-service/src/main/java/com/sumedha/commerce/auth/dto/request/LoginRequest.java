package com.sumedha.commerce.auth.dto.request; import jakarta.validation.constraints.*; public record LoginRequest(@NotBlank @Email @Size(max=320) String email,@NotBlank String password) {}
