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

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.fhir.gateway.FhirUtil;
import com.google.fhir.gateway.HttpFhirClient;
import com.google.fhir.gateway.interfaces.AccessChecker;
import com.google.fhir.gateway.interfaces.AccessCheckerFactory;
import com.google.fhir.gateway.interfaces.AccessDecision;
import com.google.fhir.gateway.interfaces.PatientFinder;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import dev.ohs.player.iam.IamProviderService;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Named;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A simple role-based access checker (RBAC) for the FHIR proxy. It expects the configured IAM
 * provider to issue one role per "HTTP verb + FHIR resource type" combination that a user is
 * allowed to perform, e.g. a user allowed to read Encounters has the role {@code GET_ENCOUNTER} and
 * a user allowed to delete Patients has the role {@code DELETE_PATIENT}.
 *
 * <p>For a plain (non-Bundle) request, access is granted iff the caller's roles contain {@code
 * <verb>_<resourceType>} for the request being made. For a Bundle (batch/transaction), each entry
 * is checked the same way and the whole Bundle is granted only if every entry is individually
 * authorized.
 *
 * <p>Roles are resolved via the injected {@link IamProviderService#extractRolesFromToken}, the same
 * abstraction {@link dev.ohs.player.auth.JwtTokenValidator} uses for the {@code /api/*} endpoints.
 * This checker is therefore agnostic to which IAM provider is configured; only that provider's
 * {@code extractRolesFromToken} implementation needs to know where roles live in its own token
 * shape.
 *
 * <p>The grant/deny outcome is wrapped in an {@link IamAccessDecision}, which is also what makes
 * the Gateway able to emit AuditEvents for these requests; see that class for details.
 */
public class OhsPlayerAccessChecker implements AccessChecker {

  private static final Logger logger = LoggerFactory.getLogger(OhsPlayerAccessChecker.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final FhirContext fhirContext;
  private final Set<String> userRoles;

  private OhsPlayerAccessChecker(FhirContext fhirContext, Set<String> userRoles) {
    this.fhirContext = fhirContext;
    this.userRoles = userRoles;
  }

  @Override
  public AccessDecision checkAccess(RequestDetailsReader requestDetails) {
    // For a Bundle, requestDetails.getResourceName() is null and each entry must be checked
    // individually to reach a final AccessDecision for the whole request.
    if (requestDetails.getRequestType() == RequestTypeEnum.POST
        && requestDetails.getResourceName() == null) {
      return checkBundleAccess(requestDetails);
    }
    return new IamAccessDecision(
        hasRequiredRole(requestDetails.getRequestType(), requestDetails.getResourceName()));
  }

  private AccessDecision checkBundleAccess(RequestDetailsReader requestDetails) {
    Bundle requestBundle = FhirUtil.parseRequestToBundle(fhirContext, requestDetails);
    for (Bundle.BundleEntryComponent entry : requestBundle.getEntry()) {
      if (!isBundleEntryAuthorized(entry)) {
        return new IamAccessDecision(false);
      }
    }
    return new IamAccessDecision(true);
  }

  private boolean isBundleEntryAuthorized(Bundle.BundleEntryComponent entry) {
    Bundle.BundleEntryRequestComponent entryRequest = entry.getRequest();
    if (entryRequest == null || entryRequest.getMethod() == null) {
      return false;
    }
    RequestTypeEnum verb = RequestTypeEnum.valueOf(entryRequest.getMethod().name());

    // For entries that carry a resource body (typically POST/PUT/PATCH) the resource type is read
    // straight from the body; this is the only reliable source for a plain "POST /Patient" create,
    // whose URL has no resource id to parse a reference out of.
    if (entry.hasResource() && entry.getResource().getResourceType() != null) {
      return hasRequiredRole(verb, entry.getResource().getResourceType().name());
    }
    if (Strings.isNullOrEmpty(entryRequest.getUrl())) {
      return false;
    }
    try {
      URI uri = new URI(entryRequest.getUrl());
      IIdType refElement = new Reference(uri.getPath()).getReferenceElement();
      String resourceType =
          refElement.getResourceType() != null
              ? refElement.getResourceType()
              : refElement.getValue();
      return hasRequiredRole(verb, resourceType);
    } catch (URISyntaxException e) {
      logger.error("Error parsing bundle entry request url {}", entryRequest.getUrl());
      return false;
    }
  }

  private boolean hasRequiredRole(@Nullable RequestTypeEnum verb, @Nullable String resourceType) {
    if (verb == null || Strings.isNullOrEmpty(resourceType)) {
      return false;
    }
    String requiredRole = roleFor(verb, resourceType);
    boolean granted = userRoles.contains(requiredRole);
    if (!granted) {
      logger.info(
          "Access denied; user roles {} do not contain required role {}", userRoles, requiredRole);
    }
    return granted;
  }

  private static String roleFor(RequestTypeEnum verb, String resourceType) {
    return String.format("%s_%s", verb.name(), resourceType.toUpperCase(Locale.ROOT));
  }

  /**
   * Decodes the JWT payload into a raw claims map so it can be handed to the IAM-agnostic {@link
   * IamProviderService#extractRolesFromToken}, mirroring how {@link
   * dev.ohs.player.auth.JwtTokenValidator} obtains claims from a (Nimbus-parsed) token for the
   * {@code /api/*} endpoints.
   */
  private static Map<String, Object> extractClaims(DecodedJWT jwt) {
    try {
      byte[] payloadJson = Base64.getUrlDecoder().decode(jwt.getPayload());
      return OBJECT_MAPPER.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
    } catch (IOException | IllegalArgumentException e) {
      throw new AuthenticationException("Failed to parse JWT claims", e);
    }
  }

  @Named("ohs_player_access")
  public static class Factory implements AccessCheckerFactory {

    private final IamProviderService iamProviderService;

    @Autowired
    public Factory(IamProviderService iamProviderService) {
      this.iamProviderService = iamProviderService;
    }

    @Override
    public AccessChecker create(
        DecodedJWT jwt,
        HttpFhirClient httpFhirClient,
        FhirContext fhirContext,
        PatientFinder patientFinder)
        throws AuthenticationException {
      Set<String> roles =
          iamProviderService.extractRolesFromToken(extractClaims(jwt)).stream()
              .map(role -> role.toUpperCase(Locale.ROOT))
              .collect(Collectors.toSet());
      if (roles.isEmpty()) {
        logger.warn(
            "The configured IAM provider returned no roles for the provided token; all requests"
                + " will be denied.");
      }
      return new OhsPlayerAccessChecker(fhirContext, roles);
    }
  }
}
