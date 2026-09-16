package dev.ohs.player.plugins;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Unsigned JWTs for checker tests; only the payload is ever decoded. */
final class TestJwts {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private TestJwts() {}

  static DecodedJWT decoded(Map<String, Object> claims) {
    return JWT.decode(encoded(claims));
  }

  static String encoded(Map<String, Object> claims) {
    try {
      String header = segment(OBJECT_MAPPER.writeValueAsString(Map.of("alg", "none")));
      String payload = segment(OBJECT_MAPPER.writeValueAsString(claims));
      return header + "." + payload + ".";
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String segment(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
