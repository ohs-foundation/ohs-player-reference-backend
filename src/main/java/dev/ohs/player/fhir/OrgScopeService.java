package dev.ohs.player.fhir;

import com.github.benmanes.caffeine.cache.Cache;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Resource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves which organizations a caller may see, and checks whether a single resource lies inside a
 * scope.
 *
 * <p>The caller's IAM user id is matched to a Practitioner through its {@link
 * PractitionerService#KEYCLOAK_IDENTIFIER_SYSTEM} identifier. The Practitioner's {@code
 * PractitionerRole.organization} references give the direct organizations, which the {@link
 * OrganizationScopeExpander} then expands. Resolution fails closed: any problem yields {@link
 * OrgScope#denyAll()}. Definitive answers are cached per user id; failures are not, so a transient
 * upstream error does not lock a user out for the cache lifetime.
 */
public class OrgScopeService {

  private static final Logger logger = LoggerFactory.getLogger(OrgScopeService.class);

  private static final int ROLE_PAGE_SIZE = 100;

  private final Cache<String, OrgScope> scopeCache;
  private final OrganizationScopeExpander expander;

  public OrgScopeService(Cache<String, OrgScope> scopeCache, OrganizationScopeExpander expander) {
    this.scopeCache = scopeCache;
    this.expander = expander;
  }

  /**
   * Returns the caller's expanded scope, or {@link OrgScope#denyAll()} if it cannot be resolved.
   * Never throws.
   */
  public OrgScope resolveScope(@Nullable String iamUserId, GatewayFhirSearch search) {
    if (iamUserId == null || iamUserId.isBlank()) {
      logger.warn("Token has no IAM user id; denying all org-scoped access");
      return OrgScope.denyAll();
    }
    OrgScope cached = scopeCache.getIfPresent(iamUserId);
    if (cached != null) {
      return cached;
    }
    try {
      OrgScope scope = loadScope(iamUserId, search);
      scopeCache.put(iamUserId, scope);
      return scope;
    } catch (RuntimeException e) {
      logger.warn("Could not resolve organization scope for user {}; denying all", iamUserId, e);
      return OrgScope.denyAll();
    }
  }

  private OrgScope loadScope(String iamUserId, GatewayFhirSearch search) {
    Bundle practitioners =
        search.search(
            "Practitioner?identifier="
                + encode(PractitionerService.KEYCLOAK_IDENTIFIER_SYSTEM + "|" + iamUserId)
                + "&_elements=id&_count=1");
    if (practitioners.getEntry().isEmpty() || !practitioners.getEntryFirstRep().hasResource()) {
      logger.info("No Practitioner found for user {}; denying all", iamUserId);
      return OrgScope.denyAll();
    }
    String practitionerId =
        practitioners.getEntryFirstRep().getResource().getIdElement().getIdPart();

    Set<String> direct = new LinkedHashSet<>();
    for (Resource resource :
        search.searchAll(
            "PractitionerRole?practitioner="
                + encode("Practitioner/" + practitionerId)
                + "&_count="
                + ROLE_PAGE_SIZE)) {
      if (resource instanceof PractitionerRole) {
        PractitionerRole role = (PractitionerRole) resource;
        String orgId = role.getOrganization().getReferenceElement().getIdPart();
        if (orgId != null && !orgId.isBlank()) {
          direct.add(orgId);
        }
      }
    }
    if (direct.isEmpty()) {
      logger.info("Practitioner {} has no organization; denying all", practitionerId);
      return OrgScope.denyAll();
    }
    return OrgScope.of(expander.expand(direct, search));
  }

  /**
   * Whether {@code type/id} exists and matches {@code searchParam} for the scope.
   *
   * <p><b>Extension point — probe cache.</b> Every call is one upstream search. To cache results,
   * store only {@code true} answers and key them on the expanded scope (for example a hash of the
   * sorted organization ids) plus {@code type/id}. Never key on the user id: once a user's
   * organizations change, grants cached under that id would keep exposing the old organization's
   * data.
   *
   * <p><b>Extension point — versioned reads.</b> vread and history requests are checked against the
   * current version of the resource.
   *
   * @throws GatewayFhirSearch.UpstreamException if the probe fails
   */
  public boolean existsInScope(
      String type, String id, String searchParam, OrgScope scope, GatewayFhirSearch search) {
    if (scope.organizationIds().isEmpty()) {
      return false;
    }
    return hasMatch(search, scopedProbeUrl(type, id, searchParam, scope));
  }

  /**
   * Returns the current version of {@code type/id} if it exists and matches {@code searchParam} for
   * the scope, or {@code null} otherwise. Like {@link #existsInScope}, this is one upstream search,
   * but it returns the whole resource.
   *
   * @throws GatewayFhirSearch.UpstreamException if the search fails
   */
  public @Nullable Resource findInScope(
      String type, String id, String searchParam, OrgScope scope, GatewayFhirSearch search) {
    if (scope.organizationIds().isEmpty()) {
      return null;
    }
    for (Bundle.BundleEntryComponent entry :
        search.search(scopedProbeUrl(type, id, searchParam, scope) + "&_count=1").getEntry()) {
      Resource resource = entry.getResource();
      if (resource != null
          && type.equals(resource.fhirType())
          && id.equals(resource.getIdElement().getIdPart())) {
        return resource;
      }
    }
    return null;
  }

  /**
   * Whether {@code type/id} exists at all, regardless of scope.
   *
   * @throws GatewayFhirSearch.UpstreamException if the probe fails
   */
  public boolean exists(String type, String id, GatewayFhirSearch search) {
    return hasMatch(search, probeUrl(type, id));
  }

  private static boolean hasMatch(GatewayFhirSearch search, String url) {
    return !search.search(url + "&_elements=id&_count=1").getEntry().isEmpty();
  }

  private static String probeUrl(String type, String id) {
    return type + "?_id=" + encode(id);
  }

  private static String scopedProbeUrl(String type, String id, String searchParam, OrgScope scope) {
    return probeUrl(type, id) + "&" + searchParam + "=" + encode(scope.filterValue(searchParam));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
