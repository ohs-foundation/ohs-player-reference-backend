package dev.ohs.player.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;

public class PractitionerRoleService {

  private final FhirContext fhirContext;
  private final String fhirServerUrl;

  public PractitionerRoleService(FhirContext fhirContext, String fhirServerUrl) {
    if (fhirContext == null) throw new IllegalArgumentException("FhirContext cannot be null");
    if (fhirServerUrl == null || fhirServerUrl.isBlank())
      throw new IllegalArgumentException("PROXY_TO is required");
    this.fhirContext = fhirContext;
    this.fhirServerUrl = fhirServerUrl;
  }

  public Bundle executeBundle(Bundle bundle) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    return client.transaction().withBundle(bundle).execute();
  }

  public PractitionerRole buildPractitionerRole(PractitionerRoleData data) {
    PractitionerRole role = new PractitionerRole();
    role.setActive(true);
    role.setPractitioner(new Reference(data.getPractitionerFhirId()));
    if (data.getOrganizationFhirId() != null) {
      role.setOrganization(new Reference(data.getOrganizationFhirId()));
    }
    List<String> locationFhirIds = data.getLocationFhirIds();
    if (locationFhirIds != null) {
      for (String locationRef : locationFhirIds) {
        role.addLocation(new Reference(locationRef));
      }
    }
    return role;
  }
}
