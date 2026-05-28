package dev.ohs.player.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PractitionerDetailService {

  private static final Logger logger = LoggerFactory.getLogger(PractitionerDetailService.class);

  private final FhirContext fhirContext;
  private final String fhirServerUrl;

  public PractitionerDetailService(FhirContext fhirContext, String fhirServerUrl) {
    if (fhirContext == null) throw new IllegalArgumentException("FhirContext cannot be null");
    if (fhirServerUrl == null || fhirServerUrl.isBlank())
      throw new IllegalArgumentException("PROXY_TO is required");
    this.fhirContext = fhirContext;
    this.fhirServerUrl = fhirServerUrl;
  }

  public @Nullable String resolvePractitionerIdFromIamId(String iamId) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    String identifier = PractitionerService.KEYCLOAK_IDENTIFIER_SYSTEM + "|" + iamId;
    String url = base() + "Practitioner?identifier=" + encode(identifier) + "&_elements=id";
    Bundle bundle = client.fetchResourceFromUrl(Bundle.class, url);
    return bundle.getEntry().stream()
        .map(Bundle.BundleEntryComponent::getResource)
        .filter(r -> r instanceof Practitioner)
        .map(r -> r.getIdElement().getIdPart())
        .findFirst()
        .orElse(null);
  }

  public @Nullable PractitionerDetail fetchPractitionerDetail(
      String practitionerId, @Nullable String organisationId, @Nullable String locationId) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    String url = buildDetailUrl(practitionerId, organisationId, locationId);
    Bundle bundle = client.fetchResourceFromUrl(Bundle.class, url);
    List<Bundle.BundleEntryComponent> allEntries = new ArrayList<>(bundle.getEntry());
    while (bundle.getLink("next") != null) {
      bundle = client.loadPage().next(bundle).execute();
      allEntries.addAll(bundle.getEntry());
    }
    logger.debug(
        "Fetched {} bundle entries for practitioner: {}", allEntries.size(), practitionerId);
    return mapEntries(allEntries);
  }

  @Nullable PractitionerDetail mapEntries(List<Bundle.BundleEntryComponent> entries) {
    Practitioner practitioner = null;
    Map<String, PractitionerRole> rolesById = new LinkedHashMap<>();
    Map<String, Organization> orgsById = new LinkedHashMap<>();
    Map<String, Location> locationsById = new LinkedHashMap<>();
    List<CareTeam> careTeams = new ArrayList<>();

    for (Bundle.BundleEntryComponent entry : entries) {
      IBaseResource resource = entry.getResource();
      if (resource instanceof Practitioner) {
        practitioner = (Practitioner) resource;
      } else if (resource instanceof PractitionerRole) {
        PractitionerRole role = (PractitionerRole) resource;
        rolesById.put(role.getIdElement().getIdPart(), role);
      } else if (resource instanceof Organization) {
        Organization org = (Organization) resource;
        orgsById.put(org.getIdElement().getIdPart(), org);
      } else if (resource instanceof Location) {
        Location loc = (Location) resource;
        locationsById.put(loc.getIdElement().getIdPart(), loc);
      } else if (resource instanceof CareTeam) {
        careTeams.add((CareTeam) resource);
      }
    }

    if (practitioner == null) {
      return null;
    }

    List<PractitionerRoleDetail> roleDetails = new ArrayList<>();
    for (Map.Entry<String, PractitionerRole> roleEntry : rolesById.entrySet()) {
      String roleId = roleEntry.getKey();
      PractitionerRole role = roleEntry.getValue();

      Organization org = null;
      if (role.hasOrganization()) {
        String orgId = role.getOrganization().getReferenceElement().getIdPart();
        org = orgsById.get(orgId);
      }

      List<Location> roleLocations = new ArrayList<>();
      for (Reference locRef : role.getLocation()) {
        String locId = locRef.getReferenceElement().getIdPart();
        Location loc = locationsById.get(locId);
        if (loc != null) roleLocations.add(loc);
      }

      List<CareTeam> roleCareTeams = new ArrayList<>();
      for (CareTeam ct : careTeams) {
        for (CareTeam.CareTeamParticipantComponent participant : ct.getParticipant()) {
          if (memberReferencesPractitionerRole(participant.getMember(), roleId)) {
            roleCareTeams.add(ct);
            break;
          }
        }
      }

      PractitionerRoleDetail detail = new PractitionerRoleDetail();
      detail.setPractitionerRole(role);
      detail.setOrganization(org);
      detail.setLocations(roleLocations);
      detail.setCareTeams(roleCareTeams);
      roleDetails.add(detail);
    }

    PractitionerDetail result = new PractitionerDetail();
    result.setPractitioner(practitioner);
    result.setPractitionerRoles(roleDetails);
    return result;
  }

  private boolean memberReferencesPractitionerRole(
      @Nullable Reference member, String practitionerRoleId) {
    if (member == null || member.getReference() == null) return false;
    return member.getReference().endsWith("PractitionerRole/" + practitionerRoleId);
  }

  private String buildDetailUrl(
      String practitionerId, @Nullable String organisationId, @Nullable String locationId) {
    StringBuilder url =
        new StringBuilder(base())
            .append("PractitionerRole?practitioner=")
            .append(encode(practitionerId))
            .append("&_include=")
            .append(encode("PractitionerRole:practitioner"))
            .append("&_include=")
            .append(encode("PractitionerRole:organization"))
            .append("&_include=")
            .append(encode("PractitionerRole:location"))
            .append("&_revinclude=")
            .append(encode("CareTeam:participant"));
    if (organisationId != null && !organisationId.isBlank()) {
      url.append("&organization=").append(encode(organisationId));
    }
    if (locationId != null && !locationId.isBlank()) {
      url.append("&location=").append(encode(locationId));
    }
    return url.toString();
  }

  private String base() {
    return fhirServerUrl.endsWith("/") ? fhirServerUrl : fhirServerUrl + "/";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
