package com.sumedha.commerce.auth.security;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class OpaqueTokenServiceTest { @Test void generatesOpaqueTokensWhoseDigestDoesNotContainRawValue(){OpaqueTokenService service=new OpaqueTokenService();String token=service.generate();assertTrue(token.length()>40);assertNotEquals(token,service.hash(token));assertEquals(service.hash(token),service.hash(token));assertNotEquals(token,service.generate());}}
