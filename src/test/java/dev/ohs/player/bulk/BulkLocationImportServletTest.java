package dev.ohs.player.bulk;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.ohs.player.fhir.LocationService;
import dev.ohs.player.fhir.OrganizationService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BulkLocationImportServletTest {

  @Mock private LocationService locationService;
  @Mock private OrganizationService organizationService;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private Part filePart;

  private CsvProcessor csvProcessor;
  private SseResponseHelper sseHelper;
  // batchSize=1: each row is its own bundle — keeps single-row tests simple.
  private BulkLocationImportServlet servlet;
  private StringWriter responseBuffer;

  private static final String HEADER =
      "id,name,physical_type,level,longitude,latitude,source_id,parent_id,source_parent_id,"
          + "org_id,source_org_id";

  @BeforeEach
  void setUp() throws Exception {
    csvProcessor = new CsvProcessor();
    sseHelper = new SseResponseHelper();
    servlet =
        new BulkLocationImportServlet(
            locationService, organizationService, csvProcessor, sseHelper, 1);
    responseBuffer = new StringWriter();
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseBuffer));
    // buildLocation, resolvePhysicalTypeCode, capitalizeFirst have no external calls — use real
    // implementations so tests can inspect the bundle entries that the servlet builds.
    lenient().when(locationService.buildLocation(any())).thenCallRealMethod();
    lenient().when(locationService.resolvePhysicalTypeCode(any())).thenCallRealMethod();
    lenient().when(locationService.capitalizeFirst(any())).thenCallRealMethod();
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  @Test
  void doPost_missingFilePart_returns400() throws Exception {
    when(request.getPart("file")).thenReturn(null);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(locationService);
  }

  @Test
  void doPost_missingName_emitsErrorAndNoFhirCall() throws Exception {
    givenCsv(HEADER + "\n,,,,,,,,,,");

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    verify(locationService, never()).executeBundle(any());
  }

  // -------------------------------------------------------------------------
  // Bundle method per row type
  // -------------------------------------------------------------------------

  @Test
  void doPost_noIdNoSourceId_sendsPost() throws Exception {
    givenCsv(HEADER + "\n,Clinic A,,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService)
        .executeBundle(
            argThat(
                b ->
                    b != null
                        && b.getEntry().size() == 1
                        && b.getEntry().get(0).getRequest().getMethod() == Bundle.HTTPVerb.POST));
    assertSseContains("\"processed\":1,\"total\":1");
    assertSseContains("\"done\":true,\"processed\":1,\"failed\":0,\"total\":1");
  }

  @Test
  void doPost_rowWithId_sendsDirectPut() throws Exception {
    givenCsv(HEADER + "\nloc-123,Ward 1,,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("loc-123"));

    servlet.doPost(request, response);

    verify(locationService)
        .executeBundle(
            argThat(
                b ->
                    b != null
                        && b.getEntry().size() == 1
                        && b.getEntry().get(0).getRequest().getMethod() == Bundle.HTTPVerb.PUT
                        && b.getEntry().get(0).getRequest().getUrl().equals("Location/loc-123")));
  }

  @Test
  void doPost_rowWithSourceId_sendsConditionalPut() throws Exception {
    givenCsv(HEADER + "\n,Ward 1,,,,,SRC-1,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService)
        .executeBundle(
            argThat(
                b ->
                    b != null
                        && b.getEntry().size() == 1
                        && b.getEntry().get(0).getRequest().getMethod() == Bundle.HTTPVerb.PUT
                        && b.getEntry().get(0).getRequest().getUrl().contains("SRC-1")));
  }

  // -------------------------------------------------------------------------
  // physicalType and level fields
  // -------------------------------------------------------------------------

  @Test
  void doPost_knownPhysicalType_setsPhysicalTypeCoding() throws Exception {
    givenCsv(HEADER + "\n,Ward 1,ward,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null
                      && "wa".equals(loc.getPhysicalType().getCodingFirstRep().getCode())
                      && "Ward".equals(loc.getPhysicalType().getCodingFirstRep().getDisplay());
                }));
  }

  @Test
  void doPost_unknownPhysicalType_usesOtherCode() throws Exception {
    givenCsv(HEADER + "\n,Lab 1,laboratory,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null
                      && "other".equals(loc.getPhysicalType().getCodingFirstRep().getCode())
                      && "Laboratory"
                          .equals(loc.getPhysicalType().getCodingFirstRep().getDisplay());
                }));
  }

  @Test
  void doPost_level_setsTypeCoding() throws Exception {
    givenCsv(HEADER + "\n,County,,county,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null
                      && !loc.getType().isEmpty()
                      && "county".equals(loc.getTypeFirstRep().getCodingFirstRep().getCode())
                      && "http://ohs.dev/codes/administrative-level"
                          .equals(loc.getTypeFirstRep().getCodingFirstRep().getSystem());
                }));
  }

  // -------------------------------------------------------------------------
  // Parent location resolution
  // -------------------------------------------------------------------------

  @Test
  void doPost_parentById_validatesViaGetLocation() throws Exception {
    givenCsv(HEADER + "\n,Child Loc,,,,,,,,,");
    // We need parent_id column — rebuild with direct parent id
    givenCsv(HEADER + "\n,Child Loc,,,,,,parent-99,,,");
    when(locationService.getLocation("parent-99")).thenReturn(namedLocation("Parent"));
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService).getLocation("parent-99");
    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null && "Location/parent-99".equals(loc.getPartOf().getReference());
                }));
  }

  @Test
  void doPost_parentBySourceParentId_resolvesViaIdentifierSearch() throws Exception {
    givenCsv(HEADER + "\n,Child Loc,,,,,,,,SRC-PARENT,");
    // Wait, column 8 is source_parent_id:
    // id,name,physical_type,level,longitude,latitude,source_id,parent_id,source_parent_id,org_id,source_org_id
    givenCsv(HEADER + "\n,Child Loc,,,,,,, SRC-PARENT,,");
    when(locationService.findLocationIdByIdentifier(
            LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-PARENT"))
        .thenReturn("parent-found");
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null
                      && "Location/parent-found".equals(loc.getPartOf().getReference());
                }));
  }

  @Test
  void doPost_parentIdNotFound_emitsErrorAndContinues() throws Exception {
    // parent_id is column index 7: 6 commas between name and the value
    givenCsv(HEADER + "\n,Child Loc,,,,,,missing-parent,,,\n,Other Loc,,,,,,,,,");
    when(locationService.getLocation("missing-parent"))
        .thenThrow(new RuntimeException("Not found"));
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":1");
  }

  @Test
  void doPost_sourceParentIdNotFound_emitsErrorAndContinues() throws Exception {
    givenCsv(HEADER + "\n,Child Loc,,,,,,, SRC-GONE,,\n,Other Loc,,,,,,,,,");
    when(locationService.findLocationIdByIdentifier(any(), eq("SRC-GONE"))).thenReturn(null);
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":1");
  }

  // -------------------------------------------------------------------------
  // Managing organization resolution
  // -------------------------------------------------------------------------

  @Test
  void doPost_managingOrgById_setsManagingOrganization() throws Exception {
    givenCsv(HEADER + "\n,Clinic A,,,,,,,, org-42,");
    when(organizationService.getOrganization("org-42")).thenReturn(new Organization());
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService).getOrganization("org-42");
    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null
                      && "Organization/org-42".equals(loc.getManagingOrganization().getReference());
                }));
  }

  @Test
  void doPost_managingOrgBySourceOrgId_resolvesViaIdentifierSearch() throws Exception {
    givenCsv(HEADER + "\n,Clinic A,,,,,,,,, SRC-ORG");
    when(organizationService.findOrganizationIdByIdentifier(
            OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-ORG"))
        .thenReturn("org-found");
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null
                      && "Organization/org-found"
                          .equals(loc.getManagingOrganization().getReference());
                }));
  }

  @Test
  void doPost_managingOrgNotFound_emitsErrorAndContinues() throws Exception {
    givenCsv(HEADER + "\n,Clinic A,,,,,,,, missing-org,\n,Other Loc,,,,,,,,,");
    when(organizationService.getOrganization("missing-org"))
        .thenThrow(new RuntimeException("Not found"));
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":1");
  }

  // -------------------------------------------------------------------------
  // Intra-batch parent resolution
  // -------------------------------------------------------------------------

  @Test
  void doPost_intraBatchParentBySourceId_flushesParentFirstThenRetries() throws Exception {
    // Row 1 (parent, source_id=SRC-P1) and row 2 (child, source_parent_id=SRC-P1) land in the
    // same batch of 2. FHIR BATCH bundles do not resolve urn:uuid cross-references, so the servlet
    // detects the intra-batch dependency, flushes the parent first, then retries the child — which
    // now resolves the parent from the cross-batch cache as Location/fhir-parent.
    BulkLocationImportServlet batchServlet =
        new BulkLocationImportServlet(
            locationService, organizationService, csvProcessor, sseHelper, 2);
    // source_id=col6 (5 empty after name), source_parent_id=col8 (7 empty after name)
    givenCsv(HEADER + "\n,Parent Loc,,,,,SRC-P1,,,,\n,Child Loc,,,,,,,SRC-P1,,");
    when(locationService.executeBundle(any()))
        .thenReturn(successBundle("fhir-parent"))
        .thenReturn(successBundle("fhir-child"));

    batchServlet.doPost(request, response);

    // Parent flushed alone, then fix bundle for parent, then child, then fix bundle for child
    verify(locationService, times(4)).executeBundle(any());
    // Child bundle: references the committed parent by FHIR ID (not urn:uuid)
    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().size() != 1) return false;
                  Location child = (Location) b.getEntry().get(0).getResource();
                  return child != null
                      && child.getPartOf() != null
                      && "Location/fhir-parent".equals(child.getPartOf().getReference());
                }));
    // No FHIR identifier search — parent resolved from cross-batch cache
    verify(locationService, never()).findLocationIdByIdentifier(any(), eq("SRC-P1"));
    assertSseContains("\"processed\":2,\"total\":2");
  }

  // -------------------------------------------------------------------------
  // Cross-batch parent cache
  // -------------------------------------------------------------------------

  @Test
  void doPost_crossBatchParent_resolvedFromCacheWithoutFhirSearch() throws Exception {
    // Batch 1 (size 1): creates parent with source_id=SRC-P2, FHIR assigns "fhir-parent".
    // Batch 2 (size 1): child references parent by source_parent_id=SRC-P2.
    // findLocationIdByIdentifier must NOT be called for the parent in batch 2.
    BulkLocationImportServlet batchServlet =
        new BulkLocationImportServlet(
            locationService, organizationService, csvProcessor, sseHelper, 1);
    givenCsv(HEADER + "\n,Parent Loc,,,,,SRC-P2,,,,\n,Child Loc,,,,,,,SRC-P2,,");
    when(locationService.executeBundle(any()))
        .thenReturn(successBundle("fhir-parent"))
        .thenReturn(successBundle("fhir-child"));

    batchServlet.doPost(request, response);

    verify(locationService, times(4)).executeBundle(any());
    verify(locationService, never()).findLocationIdByIdentifier(any(), eq("SRC-P2"));
    assertSseContains("\"processed\":2,\"total\":2");
  }

  // -------------------------------------------------------------------------
  // Alias building
  // -------------------------------------------------------------------------

  @Test
  void doPost_noParent_aliasPathIsLocationNameAndUuid() throws Exception {
    givenCsv(HEADER + "\n,Clinic A,,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null
                      && loc.getAlias().size() == 2
                      && "Clinic A".equals(loc.getAlias().get(0).getValue());
                }));
  }

  @Test
  void doPost_parentFromCrossBatch_childAliasIncludesParentNamePath() throws Exception {
    // Batch 1: parent "Country" with source_id=SRC-C → gets FHIR ID "fhir-country"
    // Batch 2: child "County" with source_parent_id=SRC-C → alias should be "Country/County"
    BulkLocationImportServlet batchServlet =
        new BulkLocationImportServlet(
            locationService, organizationService, csvProcessor, sseHelper, 1);
    givenCsv(HEADER + "\n,Country,,,,,SRC-C,,,,\n,County,,,,,,,SRC-C,,");
    when(locationService.executeBundle(any()))
        .thenReturn(successBundle("fhir-country"))
        .thenReturn(successBundle("fhir-county"));

    batchServlet.doPost(request, response);

    // County's Location should have alias[0] = "Country/County"
    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().size() != 1) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null
                      && loc.getAlias().size() == 2
                      && "Country/County".equals(loc.getAlias().get(0).getValue());
                }));
  }

  @Test
  void doPost_newLocation_uuidPathAliasContainsRealFhirId() throws Exception {
    givenCsv(HEADER + "\n,Country,,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("fhir-123"));

    servlet.doPost(request, response);

    // Fix bundle: PUT to Location/fhir-123 with the server-assigned ID in the UUID path alias
    verify(locationService, atLeastOnce())
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().size() != 1) return false;
                  Bundle.BundleEntryRequestComponent req = b.getEntry().get(0).getRequest();
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return Bundle.HTTPVerb.PUT.equals(req.getMethod())
                      && "Location/fhir-123".equals(req.getUrl())
                      && loc != null
                      && loc.getAlias().size() == 2
                      && "fhir-123".equals(loc.getAlias().get(1).getValue());
                }));
  }

  // -------------------------------------------------------------------------
  // FHIR batch failures
  // -------------------------------------------------------------------------

  @Test
  void doPost_batchEntryFails_emitsErrorForFailedRowOnly() throws Exception {
    BulkLocationImportServlet batchServlet =
        new BulkLocationImportServlet(
            locationService, organizationService, csvProcessor, sseHelper, 3);
    givenCsv(HEADER + "\n,Loc A,,,,,,,,,\n,Loc B,,,,,,,,,\n,Loc C,,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(mixedBundle(true, false, true));

    batchServlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":2");
  }

  @Test
  void doPost_executeBundleThrows_emitsErrorForAllRowsInBatch() throws Exception {
    BulkLocationImportServlet batchServlet =
        new BulkLocationImportServlet(
            locationService, organizationService, csvProcessor, sseHelper, 2);
    givenCsv(HEADER + "\n,Loc A,,,,,,,,,\n,Loc B,,,,,,,,,");
    when(locationService.executeBundle(any())).thenThrow(new RuntimeException("FHIR down"));

    batchServlet.doPost(request, response);

    assertSseContains("\"error\"");
  }

  @Test
  void doPost_unexpectedException_hidesInternalDetails() throws Exception {
    givenCsv(HEADER + "\n,Clinic A,,,,,,,,,");
    when(locationService.executeBundle(any()))
        .thenThrow(new RuntimeException("SELECT * FROM locations"));

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertTrue(
        !responseBuffer.toString().contains("SELECT"),
        "Internal exception detail must not be exposed to the client");
  }

  // -------------------------------------------------------------------------
  // Terminal done event
  // -------------------------------------------------------------------------

  @Test
  void doPost_doneEvent_happyPath_zeroFailed() throws Exception {
    BulkLocationImportServlet batchServlet =
        new BulkLocationImportServlet(
            locationService, organizationService, csvProcessor, sseHelper, 2);
    givenCsv(HEADER + "\n,Loc A,,,,,,,,,\n,Loc B,,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("id-a", "id-b"));

    batchServlet.doPost(request, response);

    assertSseContains("\"done\":true,\"processed\":2,\"failed\":0,\"total\":2");
  }

  @Test
  void doPost_doneEvent_withFhirFailures_reflectsCorrectCounts() throws Exception {
    BulkLocationImportServlet batchServlet =
        new BulkLocationImportServlet(
            locationService, organizationService, csvProcessor, sseHelper, 3);
    givenCsv(HEADER + "\n,Loc A,,,,,,,,,\n,Loc B,,,,,,,,,\n,Loc C,,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(mixedBundle(true, false, true));

    batchServlet.doPost(request, response);

    assertSseContains("\"done\":true,\"processed\":2,\"failed\":1,\"total\":3");
  }

  @Test
  void doPost_doneEvent_withParseFailures_reflectsCorrectCounts() throws Exception {
    givenCsv(HEADER + "\n,Loc A,,,,,,,,,\n,,,,,,,,,,\n,Loc C,,,,,,,,,");
    when(locationService.executeBundle(any()))
        .thenReturn(successBundle("id-a"))
        .thenReturn(successBundle("id-c"));

    servlet.doPost(request, response);

    assertSseContains("\"done\":true,\"processed\":2,\"failed\":1,\"total\":3");
  }

  // -------------------------------------------------------------------------
  // Progress emitted once per batch, not per row
  // -------------------------------------------------------------------------

  @Test
  void doPost_progressEmittedOncePerBatch() throws Exception {
    BulkLocationImportServlet batchServlet =
        new BulkLocationImportServlet(
            locationService, organizationService, csvProcessor, sseHelper, 3);
    givenCsv(HEADER + "\n,Loc A,,,,,,,,,\n,Loc B,,,,,,,,,\n,Loc C,,,,,,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("id-a", "id-b", "id-c"));

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
  // Column order independence
  // -------------------------------------------------------------------------

  @Test
  void doPost_shuffledColumns_parsedCorrectly() throws Exception {
    givenCsv(
        "name,level,source_id,id,physical_type,longitude,latitude,parent_id,source_parent_id,"
            + "org_id,source_org_id"
            + "\nClinic A,facility,SRC-99,,building,36.8,-1.3,,,,");
    when(locationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(locationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Location loc = (Location) b.getEntry().get(0).getResource();
                  return loc != null
                      && "Clinic A".equals(loc.getName())
                      && "bu".equals(loc.getPhysicalType().getCodingFirstRep().getCode())
                      && "facility".equals(loc.getTypeFirstRep().getCodingFirstRep().getCode())
                      && b.getEntry().get(0).getRequest().getUrl().contains("SRC-99");
                }));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private Bundle successBundle(String... fhirIds) {
    Bundle bundle = new Bundle();
    for (String fhirId : fhirIds) {
      bundle
          .addEntry()
          .getResponse()
          .setStatus("201 Created")
          .setLocation("Location/" + fhirId + "/_history/1");
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
            .setLocation("Location/id-" + i + "/_history/1");
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

  private Location namedLocation(String name) {
    Location loc = new Location();
    loc.setName(name);
    return loc;
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
