package dev.ohs.player.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.ohs.player.iam.IamProviderService;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JwtTokenValidatorTest {

  private static final String ISSUER = "https://auth.example.com/realms/test";

  @Mock private IamProviderService iamProviderService;

  private RSAKey rsaKey;
  private JwtTokenValidator validator;

  @BeforeEach
  void setUp() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    ConfigurableJWTProcessor<com.nimbusds.jose.proc.SecurityContext> processor =
        buildTestProcessor(rsaKey, ISSUER);
    validator = new JwtTokenValidator(processor, iamProviderService);
  }

  @Test
  void validate_ValidToken_ReturnsAuthenticatedUser() throws Exception {
    when(iamProviderService.extractRolesFromToken(any())).thenReturn(Set.of("users.manage"));

    String token = signToken(rsaKey, ISSUER, "user-123", "alice", 60);
    AuthenticatedUser user = validator.validate(token);

    assertEquals("user-123", user.getSub());
    assertEquals("alice", user.getPreferredUsername());
    assertEquals(Set.of("users.manage"), user.getRoles());
  }

  @Test
  void validate_TokenWithoutPreferredUsername_FallsBackToSub() throws Exception {
    when(iamProviderService.extractRolesFromToken(any())).thenReturn(Set.of());

    String token = signTokenNoUsername(rsaKey, ISSUER, "user-456", 60);
    AuthenticatedUser user = validator.validate(token);

    assertEquals("user-456", user.getSub());
    assertEquals("user-456", user.getPreferredUsername());
  }

  @Test
  void validate_ExpiredToken_ThrowsException() throws Exception {
    // Use -120s to exceed the 60s default clock skew tolerance in DefaultJWTClaimsVerifier
    String token = signToken(rsaKey, ISSUER, "user-123", "alice", -120);

    assertThrows(BadJWTException.class, () -> validator.validate(token));
  }

  @Test
  void validate_WrongIssuer_ThrowsException() throws Exception {
    String token =
        signToken(rsaKey, "https://other.example.com/realms/test", "user-123", "alice", 60);

    assertThrows(Exception.class, () -> validator.validate(token));
  }

  @Test
  void validate_DelegatesRoleExtractionToIamProvider() throws Exception {
    Set<String> expectedRoles = Set.of("groups.view", "users.edit");
    when(iamProviderService.extractRolesFromToken(any())).thenReturn(expectedRoles);

    String token = signToken(rsaKey, ISSUER, "user-789", "bob", 60);
    AuthenticatedUser user = validator.validate(token);

    assertEquals(expectedRoles, user.getRoles());
    verify(iamProviderService).extractRolesFromToken(any());
  }

  @Test
  void validate_PassesFullClaimsMapToIamProvider() throws Exception {
    when(iamProviderService.extractRolesFromToken(any())).thenReturn(Set.of());
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

    String token = signToken(rsaKey, ISSUER, "user-123", "alice", 60);
    validator.validate(token);

    verify(iamProviderService).extractRolesFromToken(captor.capture());
    Map<String, Object> capturedClaims = captor.getValue();
    assertEquals("user-123", capturedClaims.get("sub"));
    assertEquals(ISSUER, capturedClaims.get("iss"));
    assertEquals("alice", capturedClaims.get("preferred_username"));
  }

  @Test
  void validate_TokenSignedWithDifferentKey_ThrowsException() throws Exception {
    RSAKey differentKey = new RSAKeyGenerator(2048).keyID("other-key").generate();

    String token = signToken(differentKey, ISSUER, "user-123", "alice", 60);

    assertThrows(Exception.class, () -> validator.validate(token));
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void validate_TokenMissingSubClaim_ThrowsException() throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .expirationTime(new Date(System.currentTimeMillis() + 60_000L))
            .issueTime(new Date())
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(rsaKey));

    assertThrows(BadJWTException.class, () -> validator.validate(jwt.serialize()));
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void validate_MalformedToken_ThrowsException() {
    assertThrows(Exception.class, () -> validator.validate("not.a.jwt"));
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void validate_EmptyToken_ThrowsException() {
    assertThrows(Exception.class, () -> validator.validate(""));
    verifyNoInteractions(iamProviderService);
  }

  private static ConfigurableJWTProcessor<com.nimbusds.jose.proc.SecurityContext>
      buildTestProcessor(RSAKey rsaKey, String issuer) throws Exception {
    com.nimbusds.jose.jwk.source.ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext> jwkSource =
        new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(
            new com.nimbusds.jose.jwk.JWKSet(rsaKey.toPublicJWK()));

    ConfigurableJWTProcessor<com.nimbusds.jose.proc.SecurityContext> processor =
        new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(
        new com.nimbusds.jose.proc.JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
    processor.setJWTClaimsSetVerifier(
        new com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier<>(
            new JWTClaimsSet.Builder().issuer(issuer).build(),
            new java.util.HashSet<>(java.util.Arrays.asList("sub", "exp"))));
    return processor;
  }

  private static String signToken(
      RSAKey key, String issuer, String sub, String username, int expirySeconds) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(sub)
            .claim("preferred_username", username)
            .expirationTime(new Date(System.currentTimeMillis() + expirySeconds * 1000L))
            .issueTime(new Date())
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static String signTokenNoUsername(
      RSAKey key, String issuer, String sub, int expirySeconds) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(sub)
            .expirationTime(new Date(System.currentTimeMillis() + expirySeconds * 1000L))
            .issueTime(new Date())
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
