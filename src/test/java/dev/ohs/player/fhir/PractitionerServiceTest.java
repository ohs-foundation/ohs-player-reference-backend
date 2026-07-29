package dev.ohs.player.fhir;

import static dev.ohs.player.fhir.SearchClientMocks.FHIR_SERVER_URL;
import static dev.ohs.player.fhir.SearchClientMocks.bundleWith;
import static dev.ohs.player.fhir.SearchClientMocks.emptyBundle;
import static dev.ohs.player.fhir.SearchClientMocks.practitioner;
import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import dev.ohs.player.iam.IamUser;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PractitionerServiceTest {

  private static PractitionerService service;

  private static final String IAM_USER_ID = "keycloak-uuid-123";

  @BeforeAll
  static void setUp() {
    service = new PractitionerService(FhirContext.forR4Cached(), "http://localhost:8080/fhir");
  }

  @Test
  void buildPractitioner_emailAlwaysAddedAsTelecom() {
    IamUser user = minimalUser();

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    ContactPoint email = findTelecom(p, ContactPoint.ContactPointSystem.EMAIL);
    assertNotNull(email);
    assertEquals("jdoe@example.com", email.getValue());
    assertEquals(ContactPoint.ContactPointUse.WORK, email.getUse());
  }

  @Test
  void buildPractitioner_withPhone_addsMobileTelecom() {
    IamUser user = minimalUser();
    user.setPhone("+254700000000");

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    ContactPoint phone = findTelecom(p, ContactPoint.ContactPointSystem.PHONE);
    assertNotNull(phone);
    assertEquals("+254700000000", phone.getValue());
    assertEquals(ContactPoint.ContactPointUse.MOBILE, phone.getUse());
  }

  @Test
  void buildPractitioner_withNullPhone_noPhoneTelecom() {
    IamUser user = minimalUser();
    user.setPhone(null);

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    assertNull(findTelecom(p, ContactPoint.ContactPointSystem.PHONE));
  }

  @Test
  void buildPractitioner_withBlankPhone_noPhoneTelecom() {
    IamUser user = minimalUser();
    user.setPhone("   ");

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    assertNull(findTelecom(p, ContactPoint.ContactPointSystem.PHONE));
  }

  @Test
  void buildPractitioner_withNationalId_addsNationalIdIdentifier() {
    IamUser user = minimalUser();
    user.setNationalId("NID-12345678");

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    Identifier nationalId = findIdentifier(p, PractitionerService.NATIONAL_ID_IDENTIFIER_SYSTEM);
    assertNotNull(nationalId);
    assertEquals("NID-12345678", nationalId.getValue());
  }

  @Test
  void buildPractitioner_withNullNationalId_noNationalIdIdentifier() {
    IamUser user = minimalUser();
    user.setNationalId(null);

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    assertNull(findIdentifier(p, PractitionerService.NATIONAL_ID_IDENTIFIER_SYSTEM));
  }

  @Test
  void buildPractitioner_withDob_setsBirthDate() {
    IamUser user = minimalUser();
    user.setDob("1990-05-15");

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    assertNotNull(p.getBirthDateElement());
    assertEquals("1990-05-15", p.getBirthDateElement().getValueAsString());
  }

  @Test
  void buildPractitioner_withNullDob_noBirthDate() {
    IamUser user = minimalUser();
    user.setDob(null);

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    assertTrue(p.getBirthDateElement().isEmpty());
  }

  @Test
  void buildPractitioner_withGender_setsGender() {
    IamUser user = minimalUser();
    user.setGender("female");

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    assertEquals(Enumerations.AdministrativeGender.FEMALE, p.getGender());
  }

  @Test
  void buildPractitioner_withNullGender_noGender() {
    IamUser user = minimalUser();
    user.setGender(null);

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    assertNull(p.getGender());
  }

  @Test
  void buildPractitioner_withSourceId_addsSourceIdIdentifier() {
    IamUser user = minimalUser();
    user.setSourceId("SRC-99");

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    Identifier sourceId = findIdentifier(p, PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM);
    assertNotNull(sourceId);
    assertEquals("SRC-99", sourceId.getValue());
  }

  @Test
  void buildPractitioner_withNullSourceId_noSourceIdIdentifier() {
    IamUser user = minimalUser();
    user.setSourceId(null);

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    assertNull(findIdentifier(p, PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM));
  }

  @Test
  void buildPractitioner_withAllFields_setsAllProperties() {
    IamUser user = minimalUser();
    user.setPhone("+254700000000");
    user.setNationalId("NID-12345678");
    user.setSourceId("SRC-99");
    user.setDob("1985-03-20");
    user.setGender("male");

    Practitioner p = service.buildPractitioner(IAM_USER_ID, user);

    assertNotNull(findTelecom(p, ContactPoint.ContactPointSystem.EMAIL));
    assertNotNull(findTelecom(p, ContactPoint.ContactPointSystem.PHONE));
    assertNotNull(findIdentifier(p, PractitionerService.KEYCLOAK_IDENTIFIER_SYSTEM));
    assertNotNull(findIdentifier(p, PractitionerService.NATIONAL_ID_IDENTIFIER_SYSTEM));
    assertNotNull(findIdentifier(p, PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM));
    assertEquals("1985-03-20", p.getBirthDateElement().getValueAsString());
    assertEquals(Enumerations.AdministrativeGender.MALE, p.getGender());
  }

  private IamUser minimalUser() {
    IamUser user = new IamUser();
    user.setUsername("jdoe");
    user.setEmail("jdoe@example.com");
    user.setFirstName("John");
    user.setLastName("Doe");
    user.setEnabled(true);
    return user;
  }

  private ContactPoint findTelecom(Practitioner p, ContactPoint.ContactPointSystem system) {
    return p.getTelecom().stream()
        .filter(cp -> system.equals(cp.getSystem()))
        .findFirst()
        .orElse(null);
  }

  private Identifier findIdentifier(Practitioner p, String system) {
    return p.getIdentifier().stream()
        .filter(id -> system.equals(id.getSystem()))
        .findFirst()
        .orElse(null);
  }

  // -------------------------------------------------------------------------
  // findPractitionerIdByIdentifier — must bypass the server's search-result cache
  // -------------------------------------------------------------------------

  @Test
  void findPractitionerIdByIdentifier_searchesWithNoCacheAndReturnsId() {
    SearchClientMocks mocks = new SearchClientMocks(bundleWith(practitioner("1097")));
    PractitionerService lookupService = new PractitionerService(mocks.fhirContext, FHIR_SERVER_URL);

    String id =
        lookupService.findPractitionerIdByIdentifier(
            PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM, "1");

    assertEquals("1097", id);
    assertTrue(mocks.capturedCacheControl().isNoCache());
    assertEquals(
        FHIR_SERVER_URL
            + "/Practitioner?identifier=http%3A%2F%2Fohs.dev%2Fidentifiers%2Fsource-id%7C1"
            + "&_elements=id",
        mocks.capturedUrl());
  }

  @Test
  void findPractitionerIdByIdentifier_noMatch_returnsNull() {
    SearchClientMocks mocks = new SearchClientMocks(emptyBundle());
    PractitionerService lookupService = new PractitionerService(mocks.fhirContext, FHIR_SERVER_URL);

    assertNull(
        lookupService.findPractitionerIdByIdentifier(
            PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM, "does-not-exist"));
  }
}
