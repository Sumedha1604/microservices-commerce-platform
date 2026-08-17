package com.sumedha.commerce.auth.integration;

import com.sumedha.commerce.auth.dto.request.LoginRequest;
import com.sumedha.commerce.auth.dto.request.LogoutRequest;
import com.sumedha.commerce.auth.dto.request.RefreshTokenRequest;
import com.sumedha.commerce.auth.dto.request.RegisterRequest;
import com.sumedha.commerce.auth.entity.EmailVerificationToken;
import com.sumedha.commerce.auth.entity.PasswordResetToken;
import com.sumedha.commerce.auth.entity.RefreshToken;
import com.sumedha.commerce.auth.entity.User;
import com.sumedha.commerce.auth.enums.UserRole;
import com.sumedha.commerce.auth.enums.UserStatus;
import com.sumedha.commerce.auth.repository.EmailVerificationTokenRepository;
import com.sumedha.commerce.auth.repository.PasswordResetTokenRepository;
import com.sumedha.commerce.auth.repository.RefreshTokenRepository;
import com.sumedha.commerce.auth.repository.UserRepository;
import com.sumedha.commerce.auth.security.OpaqueTokenService;
import com.sumedha.commerce.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class AuthPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_test")
            .withUsername("auth")
            .withPassword("auth");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private AuthService authService;
    @Autowired private UserRepository users;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private PasswordResetTokenRepository resetTokens;
    @Autowired private EmailVerificationTokenRepository verificationTokens;
    @Autowired private OpaqueTokenService opaqueTokens;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearDatabase() {
        verificationTokens.deleteAll();
        resetTokens.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    @Test
    void flywayCreatesTheAuthSchema() {
        List<String> tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertTrue(tables.containsAll(List.of("users", "refresh_tokens", "password_reset_tokens", "email_verification_tokens")));
    }

    @Test
    void repositoriesPersistLookUpAndConstrainAuthData() {
        User user = users.saveAndFlush(new User("user@example.test", "bcrypt-hash"));
        assertEquals(user.getId(), users.findByEmail("user@example.test").orElseThrow().getId());
        assertTrue(users.existsByEmail("user@example.test"));
        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(new User("user@example.test", "other")));

        String refreshDigest = opaqueTokens.hash("refresh");
        refreshTokens.saveAndFlush(new RefreshToken(user, refreshDigest, Instant.now().plusSeconds(3600)));
        assertEquals(refreshDigest, refreshTokens.findByTokenHash(refreshDigest).orElseThrow().getTokenHash());

        String resetDigest = opaqueTokens.hash("reset");
        resetTokens.saveAndFlush(new PasswordResetToken(user, resetDigest, Instant.now().plusSeconds(3600)));
        assertTrue(resetTokens.findByTokenHash(resetDigest).isPresent());

        String verificationDigest = opaqueTokens.hash("verification");
        verificationTokens.saveAndFlush(new EmailVerificationToken(user, verificationDigest, Instant.now().plusSeconds(3600)));
        assertTrue(verificationTokens.findByTokenHash(verificationDigest).isPresent());
        assertEquals("CUSTOMER", jdbc.queryForObject("select role from users where user_id = ?", String.class, user.getId()));
        assertEquals("ACTIVE", jdbc.queryForObject("select status from users where user_id = ?", String.class, user.getId()));
    }

    @Test
    void databaseBackedRegistrationLoginRefreshAndLogoutFlow() {
        var registration = authService.register(new RegisterRequest(" Person@Example.Test ", "SecurePassword123"));
        User user = users.findByEmail("person@example.test").orElseThrow();
        assertEquals(UserRole.CUSTOMER, user.getRole());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertFalse(user.isVerified());
        assertNotEquals("SecurePassword123", user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("$2"));
        assertFalse(registration.tokens().accessToken().isBlank());
        assertFalse(registration.tokens().refreshToken().isBlank());
        assertTrue(refreshTokens.findByTokenHash(opaqueTokens.hash(registration.tokens().refreshToken())).isPresent());

        var login = authService.login(new LoginRequest("PERSON@example.test", "SecurePassword123"));
        assertFalse(login.tokens().accessToken().isBlank());
        var rotated = authService.refresh(new RefreshTokenRequest(login.tokens().refreshToken()));
        assertTrue(refreshTokens.findByTokenHash(opaqueTokens.hash(login.tokens().refreshToken())).orElseThrow().isRevoked());
        assertNotEquals(login.tokens().refreshToken(), rotated.tokens().refreshToken());
        assertTrue(refreshTokens.findByTokenHash(opaqueTokens.hash(rotated.tokens().refreshToken())).isPresent());

        authService.logout(new LogoutRequest(rotated.tokens().refreshToken()));
        assertTrue(refreshTokens.findByTokenHash(opaqueTokens.hash(rotated.tokens().refreshToken())).orElseThrow().isRevoked());
    }
}
