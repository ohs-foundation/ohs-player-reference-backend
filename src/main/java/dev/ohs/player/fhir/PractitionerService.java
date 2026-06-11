package dev.ohs.player.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import dev.ohs.player.iam.IamUser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Practitioner;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PractitionerService {

  private static final Logger logger = LoggerFactory.getLogger(PractitionerService.class);
  public static final String KEYCLOAK_IDENTIFIER_SYSTEM =
      "http://ohs.dev/identifiers/keycloak-user-id";
  public static final String NATIONAL_ID_IDENTIFIER_SYSTEM =
      "http://ohs.dev/identifiers/national-id";
  public static final String SOURCE_ID_IDENTIFIER_SYSTEM = "http://ohs.dev/identifiers/source-id";

  private final FhirContext fhirContext;
  private final String fhirServerUrl;

  public PractitionerService(FhirContext fhirContext, String fhirServerUrl) {
    if (fhirContext == null) throw new IllegalArgumentException("FhirContext cannot be null");
    if (fhirServerUrl == null || fhirServerUrl.isBlank())
      throw new IllegalArgumentException("PROXY_TO is required");
    this.fhirContext = fhirContext;
    this.fhirServerUrl = fhirServerUrl;
  }

  public Practitioner createPractitioner(String iamUserId, IamUser user) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    Practitioner practitioner = buildPractitioner(iamUserId, user);
    MethodOutcome outcome = client.create().resource(practitioner).execute();
    if (outcome.getResource() != null) {
      return (Practitioner) outcome.getResource();
    }
    practitioner.setId(outcome.getId());
    logger.info(
        "Created Practitioner: id={}, iamUserId={}", outcome.getId().getIdPart(), iamUserId);
    return practitioner;
  }

  public Practitioner updatePractitioner(String practitionerId, String iamUserId, IamUser user) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    Practitioner practitioner = buildPractitioner(iamUserId, user);
    practitioner.setId(practitionerId);
    MethodOutcome outcome = client.update().resource(practitioner).execute();
    logger.info("Updated Practitioner: id={}", practitionerId);
    if (outcome.getResource() != null) {
      return (Practitioner) outcome.getResource();
    }
    return practitioner;
  }

  public Practitioner getPractitioner(String practitionerId) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    return client.read().resource(Practitioner.class).withId(practitionerId).execute();
  }

  /**
   * Searches Practitioners on the FHIR server, forwarding all provided query parameters verbatim.
   * Supports any FHIR search parameter including name, _sort, _count, _offset, _include, etc.
   */
  public Bundle searchPractitioners(Map<String, String[]> params) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    String url = buildSearchUrl(params);
    return client.fetchResourceFromUrl(Bundle.class, url);
  }

  public void deletePractitioner(String practitionerId) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    client.delete().resourceById(new IdType("Practitioner", practitionerId)).execute();
    logger.info("Deleted Practitioner: id={}", practitionerId);
  }

  public @Nullable String extractIamUserId(Practitioner practitioner) {
    return practitioner.getIdentifier().stream()
        .filter(id -> KEYCLOAK_IDENTIFIER_SYSTEM.equals(id.getSystem()))
        .map(Identifier::getValue)
        .findFirst()
        .orElse(null);
  }

  public @Nullable String extractSourceId(Practitioner practitioner) {
    return practitioner.getIdentifier().stream()
        .filter(id -> SOURCE_ID_IDENTIFIER_SYSTEM.equals(id.getSystem()))
        .map(Identifier::getValue)
        .findFirst()
        .orElse(null);
  }

  public @Nullable String findPractitionerIdByIdentifier(String system, String value) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    String encoded = URLEncoder.encode(system + "|" + value, StandardCharsets.UTF_8);
    String base = fhirServerUrl.endsWith("/") ? fhirServerUrl : fhirServerUrl + "/";
    Bundle result =
        client.fetchResourceFromUrl(
            Bundle.class, base + "Practitioner?identifier=" + encoded + "&_elements=id");
    if (result.getEntry().isEmpty()) return null;
    return result.getEntry().get(0).getResource().getIdElement().getIdPart();
  }

  private String buildSearchUrl(Map<String, String[]> params) {
    String base = fhirServerUrl.endsWith("/") ? fhirServerUrl : fhirServerUrl + "/";
    StringBuilder url = new StringBuilder(base).append("Practitioner");
    if (!params.isEmpty()) {
      url.append("?");
      boolean first = true;
      for (Map.Entry<String, String[]> entry : params.entrySet()) {
        for (String value : entry.getValue()) {
          if (!first) url.append("&");
          url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
          url.append("=");
          url.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
          first = false;
        }
      }
    }
    return url.toString();
  }

  Practitioner buildPractitioner(String iamUserId, IamUser user) {
    Practitioner practitioner = new Practitioner();
    practitioner.setActive(user.isEnabled());

    Identifier keycloakIdentifier = new Identifier();
    keycloakIdentifier.setSystem(KEYCLOAK_IDENTIFIER_SYSTEM);
    keycloakIdentifier.setValue(iamUserId);
    practitioner.addIdentifier(keycloakIdentifier);

    if (user.getNationalId() != null && !user.getNationalId().isBlank()) {
      Identifier nationalIdIdentifier = new Identifier();
      nationalIdIdentifier.setSystem(NATIONAL_ID_IDENTIFIER_SYSTEM);
      nationalIdIdentifier.setValue(user.getNationalId());
      practitioner.addIdentifier(nationalIdIdentifier);
    }

    if (user.getSourceId() != null && !user.getSourceId().isBlank()) {
      Identifier sourceIdIdentifier = new Identifier();
      sourceIdIdentifier.setSystem(SOURCE_ID_IDENTIFIER_SYSTEM);
      sourceIdIdentifier.setValue(user.getSourceId());
      practitioner.addIdentifier(sourceIdIdentifier);
    }

    HumanName name = new HumanName();
    name.setFamily(user.getLastName());
    name.addGiven(user.getFirstName());
    practitioner.addName(name);

    ContactPoint emailContact = new ContactPoint();
    emailContact.setSystem(ContactPoint.ContactPointSystem.EMAIL);
    emailContact.setUse(ContactPoint.ContactPointUse.WORK);
    emailContact.setValue(user.getEmail());
    practitioner.addTelecom(emailContact);

    if (user.getPhone() != null && !user.getPhone().isBlank()) {
      ContactPoint phoneContact = new ContactPoint();
      phoneContact.setSystem(ContactPoint.ContactPointSystem.PHONE);
      phoneContact.setUse(ContactPoint.ContactPointUse.MOBILE);
      phoneContact.setValue(user.getPhone());
      practitioner.addTelecom(phoneContact);
    }

    if (user.getDob() != null && !user.getDob().isBlank()) {
      practitioner.setBirthDateElement(new DateType(user.getDob()));
    }

    if (user.getGender() != null && !user.getGender().isBlank()) {
      practitioner.setGender(Enumerations.AdministrativeGender.fromCode(user.getGender()));
    }

    return practitioner;
  }
}
