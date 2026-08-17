package com.sumedha.commerce.auth.controller;

import com.sumedha.commerce.auth.dto.response.*;
import com.sumedha.commerce.auth.enums.UserRole;
import com.sumedha.commerce.auth.exception.GlobalExceptionHandler;
import com.sumedha.commerce.auth.service.AuthService;
import com.sumedha.commerce.common.core.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerTest {
 private AuthService service; private MockMvc mvc;
 private final AuthResponse response=new AuthResponse(UUID.randomUUID(),"a@b.test",UserRole.CUSTOMER,false,new TokenResponse("access","refresh","Bearer"));
 @BeforeEach void setup(){service=mock(AuthService.class);mvc=MockMvcBuilders.standaloneSetup(new AuthController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();}
 @Test void registerHandlesSuccessValidationMalformedAndConflict()throws Exception{when(service.register(any())).thenReturn(response);mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.test\",\"password\":\"SecurePassword123\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.tokens.accessToken").value("access"));mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"bad\",\"password\":\"short\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Request validation failed"));mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest());when(service.register(any())).thenThrow(new ConflictException("duplicate"));mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.test\",\"password\":\"SecurePassword123\"}")).andExpect(status().isConflict());}
 @Test void loginRefreshAndLogoutMapExpectedStatuses()throws Exception{when(service.login(any())).thenReturn(response);mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.test\",\"password\":\"p\"}")).andExpect(status().isOk());when(service.login(any())).thenThrow(new UnauthorizedException("Invalid email or password"));mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.test\",\"password\":\"p\"}")).andExpect(status().isUnauthorized());when(service.refresh(any())).thenReturn(response);mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"token\"}")).andExpect(status().isOk());mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\" \"}")).andExpect(status().isBadRequest());mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"token\"}")).andExpect(status().isNoContent());mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\" \"}")).andExpect(status().isBadRequest());}}
