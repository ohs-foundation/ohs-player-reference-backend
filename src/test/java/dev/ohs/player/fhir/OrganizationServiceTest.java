package dev.ohs.player.fhir;

import static dev.ohs.player.fhir.SearchClientMocks.FHIR_SERVER_URL;
import static dev.ohs.player.fhir.SearchClientMocks.bundleWith;
import static dev.ohs.player.fhir.SearchClientMocks.emptyBundle;
import static dev.ohs.player.fhir.SearchClientMocks.organization;
import static org.junit.jupiter.api.Assertions.*;

import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrganizationServiceTest {

  private OrganizationService service;

  @BeforeEach
  void setUp() {
    service =
        new OrganizationService(
            ca.uhn.fhir.context.FhirContext.forR4Cached(), "http://fhir.example.com");
  }

  @Test
  void buildOrganization_setsNameAndActive() {
    OrgData data = new OrgData();
    data.setName("Test Org");

    Organization org = service.buildOrganization(data);

    assertEquals("Test Org", org.getName());
    assertTrue(org.getActive());
  }

  @Test
  void buildOrganization_withSourceId_addsIdentifier() {
    OrgData data = new OrgData();
    data.setName("Test Org");
    data.setSourceId("SRC-001");

    Organization org = service.buildOrganization(data);

    assertEquals(1, org.getIdentifier().size());
    assertEquals(
        OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM, org.getIdentifier().get(0).getSystem());
    assertEquals("SRC-001", org.getIdentifier().get(0).getValue());
  }

  @Test
  void buildOrganization_withoutSourceId_noIdentifier() {
    OrgData data = new OrgData();
    data.setName("Test Org");

    Organization org = service.buildOrganization(data);

    assertTrue(org.getIdentifier().isEmpty());
  }

  @Test
  void buildOrganization_isTeamTrue_addsTypeCodingTeam() {
    OrgData data = new OrgData();
    data.setName("Team Alpha");
    data.setTeam(true);

    Organization org = service.buildOrganization(data);

    assertEquals(1, org.getType().size());
    assertEquals(1, org.getTypeFirstRep().getCoding().size());
    assertEquals(
        "http://terminology.hl7.org/CodeSystem/organization-type",
        org.getTypeFirstRep().getCodingFirstRep().getSystem());
    assertEquals("team", org.getTypeFirstRep().getCodingFirstRep().getCode());
  }

  @Test
  void buildOrganization_isTeamFalse_noType() {
    OrgData data = new OrgData();
    data.setName("Test Org");
    data.setTeam(false);

    Organization org = service.buildOrganization(data);

    assertTrue(org.getType().isEmpty());
  }

  @Test
  void buildOrganization_withPhone_addsPhoneWorkContactPoint() {
    OrgData data = new OrgData();
    data.setName("Test Org");
    data.setPhone("+254700000000");

    Organization org = service.buildOrganization(data);

    assertEquals(1, org.getTelecom().size());
    ContactPoint cp = org.getTelecom().get(0);
    assertEquals(ContactPoint.ContactPointSystem.PHONE, cp.getSystem());
    assertEquals(ContactPoint.ContactPointUse.WORK, cp.getUse());
    assertEquals("+254700000000", cp.getValue());
  }

  @Test
  void buildOrganization_withEmail_addsEmailWorkContactPoint() {
    OrgData data = new OrgData();
    data.setName("Test Org");
    data.setEmail("org@example.com");

    Organization org = service.buildOrganization(data);

    assertEquals(1, org.getTelecom().size());
    ContactPoint cp = org.getTelecom().get(0);
    assertEquals(ContactPoint.ContactPointSystem.EMAIL, cp.getSystem());
    assertEquals(ContactPoint.ContactPointUse.WORK, cp.getUse());
    assertEquals("org@example.com", cp.getValue());
  }

  @Test
  void buildOrganization_withPhoneAndEmail_addsBothContactPoints() {
    OrgData data = new OrgData();
    data.setName("Test Org");
    data.setPhone("+254700000000");
    data.setEmail("org@example.com");

    Organization org = service.buildOrganization(data);

    assertEquals(2, org.getTelecom().size());
  }

  @Test
  void buildOrganization_withPhysicalAddress_addsWorkPhysicalAddress() {
    OrgData data = new OrgData();
    data.setName("Test Org");
    data.setPhysicalAddress("123 Main Street, Nairobi");

    Organization org = service.buildOrganization(data);

    assertEquals(1, org.getAddress().size());
    Address addr = org.getAddress().get(0);
    assertEquals(Address.AddressUse.WORK, addr.getUse());
    assertEquals(Address.AddressType.PHYSICAL, addr.getType());
    assertEquals("123 Main Street, Nairobi", addr.getText());
  }

  @Test
  void buildOrganization_withPostalAddress_addsWorkPostalAddress() {
    OrgData data = new OrgData();
    data.setName("Test Org");
    data.setPostalAddress("P.O. Box 12345, Nairobi");

    Organization org = service.buildOrganization(data);

    assertEquals(1, org.getAddress().size());
    Address addr = org.getAddress().get(0);
    assertEquals(Address.AddressUse.WORK, addr.getUse());
    assertEquals(Address.AddressType.POSTAL, addr.getType());
    assertEquals("P.O. Box 12345, Nairobi", addr.getText());
  }

  @Test
  void buildOrganization_withBothAddresses_addsTwoAddressEntries() {
    OrgData data = new OrgData();
    data.setName("Test Org");
    data.setPhysicalAddress("123 Main Street");
    data.setPostalAddress("P.O. Box 1");

    Organization org = service.buildOrganization(data);

    assertEquals(2, org.getAddress().size());
    assertEquals(Address.AddressType.PHYSICAL, org.getAddress().get(0).getType());
    assertEquals(Address.AddressType.POSTAL, org.getAddress().get(1).getType());
  }

  @Test
  void buildOrganization_withParentFhirId_setsPartOf() {
    OrgData data = new OrgData();
    data.setName("Child Org");
    data.setParentFhirId("parent-123");

    Organization org = service.buildOrganization(data);

    assertEquals("Organization/parent-123", org.getPartOf().getReference());
  }

  @Test
  void buildOrganization_withFullOrgReference_usesAsIs() {
    OrgData data = new OrgData();
    data.setName("Child Org");
    data.setParentFhirId("Organization/parent-456");

    Organization org = service.buildOrganization(data);

    assertEquals("Organization/parent-456", org.getPartOf().getReference());
  }

  @Test
  void buildOrganization_withUrnUuidReference_usesAsIs() {
    OrgData data = new OrgData();
    data.setName("Child Org");
    data.setParentFhirId("urn:uuid:some-bundle-uuid");

    Organization org = service.buildOrganization(data);

    assertEquals("urn:uuid:some-bundle-uuid", org.getPartOf().getReference());
  }

  @Test
  void buildOrganization_withoutParentFhirId_noPartOf() {
    OrgData data = new OrgData();
    data.setName("Root Org");

    Organization org = service.buildOrganization(data);

    assertTrue(org.getPartOf().isEmpty());
  }

  @Test
  void extractSourceId_returnsSourceIdValue() {
    Organization org = new Organization();
    org.addIdentifier()
        .setSystem(OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM)
        .setValue("SRC-999");

    assertEquals("SRC-999", service.extractSourceId(org));
  }

  @Test
  void extractSourceId_noIdentifier_returnsNull() {
    Organization org = new Organization();

    assertNull(service.extractSourceId(org));
  }

  // -------------------------------------------------------------------------
  // id lookups — must bypass the server's search-result cache
  // -------------------------------------------------------------------------

  @Test
  void findOrganizationIdByIdentifier_searchesWithNoCacheAndReturnsId() {
    SearchClientMocks mocks = new SearchClientMocks(bundleWith(organization("42")));
    OrganizationService lookupService = new OrganizationService(mocks.fhirContext, FHIR_SERVER_URL);

    String id =
        lookupService.findOrganizationIdByIdentifier(
            OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-1");

    assertEquals("42", id);
    assertTrue(mocks.capturedCacheControl().isNoCache());
    assertEquals(
        FHIR_SERVER_URL
            + "/Organization?identifier=http%3A%2F%2Fohs.dev%2Fidentifiers%2Fsource-id%7CSRC-1"
            + "&_elements=id",
        mocks.capturedUrl());
  }

  @Test
  void findOrganizationIdByIdentifier_noMatch_returnsNull() {
    SearchClientMocks mocks = new SearchClientMocks(emptyBundle());
    OrganizationService lookupService = new OrganizationService(mocks.fhirContext, FHIR_SERVER_URL);

    assertNull(
        lookupService.findOrganizationIdByIdentifier(
            OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-GONE"));
  }

  @Test
  void findOrganizationIdByName_searchesWithNoCacheAndReturnsId() {
    SearchClientMocks mocks = new SearchClientMocks(bundleWith(organization("7")));
    OrganizationService lookupService = new OrganizationService(mocks.fhirContext, FHIR_SERVER_URL);

    String id = lookupService.findOrganizationIdByName("Parent Org");

    assertEquals("7", id);
    assertTrue(mocks.capturedCacheControl().isNoCache());
    assertEquals(
        FHIR_SERVER_URL + "/Organization?name=Parent+Org&_elements=id", mocks.capturedUrl());
  }

  @Test
  void findOrganizationIdByName_noMatch_returnsNull() {
    SearchClientMocks mocks = new SearchClientMocks(emptyBundle());
    OrganizationService lookupService = new OrganizationService(mocks.fhirContext, FHIR_SERVER_URL);

    assertNull(lookupService.findOrganizationIdByName("Unknown Parent"));
  }
}
