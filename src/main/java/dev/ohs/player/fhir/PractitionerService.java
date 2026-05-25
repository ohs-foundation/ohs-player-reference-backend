package dev.ohs.player.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import dev.ohs.player.iam.IamUser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Practitioner;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PractitionerService {

  private static final Logger logger = LoggerFactory.getLogger(PractitionerService.class);
  static final String KEYCLOAK_IDENTIFIER_SYSTEM = "http://ohs.dev/identifiers/keycloak-user-id";

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

  private Practitioner buildPractitioner(String iamUserId, IamUser user) {
    Practitioner practitioner = new Practitioner();
    practitioner.setActive(user.isEnabled());

    Identifier identifier = new Identifier();
    identifier.setSystem(KEYCLOAK_IDENTIFIER_SYSTEM);
    identifier.setValue(iamUserId);
    practitioner.addIdentifier(identifier);

    HumanName name = new HumanName();
    name.setFamily(user.getLastName());
    name.addGiven(user.getFirstName());
    practitioner.addName(name);

    return practitioner;
  }
}
