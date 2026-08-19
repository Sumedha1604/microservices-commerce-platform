package com.sumedha.commerce.checkout.controller;

import com.sumedha.commerce.checkout.dto.request.CheckoutRequest;
import com.sumedha.commerce.checkout.dto.response.CheckoutResponse;
import com.sumedha.commerce.checkout.service.CheckoutService;
import com.sumedha.commerce.common.core.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checkouts")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(@Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(checkoutService.checkout(request.cartId())));
    }
}
