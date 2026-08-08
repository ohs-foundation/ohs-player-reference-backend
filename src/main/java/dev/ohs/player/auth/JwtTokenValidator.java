package dev.ohs.player.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSAlgorithmFamilyJWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.ohs.player.iam.IamProviderService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwtTokenValidator {

  private static final Logger logger = LoggerFactory.getLogger(JwtTokenValidator.class);
  private static final Pattern JWKS_URI_PATTERN =
      Pattern.compile("\"jwks_uri\"\\s*:\\s*\"([^\"]+)\"");

  static final int HTTP_CONNECT_TIMEOUT_MS = 5_000;
  static final int HTTP_READ_TIMEOUT_MS = 10_000;

  private final IamProviderService iamProviderService;
  private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

  public JwtTokenValidator(String tokenIssuer, IamProviderService iamProviderService) {
    this.iamProviderService = iamProviderService;
    this.jwtProcessor = buildJwtProcessor(tokenIssuer);
  }

  /** Test constructor — accepts a pre-built processor to avoid network calls. */
  JwtTokenValidator(
      ConfigurableJWTProcessor<SecurityContext> jwtProcessor,
      IamProviderService iamProviderService) {
    this.jwtProcessor = jwtProcessor;
    this.iamProviderService = iamProviderService;
  }

  private static ConfigurableJWTProcessor<SecurityContext> buildJwtProcessor(String tokenIssuer) {
    try {
      String jwksUri = discoverJwksUri(tokenIssuer);
      logger.info("Discovered JWKS URI: {}", jwksUri);

      JWKSource<SecurityContext> jwkSource =
          JWKSourceBuilder.<SecurityContext>create(
                  new URL(jwksUri),
                  new DefaultResourceRetriever(HTTP_CONNECT_TIMEOUT_MS, HTTP_READ_TIMEOUT_MS))
              .retrying(true)
              .build();

      ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
      processor.setJWSKeySelector(
          new JWSAlgorithmFamilyJWSKeySelector<>(JWSAlgorithm.Family.RSA, jwkSource));
      processor.setJWTClaimsSetVerifier(
          new DefaultJWTClaimsVerifier<>(
              new JWTClaimsSet.Builder().issuer(tokenIssuer).build(),
              new HashSet<>(Arrays.asList("sub", "exp"))));

      return processor;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to initialize JWT processor via OIDC discovery for issuer: " + tokenIssuer, e);
    }
  }

  private static String discoverJwksUri(String tokenIssuer) throws Exception {
    String normalized =
        tokenIssuer.endsWith("/")
            ? tokenIssuer.substring(0, tokenIssuer.length() - 1)
            : tokenIssuer;
    URL discoveryUrl = new URL(normalized + "/.well-known/openid-configuration");

    HttpURLConnection conn = (HttpURLConnection) discoveryUrl.openConnection();
    conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);

    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
    } finally {
      conn.disconnect();
    }

    Matcher matcher = JWKS_URI_PATTERN.matcher(sb.toString());
    if (matcher.find()) {
      return matcher.group(1);
    }
    throw new IllegalStateException(
        "jwks_uri not found in OIDC discovery response from " + discoveryUrl);
  }

  public AuthenticatedUser validate(String token) throws Exception {
    JWTClaimsSet claims = jwtProcessor.process(token, null);
    String sub = claims.getSubject();
    String preferredUsername = claims.getStringClaim("preferred_username");
    Set<String> roles = iamProviderService.extractRolesFromToken(claims.getClaims());
    String iamId = iamProviderService.extractUserIdFromToken(claims.getClaims());
    return new AuthenticatedUser(iamId, preferredUsername != null ? preferredUsername : sub, roles);
  }
}
