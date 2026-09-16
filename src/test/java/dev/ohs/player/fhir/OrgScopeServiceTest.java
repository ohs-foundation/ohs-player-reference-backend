package dev.ohs.player.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Set;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrgScopeServiceTest {

  private static final String USER_ID = "user-1";
  private static final String PRACTITIONER_URL =
      "Practitioner?identifier=http%3A%2F%2Fohs.dev%2Fidentifiers%2Fkeycloak-user-id%7Cuser-1"
          + "&_elements=id&_count=1";
  private static final String ROLES_URL =
      "PractitionerRole?practitioner=Practitioner%2Fpr-1&_count=100";

  @Mock private GatewayFhirSearch search;

  private Cache<String, OrgScope> cache;
  private OrgScopeService service;

  @BeforeEach
  void setUp() {
    cache = Caffeine.newBuilder().build();
    service = new OrgScopeService(cache, OrganizationScopeExpander.DIRECT_ONLY);
  }

  @Test
  void resolveScope_TwoRoles_ReturnsUnionOfOrganizations() {
    when(search.search(PRACTITIONER_URL)).thenReturn(bundleOf(new Practitioner().setId("pr-1")));
    when(search.searchAll(ROLES_URL)).thenReturn(List.of(role("org-a"), role("org-b")));

    OrgScope scope = service.resolveScope(USER_ID, search);

    assertEquals(Set.of("org-a", "org-b"), scope.organizationIds());
    assertEquals(scope, cache.getIfPresent(USER_ID));
  }

  @Test
  void resolveScope_UsesExpander() {
    service = new OrgScopeService(cache, (direct, s) -> Set.of("org-a", "child-of-a"));
    when(search.search(PRACTITIONER_URL)).thenReturn(bundleOf(new Practitioner().setId("pr-1")));
    when(search.searchAll(ROLES_URL)).thenReturn(List.of(role("org-a")));

    assertEquals(
        Set.of("org-a", "child-of-a"), service.resolveScope(USER_ID, search).organizationIds());
  }

  @Test
  void resolveScope_CacheHit_MakesNoUpstreamCall() {
    OrgScope cached = OrgScope.of(Set.of("org-a"));
    cache.put(USER_ID, cached);

    assertEquals(cached, service.resolveScope(USER_ID, search));
    verifyNoInteractions(search);
  }

  @Test
  void resolveScope_NoPractitioner_ReturnsCachedDenyAll() {
    when(search.search(PRACTITIONER_URL)).thenReturn(new Bundle());

    OrgScope scope = service.resolveScope(USER_ID, search);

    assertTrue(scope.isEmpty());
    assertEquals(OrgScope.denyAll(), cache.getIfPresent(USER_ID));
    verify(search, never()).searchAll(anyString());
  }

  @Test
  void resolveScope_NoOrganization_ReturnsCachedDenyAll() {
    when(search.search(PRACTITIONER_URL)).thenReturn(bundleOf(new Practitioner().setId("pr-1")));
    when(search.searchAll(ROLES_URL)).thenReturn(List.of(new PractitionerRole()));

    assertTrue(service.resolveScope(USER_ID, search).isEmpty());
    assertEquals(OrgScope.denyAll(), cache.getIfPresent(USER_ID));
  }

  @Test
  void resolveScope_UpstreamFailure_ReturnsUncachedDenyAll() {
    when(search.search(anyString()))
        .thenThrow(new GatewayFhirSearch.UpstreamException("store down"));

    assertTrue(service.resolveScope(USER_ID, search).isEmpty());
    assertNull(cache.getIfPresent(USER_ID));
  }

  @Test
  void resolveScope_BlankUserId_ReturnsDenyAllWithoutUpstreamCall() {
    assertTrue(service.resolveScope(" ", search).isEmpty());
    assertTrue(service.resolveScope(null, search).isEmpty());
    verifyNoInteractions(search);
  }

  @Test
  void existsInScope_SendsIdAndScopeFilterInOneProbe() {
    OrgScope scope = OrgScope.of(Set.of("b", "a"));
    when(search.search(
            "Encounter?_id=enc-1&patient.organization=Organization%2Fa%2COrganization%2Fb"
                + "&_elements=id&_count=1"))
        .thenReturn(bundleOf(new Patient()));

    assertTrue(service.existsInScope("Encounter", "enc-1", "patient.organization", scope, search));
  }

  @Test
  void existsInScope_NoMatch_ReturnsFalse() {
    when(search.search(anyString())).thenReturn(new Bundle());

    assertFalse(
        service.existsInScope("Patient", "p-1", "organization", OrgScope.of(Set.of("a")), search));
  }

  @Test
  void existsInScope_EmptyScope_ReturnsFalseWithoutProbe() {
    assertFalse(
        service.existsInScope("Patient", "p-1", "organization", OrgScope.denyAll(), search));
    verifyNoInteractions(search);
  }

  @Test
  void exists_SendsIdOnlyProbe() {
    when(search.search("Patient?_id=p-1&_elements=id&_count=1")).thenReturn(new Bundle());

    assertFalse(service.exists("Patient", "p-1", search));
  }

  @Test
  void exists_UpstreamFailure_Propagates() {
    when(search.search(any())).thenThrow(new GatewayFhirSearch.UpstreamException("down"));

    assertThrows(
        GatewayFhirSearch.UpstreamException.class, () -> service.exists("Patient", "p", search));
  }

  private static Bundle bundleOf(org.hl7.fhir.r4.model.Resource resource) {
    Bundle bundle = new Bundle();
    bundle.addEntry().setResource(resource);
    return bundle;
  }

  private static PractitionerRole role(String organizationId) {
    return new PractitionerRole().setOrganization(new Reference("Organization/" + organizationId));
  }
}
