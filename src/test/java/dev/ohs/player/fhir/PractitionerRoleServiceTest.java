package dev.ohs.player.fhir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ITransaction;
import ca.uhn.fhir.rest.gclient.ITransactionTyped;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PractitionerRoleServiceTest {

  @Mock private FhirContext fhirContext;
  @Mock private IGenericClient client;
  @Mock private ITransaction transaction;
  @Mock private ITransactionTyped<Bundle> transactionTyped;

  private PractitionerRoleService service;

  @BeforeEach
  void setUp() {
    service = new PractitionerRoleService(fhirContext, "http://fhir.test/fhir");
  }

  // -------------------------------------------------------------------------
  // Constructor validation
  // -------------------------------------------------------------------------

  @Test
  void constructor_nullFhirContext_throwsIllegalArgument() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PractitionerRoleService(null, "http://fhir.test/fhir"));
  }

  @Test
  void constructor_blankFhirServerUrl_throwsIllegalArgument() {
    assertThrows(
        IllegalArgumentException.class, () -> new PractitionerRoleService(fhirContext, " "));
  }

  // -------------------------------------------------------------------------
  // buildPractitionerRole
  // -------------------------------------------------------------------------

  @Test
  void buildPractitionerRole_allFieldsSet_populatesAllReferences() {
    PractitionerRoleData data = new PractitionerRoleData();
    data.setPractitionerFhirId("Practitioner/p-1");
    data.setOrganizationFhirId("Organization/org-1");
    data.setLocationFhirIds(List.of("Location/loc-1"));

    PractitionerRole role = service.buildPractitionerRole(data);

    assertTrue(role.getActive());
    assertEquals("Practitioner/p-1", role.getPractitioner().getReference());
    assertEquals("Organization/org-1", role.getOrganization().getReference());
    assertEquals(1, role.getLocation().size());
    assertEquals("Location/loc-1", role.getLocation().get(0).getReference());
  }

  @Test
  void buildPractitionerRole_multipleLocations_allAddedToRole() {
    PractitionerRoleData data = new PractitionerRoleData();
    data.setPractitionerFhirId("Practitioner/p-1");
    data.setLocationFhirIds(List.of("Location/loc-1", "Location/loc-2", "Location/loc-3"));

    PractitionerRole role = service.buildPractitionerRole(data);

    assertEquals(3, role.getLocation().size());
    assertEquals("Location/loc-1", role.getLocation().get(0).getReference());
    assertEquals("Location/loc-2", role.getLocation().get(1).getReference());
    assertEquals("Location/loc-3", role.getLocation().get(2).getReference());
  }

  @Test
  void buildPractitionerRole_onlyPractitionerSet_noOrgOrLocation() {
    PractitionerRoleData data = new PractitionerRoleData();
    data.setPractitionerFhirId("Practitioner/p-1");

    PractitionerRole role = service.buildPractitionerRole(data);

    assertEquals("Practitioner/p-1", role.getPractitioner().getReference());
    assertFalse(role.hasOrganization());
    assertTrue(role.getLocation().isEmpty());
  }

  // -------------------------------------------------------------------------
  // executeBundle
  // -------------------------------------------------------------------------

  @Test
  void executeBundle_delegatesToFhirClient() {
    Bundle inputBundle = new Bundle();
    Bundle expectedResponse = new Bundle();
    when(fhirContext.newRestfulGenericClient("http://fhir.test/fhir")).thenReturn(client);
    when(client.transaction()).thenReturn(transaction);
    when(transaction.withBundle(inputBundle)).thenReturn(transactionTyped);
    when(transactionTyped.execute()).thenReturn(expectedResponse);

    Bundle result = service.executeBundle(inputBundle);

    assertSame(expectedResponse, result);
  }
}
