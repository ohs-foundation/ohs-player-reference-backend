package dev.ohs.player.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrganizationService {

  private static final Logger logger = LoggerFactory.getLogger(OrganizationService.class);

  public static final String SOURCE_ID_IDENTIFIER_SYSTEM = "http://ohs.dev/identifiers/source-id";
  private static final String ORG_TYPE_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/organization-type";

  private final FhirContext fhirContext;
  private final String fhirServerUrl;

  public OrganizationService(FhirContext fhirContext, String fhirServerUrl) {
    if (fhirContext == null) throw new IllegalArgumentException("FhirContext cannot be null");
    if (fhirServerUrl == null || fhirServerUrl.isBlank())
      throw new IllegalArgumentException("PROXY_TO is required");
    this.fhirContext = fhirContext;
    this.fhirServerUrl = fhirServerUrl;
  }

  public Organization createOrganization(OrgData data) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    Organization org = buildOrganization(data);
    MethodOutcome outcome = client.create().resource(org).execute();
    if (outcome.getResource() != null) {
      return (Organization) outcome.getResource();
    }
    org.setId(outcome.getId());
    logger.info(
        "Created Organization: id={}, name={}", outcome.getId().getIdPart(), data.getName());
    return org;
  }

  public Organization updateOrganization(String orgId, OrgData data) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    Organization org = buildOrganization(data);
    org.setId(orgId);
    MethodOutcome outcome = client.update().resource(org).execute();
    logger.info("Updated Organization: id={}", orgId);
    if (outcome.getResource() != null) {
      return (Organization) outcome.getResource();
    }
    return org;
  }

  public Bundle executeBundle(Bundle bundle) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    return client.transaction().withBundle(bundle).execute();
  }

  public Organization getOrganization(String orgId) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    return client.read().resource(Organization.class).withId(orgId).execute();
  }

  /**
   * Finds an Organization by identifier, bypassing the FHIR server's search-result cache. See
   * {@link FhirIdLookup}.
   */
  public @Nullable String findOrganizationIdByIdentifier(String system, String value) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    return FhirIdLookup.findFirstId(
        client, fhirServerUrl, "Organization", "identifier", system + "|" + value);
  }

  /**
   * Finds an Organization by name, bypassing the FHIR server's search-result cache. See {@link
   * FhirIdLookup}.
   */
  public @Nullable String findOrganizationIdByName(String name) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    return FhirIdLookup.findFirstId(client, fhirServerUrl, "Organization", "name", name);
  }

  public @Nullable String extractSourceId(Organization org) {
    return org.getIdentifier().stream()
        .filter(id -> SOURCE_ID_IDENTIFIER_SYSTEM.equals(id.getSystem()))
        .map(Identifier::getValue)
        .findFirst()
        .orElse(null);
  }

  public Organization buildOrganization(OrgData data) {
    Organization org = new Organization();
    org.setActive(true);
    org.setName(data.getName());

    if (data.getSourceId() != null) {
      Identifier sourceIdIdentifier = new Identifier();
      sourceIdIdentifier.setSystem(SOURCE_ID_IDENTIFIER_SYSTEM);
      sourceIdIdentifier.setValue(data.getSourceId());
      org.addIdentifier(sourceIdIdentifier);
    }

    if (data.isTeam()) {
      CodeableConcept type = new CodeableConcept();
      Coding coding = new Coding();
      coding.setSystem(ORG_TYPE_SYSTEM);
      coding.setCode("team");
      type.addCoding(coding);
      org.addType(type);
    }

    if (data.getPhone() != null) {
      ContactPoint phone = new ContactPoint();
      phone.setSystem(ContactPoint.ContactPointSystem.PHONE);
      phone.setUse(ContactPoint.ContactPointUse.WORK);
      phone.setValue(data.getPhone());
      org.addTelecom(phone);
    }

    if (data.getEmail() != null) {
      ContactPoint email = new ContactPoint();
      email.setSystem(ContactPoint.ContactPointSystem.EMAIL);
      email.setUse(ContactPoint.ContactPointUse.WORK);
      email.setValue(data.getEmail());
      org.addTelecom(email);
    }

    if (data.getPhysicalAddress() != null) {
      Address physical = new Address();
      physical.setUse(Address.AddressUse.WORK);
      physical.setType(Address.AddressType.PHYSICAL);
      physical.setText(data.getPhysicalAddress());
      org.addAddress(physical);
    }

    if (data.getPostalAddress() != null) {
      Address postal = new Address();
      postal.setUse(Address.AddressUse.WORK);
      postal.setType(Address.AddressType.POSTAL);
      postal.setText(data.getPostalAddress());
      org.addAddress(postal);
    }

    if (data.getParentFhirId() != null) {
      String parentRef = data.getParentFhirId();
      if (!parentRef.startsWith("urn:") && !parentRef.startsWith("Organization/")) {
        parentRef = "Organization/" + parentRef;
      }
      org.setPartOf(new Reference(parentRef));
    }

    return org;
  }
}
