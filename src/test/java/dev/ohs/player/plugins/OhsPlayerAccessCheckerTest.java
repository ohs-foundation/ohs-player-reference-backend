/*
 * Copyright 2021-2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.player.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.fhir.gateway.interfaces.AccessChecker;
import com.google.fhir.gateway.interfaces.AccessDecision;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import dev.ohs.player.iam.IamProviderService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OhsPlayerAccessCheckerTest {

  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String BUNDLE_GET_ENCOUNTER_SEARCH =
      "{"
          + "\"resourceType\": \"Bundle\","
          + "\"type\": \"transaction\","
          + "\"entry\": [ { \"request\": "
          + "{ \"method\": \"GET\", \"url\": \"Encounter?patient=Patient/123\" } } ]"
          + "}";

  private static final String BUNDLE_POST_PATIENT_CREATE =
      "{"
          + "\"resourceType\": \"Bundle\","
          + "\"type\": \"transaction\","
          + "\"entry\": [ {"
          + "  \"resource\": { \"resourceType\": \"Patient\", \"name\": [ { \"family\": \"Doe\" } ] },"
          + "  \"request\": { \"method\": \"POST\", \"url\": \"Patient\" }"
          + "} ]"
          + "}";

  private static final String BUNDLE_DELETE_OBSERVATION =
      "{"
          + "\"resourceType\": \"Bundle\","
          + "\"type\": \"transaction\","
          + "\"entry\": [ { \"request\": "
          + "{ \"method\": \"DELETE\", \"url\": \"Observation/456\" } } ]"
          + "}";

  @Mock private IamProviderService iamProviderService;
  @Mock private RequestDetailsReader requestMock;

  private AccessChecker createCheckerWithRoles(String... roles) {
    when(iamProviderService.extractRolesFromToken(any())).thenReturn(Set.of(roles));
    return new OhsPlayerAccessChecker.Factory(iamProviderService)
        .create(
            fakeDecodedJwt(Map.of("sub", "test-user", "iss", "https://issuer.example.com")),
            null,
            FHIR_CONTEXT,
            null);
  }

  private void setUpBundleRequest(String bundleJson) {
    when(requestMock.getResourceName()).thenReturn(null);
    when(requestMock.getRequestType()).thenReturn(RequestTypeEnum.POST);
    when(requestMock.loadRequestContents()).thenReturn(bundleJson.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void checkAccess_GetWithMatchingRole_Grants() {
    when(requestMock.getResourceName()).thenReturn("Encounter");
    when(requestMock.getRequestType()).thenReturn(RequestTypeEnum.GET);
    AccessChecker checker = createCheckerWithRoles("GET_ENCOUNTER");
    assertTrue(checker.checkAccess(requestMock).canAccess());
  }

  @Test
  void checkAccess_DeleteWithoutMatchingRole_Denies() {
    when(requestMock.getResourceName()).thenReturn("Patient");
    when(requestMock.getRequestType()).thenReturn(RequestTypeEnum.DELETE);
    AccessChecker checker = createCheckerWithRoles("GET_ENCOUNTER");
    assertFalse(checker.checkAccess(requestMock).canAccess());
  }

  @Test
  void checkAccess_NoRolesFromIamProvider_Denies() {
    when(requestMock.getResourceName()).thenReturn("Patient");
    when(requestMock.getRequestType()).thenReturn(RequestTypeEnum.GET);
    AccessChecker checker = createCheckerWithRoles();
    assertFalse(checker.checkAccess(requestMock).canAccess());
  }

  @Test
  void checkAccess_DeleteWithMatchingRole_Grants() {
    when(requestMock.getResourceName()).thenReturn("Patient");
    when(requestMock.getRequestType()).thenReturn(RequestTypeEnum.DELETE);
    AccessChecker checker = createCheckerWithRoles("DELETE_PATIENT");
    assertTrue(checker.checkAccess(requestMock).canAccess());
  }

  @Test
  void checkAccess_BundleAllEntriesAuthorized_Grants() {
    // POST / -d BUNDLE_GET_ENCOUNTER_SEARCH (a GET Encounter?patient=... entry)
    setUpBundleRequest(BUNDLE_GET_ENCOUNTER_SEARCH);
    AccessChecker checker = createCheckerWithRoles("GET_ENCOUNTER");
    assertTrue(checker.checkAccess(requestMock).canAccess());
  }

  @Test
  void checkAccess_BundleOneEntryUnauthorized_Denies() {
    setUpBundleRequest(BUNDLE_GET_ENCOUNTER_SEARCH);
    // Caller only has an unrelated role, not GET_ENCOUNTER.
    AccessChecker checker = createCheckerWithRoles("GET_PATIENT");
    assertFalse(checker.checkAccess(requestMock).canAccess());
  }

  @Test
  void checkAccess_BundlePostCreateUsesResourceBodyType_Grants() {
    // POST / -d BUNDLE_POST_PATIENT_CREATE (a POST Patient with no id in the URL)
    setUpBundleRequest(BUNDLE_POST_PATIENT_CREATE);
    AccessChecker checker = createCheckerWithRoles("POST_PATIENT");
    assertTrue(checker.checkAccess(requestMock).canAccess());
  }

  @Test
  void checkAccess_BundleDeleteNonPatientEntry_Grants() {
    // POST / -d BUNDLE_DELETE_OBSERVATION (a DELETE Observation entry)
    setUpBundleRequest(BUNDLE_DELETE_OBSERVATION);
    AccessChecker checker = createCheckerWithRoles("DELETE_OBSERVATION");
    assertTrue(checker.checkAccess(requestMock).canAccess());
  }

  @Test
  void create_PassesDecodedJwtClaimsToIamProvider() {
    when(iamProviderService.extractRolesFromToken(any())).thenReturn(Set.of());
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

    new OhsPlayerAccessChecker.Factory(iamProviderService)
        .create(
            fakeDecodedJwt(Map.of("sub", "user-123", "iss", "https://issuer.example.com")),
            null,
            FHIR_CONTEXT,
            null);

    verify(iamProviderService).extractRolesFromToken(captor.capture());
    Map<String, Object> capturedClaims = captor.getValue();
    assertEquals("user-123", capturedClaims.get("sub"));
    assertEquals("https://issuer.example.com", capturedClaims.get("iss"));
  }

  @Test
  void getUserWho_ReturnsNonNullReference_SoAuditEventsCanBeEmitted() {
    when(requestMock.getHeader("Authorization"))
        .thenReturn(
            "Bearer "
                + fakeJwt(
                    Map.of(
                        "sub", "test-user",
                        "iss", "https://issuer.example.com",
                        "preferred_username", "alice")));
    when(requestMock.getResourceName()).thenReturn("Encounter");
    when(requestMock.getRequestType()).thenReturn(RequestTypeEnum.GET);

    AccessDecision decision = createCheckerWithRoles("GET_ENCOUNTER").checkAccess(requestMock);

    assertNotNull(decision.getUserWho(requestMock));
  }

  private static DecodedJWT fakeDecodedJwt(Map<String, Object> claims) {
    return JWT.decode(fakeJwt(claims));
  }

  // A syntactically valid (unsigned) JWT carrying the given claims; signature is never checked
  // here, only the payload is decoded (both by our code and by JwtUtil in production).
  private static String fakeJwt(Map<String, Object> claims) {
    try {
      String header = encodeSegment(OBJECT_MAPPER.writeValueAsString(Map.of("alg", "none")));
      String payload = encodeSegment(OBJECT_MAPPER.writeValueAsString(claims));
      return header + "." + payload + ".";
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String encodeSegment(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
