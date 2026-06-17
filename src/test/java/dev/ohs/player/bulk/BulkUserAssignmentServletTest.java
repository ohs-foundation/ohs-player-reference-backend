package dev.ohs.player.bulk;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.ohs.player.fhir.LocationService;
import dev.ohs.player.fhir.OrganizationService;
import dev.ohs.player.fhir.PractitionerRoleService;
import dev.ohs.player.fhir.PractitionerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BulkUserAssignmentServletTest {

  @Mock private PractitionerRoleService practitionerRoleService;
  @Mock private PractitionerService practitionerService;
  @Mock private OrganizationService organizationService;
  @Mock private LocationService locationService;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private Part filePart;

  private CsvProcessor csvProcessor;
  private SseResponseHelper sseHelper;
  // batchSize=1: each row is its own bundle — keeps single-row tests simple.
  private BulkUserAssignmentServlet servlet;
  private StringWriter responseBuffer;

  private static final String HEADER =
      "practitioner_id,practitioner_source_id,org_id,org_source_id,location_id,location_source_id";

  @BeforeEach
  void setUp() throws Exception {
    csvProcessor = new CsvProcessor();
    sseHelper = new SseResponseHelper();
    servlet =
        new BulkUserAssignmentServlet(
            practitionerRoleService,
            practitionerService,
            organizationService,
            locationService,
            csvProcessor,
            sseHelper,
            1);
    responseBuffer = new StringWriter();
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseBuffer));
    lenient().when(practitionerRoleService.buildPractitionerRole(any())).thenCallRealMethod();
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  @Test
  void doPost_missingFilePart_returns400() throws Exception {
    when(request.getPart("file")).thenReturn(null);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerRoleService);
  }

  @Test
  void doPost_missingBothPractitionerColumns_emitsErrorAndContinues() throws Exception {
    givenCsv(HEADER + "\n,,,,,\np-id,,,,,");
    when(practitionerService.getPractitioner("p-id")).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":1");
    assertSseContains("\"done\":true,\"processed\":1,\"failed\":1,\"total\":2");
  }

  // -------------------------------------------------------------------------
  // Practitioner reference resolution
  // -------------------------------------------------------------------------

  @Test
  void doPost_practitionerById_createsRoleWithCorrectReference() throws Exception {
    givenCsv(HEADER + "\np-123,,,,,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null
                      && "Practitioner/p-123".equals(role.getPractitioner().getReference());
                }));
    assertSseContains("\"done\":true,\"processed\":1,\"failed\":0,\"total\":1");
  }

  @Test
  void doPost_practitionerBySourceId_resolvesViaIdentifierLookup() throws Exception {
    givenCsv(HEADER + "\n,SRC-P1,,,,");
    when(practitionerService.findPractitionerIdByIdentifier(
            PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-P1"))
        .thenReturn("p-found");
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null
                      && "Practitioner/p-found".equals(role.getPractitioner().getReference());
                }));
  }

  @Test
  void doPost_practitionerSourceIdCached_noRepeatedFhirLookup() throws Exception {
    BulkUserAssignmentServlet batchServlet =
        new BulkUserAssignmentServlet(
            practitionerRoleService,
            practitionerService,
            organizationService,
            locationService,
            csvProcessor,
            sseHelper,
            2);
    givenCsv(HEADER + "\n,SRC-P1,,,,\n,SRC-P1,,,,");
    when(practitionerService.findPractitionerIdByIdentifier(
            PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-P1"))
        .thenReturn("p-found");
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle("id-1", "id-2"));

    batchServlet.doPost(request, response);

    verify(practitionerService, times(1)).findPractitionerIdByIdentifier(any(), eq("SRC-P1"));
    assertSseContains("\"processed\":2");
  }

  @Test
  void doPost_practitionerNotFound_emitsRowErrorAndContinues() throws Exception {
    givenCsv(HEADER + "\np-missing,,,,,\np-123,,,,,");
    when(practitionerService.getPractitioner("p-missing"))
        .thenThrow(new RuntimeException("Not found"));
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":1");
    assertSseContains("\"done\":true,\"processed\":1,\"failed\":1,\"total\":2");
  }

  // -------------------------------------------------------------------------
  // Organization reference resolution
  // -------------------------------------------------------------------------

  @Test
  void doPost_orgById_setsOrganizationReference() throws Exception {
    givenCsv(HEADER + "\np-123,,org-99,,,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(organizationService.getOrganization("org-99")).thenReturn(new Organization());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null
                      && "Organization/org-99".equals(role.getOrganization().getReference());
                }));
  }

  @Test
  void doPost_orgBySourceId_resolvesViaIdentifierLookup() throws Exception {
    givenCsv(HEADER + "\np-123,,,SRC-ORG,,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(organizationService.findOrganizationIdByIdentifier(
            OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-ORG"))
        .thenReturn("org-found");
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null
                      && "Organization/org-found".equals(role.getOrganization().getReference());
                }));
  }

  @Test
  void doPost_orgColumnsOmitted_noOrganizationReference() throws Exception {
    givenCsv(HEADER + "\np-123,,,,,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null && !role.hasOrganization();
                }));
  }

  // -------------------------------------------------------------------------
  // Location reference resolution
  // -------------------------------------------------------------------------

  @Test
  void doPost_locationById_setsLocationReference() throws Exception {
    givenCsv(HEADER + "\np-123,,,,loc-77,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(locationService.getLocation("loc-77")).thenReturn(new Location());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null
                      && !role.getLocation().isEmpty()
                      && "Location/loc-77".equals(role.getLocation().get(0).getReference());
                }));
  }

  @Test
  void doPost_locationBySourceId_resolvesViaIdentifierLookup() throws Exception {
    givenCsv(HEADER + "\np-123,,,,,SRC-LOC");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(locationService.findLocationIdByIdentifier(
            LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-LOC"))
        .thenReturn("loc-found");
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null
                      && !role.getLocation().isEmpty()
                      && "Location/loc-found".equals(role.getLocation().get(0).getReference());
                }));
  }

  @Test
  void doPost_multipleLocationsBySourceId_allResolvedAndSetOnRole() throws Exception {
    givenCsv(HEADER + "\np-123,,,,,SRC-LOC1;SRC-LOC2;SRC-LOC3");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(locationService.findLocationIdByIdentifier(
            LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-LOC1"))
        .thenReturn("loc-1");
    when(locationService.findLocationIdByIdentifier(
            LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-LOC2"))
        .thenReturn("loc-2");
    when(locationService.findLocationIdByIdentifier(
            LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-LOC3"))
        .thenReturn("loc-3");
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null
                      && role.getLocation().size() == 3
                      && "Location/loc-1".equals(role.getLocation().get(0).getReference())
                      && "Location/loc-2".equals(role.getLocation().get(1).getReference())
                      && "Location/loc-3".equals(role.getLocation().get(2).getReference());
                }));
  }

  @Test
  void doPost_multipleLocationsById_allResolvedAndSetOnRole() throws Exception {
    givenCsv(HEADER + "\np-123,,,,loc-A;loc-B,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(locationService.getLocation("loc-A")).thenReturn(new Location());
    when(locationService.getLocation("loc-B")).thenReturn(new Location());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null
                      && role.getLocation().size() == 2
                      && "Location/loc-A".equals(role.getLocation().get(0).getReference())
                      && "Location/loc-B".equals(role.getLocation().get(1).getReference());
                }));
  }

  @Test
  void doPost_multipleLocationSourceIds_cachedAfterFirstRow() throws Exception {
    BulkUserAssignmentServlet batchServlet =
        new BulkUserAssignmentServlet(
            practitionerRoleService,
            practitionerService,
            organizationService,
            locationService,
            csvProcessor,
            sseHelper,
            2);
    givenCsv(HEADER + "\np-1,,,,,SRC-LOC1;SRC-LOC2\np-2,,,,,SRC-LOC1;SRC-LOC2");
    when(practitionerService.getPractitioner(any())).thenReturn(new Practitioner());
    when(locationService.findLocationIdByIdentifier(
            LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-LOC1"))
        .thenReturn("loc-1");
    when(locationService.findLocationIdByIdentifier(
            LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-LOC2"))
        .thenReturn("loc-2");
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle("id-1", "id-2"));

    batchServlet.doPost(request, response);

    // Each source_id looked up only once despite appearing in two rows.
    verify(locationService, times(1)).findLocationIdByIdentifier(any(), eq("SRC-LOC1"));
    verify(locationService, times(1)).findLocationIdByIdentifier(any(), eq("SRC-LOC2"));
  }

  @Test
  void doPost_locationColumnsOmitted_noLocationReference() throws Exception {
    givenCsv(HEADER + "\np-123,,,,,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null && role.getLocation().isEmpty();
                }));
  }

  // -------------------------------------------------------------------------
  // FHIR batch failures
  // -------------------------------------------------------------------------

  @Test
  void doPost_batchEntryFails_emitsErrorForFailedRowOnly() throws Exception {
    BulkUserAssignmentServlet batchServlet =
        new BulkUserAssignmentServlet(
            practitionerRoleService,
            practitionerService,
            organizationService,
            locationService,
            csvProcessor,
            sseHelper,
            3);
    givenCsv(HEADER + "\np-1,,,,,\np-2,,,,,\np-3,,,,,");
    when(practitionerService.getPractitioner(any())).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any())).thenReturn(mixedBundle(true, false, true));

    batchServlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":2");
    assertSseContains("\"done\":true,\"processed\":2,\"failed\":1,\"total\":3");
  }

  @Test
  void doPost_executeBundleThrows_emitsErrorForAllRowsInBatch() throws Exception {
    BulkUserAssignmentServlet batchServlet =
        new BulkUserAssignmentServlet(
            practitionerRoleService,
            practitionerService,
            organizationService,
            locationService,
            csvProcessor,
            sseHelper,
            2);
    givenCsv(HEADER + "\np-1,,,,,\np-2,,,,,");
    when(practitionerService.getPractitioner(any())).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any())).thenThrow(new RuntimeException("FHIR down"));

    batchServlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"done\":true,\"processed\":0,\"failed\":2,\"total\":2");
  }

  // -------------------------------------------------------------------------
  // Batch size boundary and progress events
  // -------------------------------------------------------------------------

  @Test
  void doPost_batchSizeBoundary_splitAcrossTwoBatches() throws Exception {
    BulkUserAssignmentServlet batchServlet =
        new BulkUserAssignmentServlet(
            practitionerRoleService,
            practitionerService,
            organizationService,
            locationService,
            csvProcessor,
            sseHelper,
            2);
    givenCsv(HEADER + "\np-1,,,,,\np-2,,,,,\np-3,,,,,");
    when(practitionerService.getPractitioner(any())).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any()))
        .thenReturn(successBundle("id-1", "id-2"))
        .thenReturn(successBundle("id-3"));

    batchServlet.doPost(request, response);

    verify(practitionerRoleService, times(2)).executeBundle(any());
    assertSseContains("\"done\":true,\"processed\":3,\"failed\":0,\"total\":3");
  }

  @Test
  void doPost_progressEmittedOncePerBatch() throws Exception {
    BulkUserAssignmentServlet batchServlet =
        new BulkUserAssignmentServlet(
            practitionerRoleService,
            practitionerService,
            organizationService,
            locationService,
            csvProcessor,
            sseHelper,
            3);
    givenCsv(HEADER + "\np-1,,,,,\np-2,,,,,\np-3,,,,,");
    when(practitionerService.getPractitioner(any())).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any()))
        .thenReturn(successBundle("id-1", "id-2", "id-3"));

    batchServlet.doPost(request, response);

    String output = responseBuffer.toString();
    long progressCount =
        output.lines().filter(l -> l.contains("\"processed\"") && !l.contains("\"done\"")).count();
    assertTrue(
        progressCount == 1,
        "Expected exactly 1 progress event for a single batch, got: " + progressCount);
    assertSseContains("\"processed\":3,\"total\":3");
  }

  // -------------------------------------------------------------------------
  // Done event
  // -------------------------------------------------------------------------

  @Test
  void doPost_doneEvent_zeroFailed() throws Exception {
    givenCsv(HEADER + "\np-123,,,,,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    assertSseContains("\"done\":true,\"processed\":1,\"failed\":0,\"total\":1");
  }

  @Test
  void doPost_doneEvent_withParseAndFhirFailures() throws Exception {
    givenCsv(HEADER + "\np-123,,,,,\n,,,,,\np-456,,,,,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(practitionerService.getPractitioner("p-456")).thenReturn(new Practitioner());
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    assertSseContains("\"done\":true,\"processed\":2,\"failed\":1,\"total\":3");
  }

  // -------------------------------------------------------------------------
  // Column order independence
  // -------------------------------------------------------------------------

  @Test
  void doPost_shuffledColumns_parsedCorrectly() throws Exception {
    givenCsv(
        "org_source_id,practitioner_id,location_source_id,org_id,practitioner_source_id,location_id"
            + "\n,p-123,SRC-LOC,,,");
    when(practitionerService.getPractitioner("p-123")).thenReturn(new Practitioner());
    when(locationService.findLocationIdByIdentifier(
            LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-LOC"))
        .thenReturn("loc-found");
    when(practitionerRoleService.executeBundle(any())).thenReturn(successBundle());

    servlet.doPost(request, response);

    verify(practitionerRoleService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  PractitionerRole role = (PractitionerRole) b.getEntry().get(0).getResource();
                  return role != null
                      && "Practitioner/p-123".equals(role.getPractitioner().getReference())
                      && !role.getLocation().isEmpty()
                      && "Location/loc-found".equals(role.getLocation().get(0).getReference());
                }));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private Bundle successBundle(String... fhirIds) {
    Bundle bundle = new Bundle();
    if (fhirIds.length == 0) {
      bundle
          .addEntry()
          .getResponse()
          .setStatus("201 Created")
          .setLocation("PractitionerRole/new-id/_history/1");
    } else {
      for (String fhirId : fhirIds) {
        bundle
            .addEntry()
            .getResponse()
            .setStatus("201 Created")
            .setLocation("PractitionerRole/" + fhirId + "/_history/1");
      }
    }
    return bundle;
  }

  private Bundle mixedBundle(boolean... successes) {
    Bundle bundle = new Bundle();
    int i = 0;
    for (boolean success : successes) {
      Bundle.BundleEntryComponent entry = bundle.addEntry();
      if (success) {
        entry
            .getResponse()
            .setStatus("201 Created")
            .setLocation("PractitionerRole/id-" + i + "/_history/1");
      } else {
        entry.getResponse().setStatus("400 Bad Request");
        OperationOutcome oo = new OperationOutcome();
        oo.addIssue().setDiagnostics("Validation error for entry " + i);
        entry.setResource(oo);
      }
      i++;
    }
    return bundle;
  }

  private void givenCsv(String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    when(request.getPart("file")).thenReturn(filePart);
    when(filePart.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
  }

  private void assertSseContains(String fragment) {
    assertTrue(
        responseBuffer.toString().contains(fragment),
        "Expected SSE output to contain: " + fragment + "\nActual: " + responseBuffer);
  }
}
