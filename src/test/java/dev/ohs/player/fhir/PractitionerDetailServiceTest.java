package dev.ohs.player.fhir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IGetPage;
import ca.uhn.fhir.rest.gclient.IGetPageTyped;
import java.util.ArrayList;
import java.util.List;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PractitionerDetailServiceTest {

  @Mock private FhirContext fhirContext;
  @Mock private IGenericClient fhirClient;
  @Mock private IGetPage getPage;
  @Mock private IGetPageTyped<Bundle> getPageTyped;

  private PractitionerDetailService service;

  private static final String FHIR_SERVER_URL = "http://fhir-server/fhir";
  private static final String PRACTITIONER_ID = "p1";
  private static final String ROLE_ID = "r1";
  private static final String ORG_ID = "o1";
  private static final String LOCATION_ID = "l1";
  private static final String CARE_TEAM_ID = "ct1";

  @BeforeEach
  void setUp() {
    service = new PractitionerDetailService(fhirContext, FHIR_SERVER_URL);
    lenient().when(fhirContext.newRestfulGenericClient(FHIR_SERVER_URL)).thenReturn(fhirClient);
  }

  // -------------------------------------------------------------------------
  // mapEntries — Bundle → PractitionerDetail mapping
  // -------------------------------------------------------------------------

  @Test
  void mapEntries_EmptyList_ReturnsNull() {
    assertNull(service.mapEntries(List.of()));
  }

  @Test
  void mapEntries_NoPractitioner_ReturnsNull() {
    PractitionerRole role = roleWithId(ROLE_ID);
    assertNull(service.mapEntries(entriesOf(role)));
  }

  @Test
  void mapEntries_PractitionerNoRoles_ReturnsDetailWithEmptyRoles() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner));
    assertNotNull(detail);
    assertEquals(practitioner, detail.getPractitioner());
    assertTrue(detail.getPractitionerRoles().isEmpty());
  }

  @Test
  void mapEntries_PractitionerAndRole_MapsCorrectly() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    PractitionerRole role = roleWithId(ROLE_ID);

    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner, role));

    assertNotNull(detail);
    assertEquals(practitioner, detail.getPractitioner());
    assertEquals(1, detail.getPractitionerRoles().size());
    assertEquals(role, detail.getPractitionerRoles().get(0).getPractitionerRole());
    assertNull(detail.getPractitionerRoles().get(0).getOrganization());
    assertTrue(detail.getPractitionerRoles().get(0).getLocations().isEmpty());
    assertTrue(detail.getPractitionerRoles().get(0).getCareTeams().isEmpty());
  }

  @Test
  void mapEntries_RoleWithOrganization_OrganizationIsResolved() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    Organization org = orgWithId(ORG_ID);
    PractitionerRole role = roleWithId(ROLE_ID);
    role.setOrganization(new Reference("Organization/" + ORG_ID));

    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner, role, org));

    assertNotNull(detail);
    assertEquals(org, detail.getPractitionerRoles().get(0).getOrganization());
  }

  @Test
  void mapEntries_RoleReferencesOrganizationNotInBundle_OrganizationIsNull() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    PractitionerRole role = roleWithId(ROLE_ID);
    role.setOrganization(new Reference("Organization/missing"));

    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner, role));

    assertNotNull(detail);
    assertNull(detail.getPractitionerRoles().get(0).getOrganization());
  }

  @Test
  void mapEntries_RoleWithLocation_LocationIsResolved() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    Location location = locationWithId(LOCATION_ID);
    PractitionerRole role = roleWithId(ROLE_ID);
    role.getLocation().add(new Reference("Location/" + LOCATION_ID));

    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner, role, location));

    assertNotNull(detail);
    assertEquals(1, detail.getPractitionerRoles().get(0).getLocations().size());
    assertEquals(location, detail.getPractitionerRoles().get(0).getLocations().get(0));
  }

  @Test
  void mapEntries_RoleWithMultipleLocations_AllLocationsResolved() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    Location loc1 = locationWithId("l1");
    Location loc2 = locationWithId("l2");
    PractitionerRole role = roleWithId(ROLE_ID);
    role.getLocation().add(new Reference("Location/l1"));
    role.getLocation().add(new Reference("Location/l2"));

    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner, role, loc1, loc2));

    assertEquals(2, detail.getPractitionerRoles().get(0).getLocations().size());
  }

  @Test
  void mapEntries_CareTeamParticipantReferencesRole_CareTeamScopedToRole() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    PractitionerRole role = roleWithId(ROLE_ID);
    CareTeam careTeam = careTeamReferencingRole(CARE_TEAM_ID, ROLE_ID);

    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner, role, careTeam));

    assertNotNull(detail);
    assertEquals(1, detail.getPractitionerRoles().get(0).getCareTeams().size());
    assertEquals(careTeam, detail.getPractitionerRoles().get(0).getCareTeams().get(0));
  }

  @Test
  void mapEntries_CareTeamParticipantReferencesDifferentRole_NotIncluded() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    PractitionerRole role = roleWithId(ROLE_ID);
    CareTeam careTeam = careTeamReferencingRole(CARE_TEAM_ID, "other-role");

    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner, role, careTeam));

    assertNotNull(detail);
    assertTrue(detail.getPractitionerRoles().get(0).getCareTeams().isEmpty());
  }

  @Test
  void mapEntries_CareTeamWithNoParticipantMember_NotIncluded() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    PractitionerRole role = roleWithId(ROLE_ID);
    CareTeam careTeam = new CareTeam();
    careTeam.setId(CARE_TEAM_ID);
    careTeam.addParticipant(); // participant with no member

    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner, role, careTeam));

    assertTrue(detail.getPractitionerRoles().get(0).getCareTeams().isEmpty());
  }

  @Test
  void mapEntries_MultipleRoles_EachRoleGetsCorrectResources() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);

    Organization org1 = orgWithId("o1");
    Organization org2 = orgWithId("o2");

    PractitionerRole role1 = roleWithId("r1");
    role1.setOrganization(new Reference("Organization/o1"));

    PractitionerRole role2 = roleWithId("r2");
    role2.setOrganization(new Reference("Organization/o2"));

    CareTeam ct1 = careTeamReferencingRole("ct1", "r1");
    CareTeam ct2 = careTeamReferencingRole("ct2", "r2");

    PractitionerDetail detail =
        service.mapEntries(entriesOf(practitioner, role1, role2, org1, org2, ct1, ct2));

    assertNotNull(detail);
    assertEquals(2, detail.getPractitionerRoles().size());

    PractitionerRoleDetail rd1 = detail.getPractitionerRoles().get(0);
    assertEquals("r1", rd1.getPractitionerRole().getIdElement().getIdPart());
    assertEquals(org1, rd1.getOrganization());
    assertEquals(1, rd1.getCareTeams().size());
    assertEquals(ct1, rd1.getCareTeams().get(0));

    PractitionerRoleDetail rd2 = detail.getPractitionerRoles().get(1);
    assertEquals("r2", rd2.getPractitionerRole().getIdElement().getIdPart());
    assertEquals(org2, rd2.getOrganization());
    assertEquals(1, rd2.getCareTeams().size());
    assertEquals(ct2, rd2.getCareTeams().get(0));
  }

  @Test
  void mapEntries_CareTeamWithAbsoluteReference_MatchedCorrectly() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    PractitionerRole role = roleWithId(ROLE_ID);
    CareTeam careTeam = new CareTeam();
    careTeam.setId(CARE_TEAM_ID);
    careTeam
        .addParticipant()
        .setMember(new Reference("http://fhir-server/fhir/PractitionerRole/" + ROLE_ID));

    PractitionerDetail detail = service.mapEntries(entriesOf(practitioner, role, careTeam));

    assertEquals(1, detail.getPractitionerRoles().get(0).getCareTeams().size());
  }

  // -------------------------------------------------------------------------
  // resolvePractitionerIdFromIamId — Step 1
  // -------------------------------------------------------------------------

  @Test
  void resolvePractitionerIdFromIamId_PractitionerFound_ReturnsId() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    Bundle bundle = new Bundle();
    bundle.addEntry().setResource(practitioner);
    when(fhirClient.fetchResourceFromUrl(eq(Bundle.class), any())).thenReturn(bundle);

    String result = service.resolvePractitionerIdFromIamId("iam-123");

    assertEquals(PRACTITIONER_ID, result);
  }

  @Test
  void resolvePractitionerIdFromIamId_NoPractitionerInBundle_ReturnsNull() {
    Bundle bundle = new Bundle();
    when(fhirClient.fetchResourceFromUrl(eq(Bundle.class), any())).thenReturn(bundle);

    String result = service.resolvePractitionerIdFromIamId("iam-unknown");

    assertNull(result);
  }

  // -------------------------------------------------------------------------
  // fetchPractitionerDetail — Step 2 with pagination
  // -------------------------------------------------------------------------

  @Test
  void fetchPractitionerDetail_SinglePage_MapsBundle() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    PractitionerRole role = roleWithId(ROLE_ID);
    Bundle bundle = new Bundle();
    bundle.addEntry().setResource(practitioner);
    bundle.addEntry().setResource(role);
    when(fhirClient.fetchResourceFromUrl(eq(Bundle.class), any())).thenReturn(bundle);

    PractitionerDetail detail = service.fetchPractitionerDetail(PRACTITIONER_ID, null, null);

    assertNotNull(detail);
    assertEquals(practitioner, detail.getPractitioner());
    assertEquals(1, detail.getPractitionerRoles().size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void fetchPractitionerDetail_MultiplePages_AggregatesAllEntries() {
    Practitioner practitioner = practitionerWithId(PRACTITIONER_ID);
    PractitionerRole role1 = roleWithId("r1");
    PractitionerRole role2 = roleWithId("r2");

    Bundle page1 = new Bundle();
    page1.addEntry().setResource(practitioner);
    page1.addEntry().setResource(role1);
    page1.addLink().setRelation("next").setUrl("http://fhir-server/fhir?page=2");

    Bundle page2 = new Bundle();
    page2.addEntry().setResource(role2);

    when(fhirClient.fetchResourceFromUrl(eq(Bundle.class), any())).thenReturn(page1);
    when(fhirClient.loadPage()).thenReturn(getPage);
    when(getPage.next(page1)).thenReturn(getPageTyped);
    when(getPageTyped.execute()).thenReturn(page2);

    PractitionerDetail detail = service.fetchPractitionerDetail(PRACTITIONER_ID, null, null);

    assertNotNull(detail);
    assertEquals(2, detail.getPractitionerRoles().size());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static Practitioner practitionerWithId(String id) {
    Practitioner p = new Practitioner();
    p.setId(id);
    return p;
  }

  private static PractitionerRole roleWithId(String id) {
    PractitionerRole r = new PractitionerRole();
    r.setId(id);
    return r;
  }

  private static Organization orgWithId(String id) {
    Organization o = new Organization();
    o.setId(id);
    return o;
  }

  private static Location locationWithId(String id) {
    Location l = new Location();
    l.setId(id);
    return l;
  }

  private static CareTeam careTeamReferencingRole(String careTeamId, String practitionerRoleId) {
    CareTeam ct = new CareTeam();
    ct.setId(careTeamId);
    ct.addParticipant().setMember(new Reference("PractitionerRole/" + practitionerRoleId));
    return ct;
  }

  private static List<Bundle.BundleEntryComponent> entriesOf(IBaseResource... resources) {
    List<Bundle.BundleEntryComponent> entries = new ArrayList<>();
    for (IBaseResource resource : resources) {
      Bundle.BundleEntryComponent entry = new Bundle.BundleEntryComponent();
      entry.setResource((org.hl7.fhir.r4.model.Resource) resource);
      entries.add(entry);
    }
    return entries;
  }
}
