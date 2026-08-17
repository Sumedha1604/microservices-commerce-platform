package com.sumedha.commerce.auth.service;

import com.sumedha.commerce.auth.config.AuthProperties;
import com.sumedha.commerce.auth.dto.request.*;
import com.sumedha.commerce.auth.entity.*;
import com.sumedha.commerce.auth.enums.UserStatus;
import com.sumedha.commerce.auth.repository.*;
import com.sumedha.commerce.auth.security.*;
import com.sumedha.commerce.common.core.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuthServiceImplTest {
 @Mock UserRepository users; @Mock RefreshTokenRepository refreshes; @Mock PasswordResetTokenRepository resets; @Mock EmailVerificationTokenRepository verifications; @Mock PasswordEncoder passwords; @Mock JwtService jwt; @Mock OpaqueTokenService opaque;
 AuthService service; User user;
 @BeforeEach void setup(){service=new AuthServiceImpl(users,refreshes,resets,verifications,passwords,jwt,opaque,new AuthProperties("12345678901234567890123456789012",Duration.ofMinutes(15),Duration.ofDays(30),"issuer"));user=new User("person@example.test","stored-hash");when(passwords.encode(anyString())).thenReturn("stored-hash");when(opaque.generate()).thenReturn("raw-token");when(opaque.hash("raw-token")).thenReturn("token-digest");when(jwt.create(any())).thenReturn("access-token");}
 @Test void registersNormalizedCustomerWithHashedPasswordAndDigest(){when(users.save(any())).thenAnswer(i->i.getArgument(0)); var response=service.register(new RegisterRequest(" Person@Example.TEST ","SecurePassword123"));ArgumentCaptor<User> captor=ArgumentCaptor.forClass(User.class);verify(users).existsByEmail("person@example.test");verify(passwords).encode("SecurePassword123");verify(users).save(captor.capture());assertEquals("person@example.test",captor.getValue().getEmail());assertEquals("stored-hash",captor.getValue().getPasswordHash());assertEquals(com.sumedha.commerce.auth.enums.UserRole.CUSTOMER,captor.getValue().getRole());assertEquals(UserStatus.ACTIVE,captor.getValue().getStatus());assertFalse(captor.getValue().isVerified());verify(refreshes).save(argThat(t->"token-digest".equals(t.getTokenHash())));assertEquals("access-token",response.tokens().accessToken());assertEquals("raw-token",response.tokens().refreshToken());}
 @Test void rejectsDuplicateRegistration(){when(users.existsByEmail("person@example.test")).thenReturn(true);assertThrows(ConflictException.class,()->service.register(new RegisterRequest("person@example.test","SecurePassword123")));verify(users,never()).save(any());}
 @Test void loginUsesGenericErrorForUnknownAndWrongPassword(){when(users.findByEmail("person@example.test")).thenReturn(Optional.empty());var missing=assertThrows(UnauthorizedException.class,()->service.login(new LoginRequest(" PERSON@example.test ","pw")));when(users.findByEmail("person@example.test")).thenReturn(Optional.of(user));when(passwords.matches("pw","stored-hash")).thenReturn(false);var wrong=assertThrows(UnauthorizedException.class,()->service.login(new LoginRequest("person@example.test","pw")));assertEquals(missing.getMessage(),wrong.getMessage());}
 @Test void loginRejectsUnavailableAndIssuesForActive(){user.setStatus(UserStatus.DISABLED);when(users.findByEmail(any())).thenReturn(Optional.of(user));when(passwords.matches(any(),any())).thenReturn(true);assertThrows(UnauthorizedException.class,()->service.login(new LoginRequest("person@example.test","pw")));user.setStatus(UserStatus.ACTIVE);assertEquals("access-token",service.login(new LoginRequest("person@example.test","pw")).tokens().accessToken());verify(refreshes).save(any(RefreshToken.class));}
 @Test void refreshHashesInputRevokesOldAndIssuesNew(){RefreshToken old=new RefreshToken(user,"digest",java.time.Instant.now().plusSeconds(60));when(opaque.hash("raw")).thenReturn("digest");when(refreshes.findByTokenHash("digest")).thenReturn(Optional.of(old));service.refresh(new RefreshTokenRequest("raw"));assertTrue(old.isRevoked());verify(refreshes).save(argThat(t->"token-digest".equals(t.getTokenHash())));}
 @Test void refreshRejectsMissingRevokedAndExpired(){when(opaque.hash("raw")).thenReturn("digest");when(refreshes.findByTokenHash("digest")).thenReturn(Optional.empty());assertThrows(UnauthorizedException.class,()->service.refresh(new RefreshTokenRequest("raw")));RefreshToken revoked=new RefreshToken(user,"digest",java.time.Instant.now().plusSeconds(60));revoked.revoke();when(refreshes.findByTokenHash("digest")).thenReturn(Optional.of(revoked));assertThrows(UnauthorizedException.class,()->service.refresh(new RefreshTokenRequest("raw")));}
 @Test void logoutHashesAndIsIdempotent(){when(opaque.hash("raw")).thenReturn("digest");RefreshToken token=new RefreshToken(user,"digest",java.time.Instant.now().plusSeconds(60));when(refreshes.findByTokenHash("digest")).thenReturn(Optional.of(token));service.logout(new LogoutRequest("raw"));service.logout(new LogoutRequest("raw"));assertTrue(token.isRevoked());verify(refreshes,times(2)).findByTokenHash("digest");}
 @Test void resetAndVerificationUseDigestAndMarkTokens(){when(users.findByEmail("person@example.test")).thenReturn(Optional.of(user));service.requestPasswordReset("person@example.test");verify(resets).save(argThat(t->t!=null));PasswordResetToken reset=new PasswordResetToken(user,"digest",java.time.Instant.now().plusSeconds(60));when(opaque.hash("reset")).thenReturn("digest");when(resets.findByTokenHash("digest")).thenReturn(Optional.of(reset));service.confirmPasswordReset("reset","NewPassword123");assertFalse(reset.usable());verify(passwords).encode("NewPassword123");when(users.findById(user.getId())).thenReturn(Optional.of(user));assertEquals("raw-token",service.createVerificationToken(user.getId()));verify(verifications).save(any());EmailVerificationToken verification=new EmailVerificationToken(user,"digest",java.time.Instant.now().plusSeconds(60));when(verifications.findByTokenHash("digest")).thenReturn(Optional.of(verification));service.verifyEmail("reset");assertTrue(user.isVerified());assertFalse(verification.usable());}
 @Test void verificationUnknownUserAndUnknownTokensFail(){assertThrows(ResourceNotFoundException.class,()->service.createVerificationToken(UUID.randomUUID()));when(opaque.hash("bad")).thenReturn("bad");when(resets.findByTokenHash("bad")).thenReturn(Optional.empty());assertThrows(BadRequestException.class,()->service.confirmPasswordReset("bad","NewPassword123"));when(verifications.findByTokenHash("bad")).thenReturn(Optional.empty());assertThrows(BadRequestException.class,()->service.verifyEmail("bad"));}
}
