package com.sumedha.commerce.auth.controller;

import com.sumedha.commerce.auth.dto.request.*;
import com.sumedha.commerce.auth.dto.response.AuthResponse;
import com.sumedha.commerce.auth.service.AuthService;
import com.sumedha.commerce.common.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
 private final AuthService service; public AuthController(AuthService service){this.service=service;}
 @Operation(summary="Register an authentication account") @PostMapping("/register") public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Registered",service.register(request)));}
 @Operation(summary="Authenticate with email and password") @PostMapping("/login") public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request){return ApiResponse.success("Authenticated",service.login(request));}
 @Operation(summary="Rotate a refresh token") @PostMapping("/refresh") public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request){return ApiResponse.success("Token refreshed",service.refresh(request));}
 @Operation(summary="Revoke a refresh token") @PostMapping("/logout") public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request){service.logout(request);return ResponseEntity.noContent().build();}
}
