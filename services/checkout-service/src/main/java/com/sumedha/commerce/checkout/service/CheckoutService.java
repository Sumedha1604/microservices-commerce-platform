package com.sumedha.commerce.checkout.service;

import com.sumedha.commerce.checkout.dto.response.CheckoutResponse;

import java.util.UUID;

public interface CheckoutService {

    CheckoutResponse checkout(UUID cartId);
}
