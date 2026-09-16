package dev.ohs.player.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.fhir.gateway.HttpFhirClient;
import com.google.fhir.gateway.PatientFinderImp;
import com.google.fhir.gateway.interfaces.AccessChecker;
import com.google.fhir.gateway.interfaces.AccessDecision;
import com.google.fhir.gateway.interfaces.PatientFinder;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import com.google.fhir.gateway.interfaces.RequestMutation;
import dev.ohs.player.fhir.GatewayFhirSearch;
import dev.ohs.player.fhir.OrgScope;
import dev.ohs.player.fhir.OrgScopeService;
import dev.ohs.player.fhir.OrganizationScopeExpander;
import dev.ohs.player.iam.IamProviderService;
import dev.ohs.player.plugins.OrgScopedAccessChecker.DenyReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgScopedAccessCheckerTest {

  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4Cached();
  private static final PatientFinder PATIENT_FINDER = PatientFinderImp.getInstance(FHIR_CONTEXT);
  private static final OrgScope SCOPE_A = OrgScope.of(Set.of("org-a"));
  private static final String PATIENT_URN = "urn:uuid:11111111-1111-1111-1111-111111111111";

  @Mock private AccessChecker roleChecker;
  @Mock private OrgScopeService scopeService;
  @Mock private GatewayFhirSearch search;
  @Mock private RequestDetailsReader request;
  @Mock private IamProviderService iamProviderService;
  @Mock private HttpFhirClient httpFhirClient;

  @BeforeEach
  void grantRolesByDefault() {
    when(roleChecker.checkAccess(any())).thenReturn(new IamAccessDecision(true));
  }

  // ---- RBAC and scope preconditions ----

  @Test
  void checkAccess_RoleMissing_DeniesWithRoleMissing() {
    givenRequest(RequestTypeEnum.GET, "Patient", null, null);
    when(roleChecker.checkAccess(request)).thenReturn(new IamAccessDecision(false));

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.ROLE_MISSING);
  }

  @Test
  void checkAccess_NoOrganization_DeniesWithNoOrganization() {
    givenRequest(RequestTypeEnum.GET, "Patient", null, null);

    assertDenied(checker(OrgScope.denyAll()).checkAccess(request), DenyReason.NO_ORGANIZATION);
  }

  @Test
  void checkAccess_ExemptRole_GrantsWithoutMutation() {
    when(iamProviderService.extractRolesFromToken(any()))
        .thenReturn(Set.of("GET_PATIENT", "org_scope_exempt"));
    givenRequest(RequestTypeEnum.GET, "Patient", null, null);

    AccessDecision decision = factoryChecker(scopeService).checkAccess(request);

    assertTrue(decision.canAccess());
    assertNull(decision.getRequestMutation(request));
    verify(scopeService, never()).resolveScope(any(), any());
  }

  @Test
  void create_UpstreamFailure_ReturnsDenyingCheckerWithoutThrowing() throws IOException {
    when(iamProviderService.extractRolesFromToken(any())).thenReturn(Set.of("GET_PATIENT"));
    when(iamProviderService.extractUserIdFromToken(any())).thenReturn("user-1");
    when(httpFhirClient.getResource(anyString())).thenThrow(new IOException("store down"));
    OrgScopeService realService =
        new OrgScopeService(Caffeine.newBuilder().build(), OrganizationScopeExpander.DIRECT_ONLY);
    givenRequest(RequestTypeEnum.GET, "Patient", null, null);

    AccessChecker checker = factoryChecker(realService);

    assertDenied(checker.checkAccess(request), DenyReason.NO_ORGANIZATION);
  }

  // ---- Searches ----

  @Test
  void checkAccess_PatientSearch_InjectsSingleCommaJoinedOrganizationValue() {
    givenRequest(RequestTypeEnum.GET, "Patient", null, null);

    AccessDecision decision = checker(OrgScope.of(Set.of("org-b", "org-a"))).checkAccess(request);

    RequestMutation mutation = mutationOf(decision);
    assertEquals(
        Map.of("organization", List.of("Organization/org-a,Organization/org-b")),
        mutation.getAdditionalQueryParams());
    assertTrue(mutation.getDiscardQueryParams().contains("organization"));
  }

  @Test
  void checkAccess_OrganizationSearch_InjectsBareIds() {
    givenRequest(RequestTypeEnum.GET, "Organization", null, null);

    RequestMutation mutation = mutationOf(checker(SCOPE_A).checkAccess(request));

    assertEquals(Map.of("_id", List.of("org-a")), mutation.getAdditionalQueryParams());
  }

  @Test
  void checkAccess_EncounterSearch_InjectsPatientOrganizationAndDiscardsIncludes() {
    givenRequest(RequestTypeEnum.GET, "Encounter", null, null);

    RequestMutation mutation = mutationOf(checker(SCOPE_A).checkAccess(request));

    assertEquals(
        Map.of("patient.organization", List.of("Organization/org-a")),
        mutation.getAdditionalQueryParams());
    assertTrue(mutation.getDiscardQueryParams().containsAll(OrgScopeRules.DISCARD_PARAMS));
    assertTrue(mutation.getDiscardQueryParams().contains("patient.organization"));
  }

  @Test
  void checkAccess_UnscopedTypeSearch_GrantsWithoutMutation() {
    givenRequest(RequestTypeEnum.GET, "Practitioner", null, null);

    AccessDecision decision = checker(SCOPE_A).checkAccess(request);

    assertTrue(decision.canAccess());
    assertNull(decision.getRequestMutation(request));
  }

  @Test
  void checkAccess_Rule4TypeSearch_DeniesWithTypeNotScopable() {
    givenRequest(RequestTypeEnum.GET, "Group", null, null);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.TYPE_NOT_SCOPABLE);
  }

  // ---- Reads and deletes by id ----

  @Test
  void checkAccess_ReadOutOfScope_DeniesWithOutOfScope() {
    givenRequest(RequestTypeEnum.GET, "Encounter", "enc-b", null);
    givenInScope("Encounter", "enc-b", "patient.organization", false);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.OUT_OF_SCOPE);
  }

  @Test
  void checkAccess_ReadInScope_Grants() {
    givenRequest(RequestTypeEnum.GET, "Encounter", "enc-a", null);
    givenInScope("Encounter", "enc-a", "patient.organization", true);

    assertGranted(checker(SCOPE_A).checkAccess(request));
  }

  @Test
  void checkAccess_InstanceHistoryInScope_Grants() {
    givenRequest(RequestTypeEnum.GET, "Patient", "p-a", "_history");
    givenInScope("Patient", "p-a", "organization", true);

    assertGranted(checker(SCOPE_A).checkAccess(request));
  }

  @Test
  void checkAccess_DeleteOutOfScope_Denies() {
    givenRequest(RequestTypeEnum.DELETE, "Patient", "p-b", null);
    givenInScope("Patient", "p-b", "organization", false);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.OUT_OF_SCOPE);
  }

  @Test
  void checkAccess_OrganizationReadInScope_GrantsWithoutProbe() {
    givenRequest(RequestTypeEnum.GET, "Organization", "org-a", null);

    assertGranted(checker(SCOPE_A).checkAccess(request));
    verify(scopeService, never()).existsInScope(any(), any(), any(), any(), any());
  }

  @Test
  void checkAccess_ProbeFailsUpstream_DeniesWithUpstreamFailure() {
    givenRequest(RequestTypeEnum.GET, "Encounter", "enc-a", null);
    when(scopeService.existsInScope(any(), any(), any(), any(), any()))
        .thenThrow(new GatewayFhirSearch.UpstreamException("down"));

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.UPSTREAM_FAILURE);
  }

  // ---- Creates and updates ----

  @Test
  void checkAccess_CreatePatientInScope_Grants() {
    givenRequest(RequestTypeEnum.POST, "Patient", null, null);
    givenBody(patientManagedBy("org-a"));

    assertGranted(checker(SCOPE_A).checkAccess(request));
  }

  @Test
  void checkAccess_CreatePatientWithoutManagingOrganization_Denies() {
    givenRequest(RequestTypeEnum.POST, "Patient", null, null);
    givenBody(new Patient());

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.OUT_OF_SCOPE);
  }

  @Test
  void checkAccess_CreateEncounterForOutOfScopePatient_Denies() {
    givenRequest(RequestTypeEnum.POST, "Encounter", null, null);
    givenBody(encounterFor("Patient/p-b"));
    givenInScope("Patient", "p-b", "organization", false);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.OUT_OF_SCOPE);
  }

  @Test
  void checkAccess_CreateEncounterWithoutPatient_DeniesWithNoPatientReference() {
    givenRequest(RequestTypeEnum.POST, "Encounter", null, null);
    givenBody(new Encounter().setStatus(Encounter.EncounterStatus.PLANNED));

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.NO_PATIENT_REFERENCE);
  }

  @Test
  void checkAccess_CreateObservationWithPractitionerPerformer_ChecksOnlyThePatient() {
    Observation observation = new Observation();
    observation.setSubject(new Reference("Patient/p-a"));
    observation.addPerformer(new Reference("Practitioner/pr-1"));
    givenRequest(RequestTypeEnum.POST, "Observation", null, null);
    givenBody(observation);
    givenInScope("Patient", "p-a", "organization", true);

    assertGranted(checker(SCOPE_A).checkAccess(request));
    verify(scopeService, never()).existsInScope(eq("Patient"), eq("pr-1"), any(), any(), any());
  }

  @Test
  void checkAccess_CreateOrganization_DeniesWithTypeNotScopable() {
    givenRequest(RequestTypeEnum.POST, "Organization", null, null);
    givenBody(new org.hl7.fhir.r4.model.Organization());

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.TYPE_NOT_SCOPABLE);
  }

  @Test
  void checkAccess_UpdateNewResourceWithInScopeBody_Grants() {
    givenRequest(RequestTypeEnum.PUT, "Encounter", "enc-new", null);
    givenBody(encounterFor("Patient/p-a"));
    givenInScope("Patient", "p-a", "organization", true);
    givenInScope("Encounter", "enc-new", "patient.organization", false);
    when(scopeService.exists("Encounter", "enc-new", search)).thenReturn(false);

    assertGranted(checker(SCOPE_A).checkAccess(request));
  }

  @Test
  void checkAccess_UpdateOfExistingOutOfScopeResource_Denies() {
    givenRequest(RequestTypeEnum.PUT, "Encounter", "enc-b", null);
    givenBody(encounterFor("Patient/p-a"));
    givenInScope("Patient", "p-a", "organization", true);
    givenInScope("Encounter", "enc-b", "patient.organization", false);
    when(scopeService.exists("Encounter", "enc-b", search)).thenReturn(true);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.OUT_OF_SCOPE);
  }

  @Test
  void checkAccess_UpdateMovingResourceToOtherOrgPatient_Denies() {
    givenRequest(RequestTypeEnum.PUT, "Encounter", "enc-a", null);
    givenBody(encounterFor("Patient/p-b"));
    givenInScope("Patient", "p-b", "organization", false);
    givenInScope("Encounter", "enc-a", "patient.organization", true);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.OUT_OF_SCOPE);
  }

  @Test
  void checkAccess_BodyTypeDiffersFromPath_DeniesWithUnsupported() {
    givenRequest(RequestTypeEnum.POST, "Encounter", null, null);
    givenBody(patientManagedBy("org-a"));

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.UNSUPPORTED);
  }

  // ---- Unsupported interactions ----

  @Test
  void checkAccess_Patch_DeniesWithUnsupported() {
    givenRequest(RequestTypeEnum.PATCH, "Patient", "p-a", null);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.UNSUPPORTED);
  }

  @Test
  void checkAccess_Operation_DeniesWithUnsupported() {
    givenRequest(RequestTypeEnum.GET, "Patient", "p-a", "$everything");

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.UNSUPPORTED);
  }

  @Test
  void checkAccess_SystemLevelRequest_DeniesWithUnsupported() {
    givenRequest(RequestTypeEnum.GET, null, null, null);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.UNSUPPORTED);
  }

  // ---- Bundles ----

  @Test
  void checkAccess_BundlePatientPlusEncounterForSamePatient_Grants() {
    Bundle bundle = transaction();
    bundle
        .addEntry()
        .setFullUrl(PATIENT_URN)
        .setResource(patientManagedBy("org-a"))
        .getRequest()
        .setMethod(Bundle.HTTPVerb.POST)
        .setUrl("Patient");
    bundle
        .addEntry()
        .setResource(encounterFor(PATIENT_URN))
        .getRequest()
        .setMethod(Bundle.HTTPVerb.POST)
        .setUrl("Encounter");
    givenBundle(bundle);

    assertGranted(checker(SCOPE_A).checkAccess(request));
    verify(scopeService, never()).existsInScope(any(), any(), any(), any(), any());
  }

  @Test
  void checkAccess_BundleEncounterForUnknownUrnPatient_Denies() {
    Bundle bundle = transaction();
    bundle
        .addEntry()
        .setResource(encounterFor(PATIENT_URN))
        .getRequest()
        .setMethod(Bundle.HTTPVerb.POST)
        .setUrl("Encounter");
    givenBundle(bundle);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.OUT_OF_SCOPE);
  }

  @Test
  void checkAccess_BundleWithSearchEntry_Denies() {
    Bundle bundle = transaction();
    bundle
        .addEntry()
        .getRequest()
        .setMethod(Bundle.HTTPVerb.GET)
        .setUrl("Encounter?status=planned");
    givenBundle(bundle);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.UNSUPPORTED);
  }

  @Test
  void checkAccess_BundleOneEntryOutOfScope_DeniesWholeBundle() {
    Bundle bundle = transaction();
    bundle.addEntry().getRequest().setMethod(Bundle.HTTPVerb.DELETE).setUrl("Observation/obs-a");
    bundle.addEntry().getRequest().setMethod(Bundle.HTTPVerb.GET).setUrl("Encounter/enc-b");
    givenBundle(bundle);
    givenInScope("Observation", "obs-a", "patient.organization", true);
    givenInScope("Encounter", "enc-b", "patient.organization", false);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.OUT_OF_SCOPE);
  }

  @Test
  void checkAccess_BundleAllEntriesInScope_Grants() {
    Bundle bundle = transaction();
    bundle.addEntry().getRequest().setMethod(Bundle.HTTPVerb.GET).setUrl("/Encounter/enc-a");
    bundle.addEntry().getRequest().setMethod(Bundle.HTTPVerb.GET).setUrl("Patient/p-a/_history/2");
    givenBundle(bundle);
    givenInScope("Encounter", "enc-a", "patient.organization", true);
    givenInScope("Patient", "p-a", "organization", true);

    assertGranted(checker(SCOPE_A).checkAccess(request));
  }

  @Test
  void checkAccess_BundleConditionalDelete_Denies() {
    Bundle bundle = transaction();
    bundle.addEntry().getRequest().setMethod(Bundle.HTTPVerb.DELETE).setUrl("Patient?identifier=x");
    givenBundle(bundle);

    assertDenied(checker(SCOPE_A).checkAccess(request), DenyReason.UNSUPPORTED);
  }

  // ---- Audit ----

  @Test
  void getUserWho_ReturnsNonNullReference() {
    when(request.getHeader("Authorization"))
        .thenReturn(
            "Bearer "
                + TestJwts.encoded(
                    Map.of(
                        "sub", "user-1",
                        "iss", "https://issuer.example.com",
                        "preferred_username", "alice")));
    givenRequest(RequestTypeEnum.GET, "Practitioner", null, null);

    assertNotNull(checker(SCOPE_A).checkAccess(request).getUserWho(request));
  }

  // ---- Helpers ----

  private OrgScopedAccessChecker checker(OrgScope scope) {
    return new OrgScopedAccessChecker(
        roleChecker,
        scope,
        new OrgScopeRules(FHIR_CONTEXT),
        scopeService,
        search,
        PATIENT_FINDER,
        FHIR_CONTEXT);
  }

  // Uses the real RBAC checker, so roles come from iamProviderService.
  private AccessChecker factoryChecker(OrgScopeService service) {
    return new OrgScopedAccessChecker.Factory(iamProviderService, service)
        .create(
            TestJwts.decoded(Map.of("sub", "user-1", "iss", "https://issuer.example.com")),
            httpFhirClient,
            FHIR_CONTEXT,
            PATIENT_FINDER);
  }

  private void givenRequest(
      RequestTypeEnum method,
      @Nullable String type,
      @Nullable String id,
      @Nullable String operation) {
    when(request.getRequestType()).thenReturn(method);
    when(request.getResourceName()).thenReturn(type);
    when(request.getId()).thenReturn(id == null ? null : new IdType(type, id));
    when(request.getOperation()).thenReturn(operation);
    when(request.getRequestPath())
        .thenReturn(type == null ? "" : type + (id == null ? "" : "/" + id));
  }

  private void givenBody(Resource resource) {
    when(request.getCharset()).thenReturn(StandardCharsets.UTF_8);
    when(request.loadRequestContents())
        .thenReturn(
            FHIR_CONTEXT
                .newJsonParser()
                .encodeResourceToString(resource)
                .getBytes(StandardCharsets.UTF_8));
  }

  private void givenBundle(Bundle bundle) {
    givenRequest(RequestTypeEnum.POST, null, null, null);
    givenBody(bundle);
  }

  private void givenInScope(String type, String id, String param, boolean inScope) {
    when(scopeService.existsInScope(eq(type), eq(id), eq(param), any(), eq(search)))
        .thenReturn(inScope);
  }

  private static Bundle transaction() {
    return new Bundle().setType(Bundle.BundleType.TRANSACTION);
  }

  private static Patient patientManagedBy(String organizationId) {
    Patient patient = new Patient();
    patient.setManagingOrganization(new Reference("Organization/" + organizationId));
    return patient;
  }

  private static Encounter encounterFor(String patientReference) {
    Encounter encounter = new Encounter().setStatus(Encounter.EncounterStatus.PLANNED);
    encounter.setSubject(new Reference(patientReference));
    return encounter;
  }

  private static RequestMutation mutationOf(AccessDecision decision) {
    assertTrue(decision.canAccess());
    RequestMutation mutation = decision.getRequestMutation(null);
    assertNotNull(mutation);
    return mutation;
  }

  private static void assertGranted(AccessDecision decision) {
    assertTrue(
        decision.canAccess(), () -> "denied: " + ((OrgScopedDecision) decision).getDenyReason());
  }

  private static void assertDenied(AccessDecision decision, DenyReason reason) {
    assertFalse(decision.canAccess());
    assertEquals(reason, ((OrgScopedDecision) decision).getDenyReason());
  }
}
