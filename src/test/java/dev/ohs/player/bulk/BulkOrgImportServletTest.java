package dev.ohs.player.bulk;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.ohs.player.fhir.OrganizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BulkOrgImportServletTest {

  @Mock private OrganizationService organizationService;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private Part filePart;

  private CsvProcessor csvProcessor;
  private SseResponseHelper sseHelper;
  // batchSize=1: each row is its own bundle — keeps single-row tests simple.
  // Multi-row tests that verify batch behaviour create a servlet with batchSize=2.
  private BulkOrgImportServlet servlet;
  private StringWriter responseBuffer;

  private static final String HEADER =
      "id,name,is_team,source_id,parent_id,parent_name,source_parent_id,phone,email,"
          + "physical_address,postal_address";

  @BeforeEach
  void setUp() throws Exception {
    csvProcessor = new CsvProcessor();
    sseHelper = new SseResponseHelper();
    servlet = new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 1);
    responseBuffer = new StringWriter();
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseBuffer));
    // buildOrganization has no external calls, so use the real implementation to let tests
    // inspect the bundle entries that the servlet builds.
    lenient().when(organizationService.buildOrganization(any())).thenCallRealMethod();
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  @Test
  void doPost_missingFilePart_returns400() throws Exception {
    when(request.getPart("file")).thenReturn(null);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(organizationService);
  }

  @Test
  void doPost_missingName_emitsErrorAndNoFhirCall() throws Exception {
    givenCsv(HEADER + "\n,,,,,,,,,,");

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    verify(organizationService, never()).executeBundle(any());
  }

  // -------------------------------------------------------------------------
  // Bundle method per row type
  // -------------------------------------------------------------------------

  @Test
  void doPost_noIdNoSourceId_sendsPost() throws Exception {
    givenCsv(HEADER + "\n,Clinic A,,,,,,,,, ");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService)
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
    givenCsv(HEADER + "\norg-123,Updated Org,,,,,,,,,");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("org-123"));

    servlet.doPost(request, response);

    verify(organizationService)
        .executeBundle(
            argThat(
                b ->
                    b != null
                        && b.getEntry().size() == 1
                        && b.getEntry().get(0).getRequest().getMethod() == Bundle.HTTPVerb.PUT
                        && b.getEntry()
                            .get(0)
                            .getRequest()
                            .getUrl()
                            .equals("Organization/org-123")));
  }

  @Test
  void doPost_rowWithSourceId_sendsConditionalPut() throws Exception {
    givenCsv(HEADER + "\n,Org Name,,SRC-1,,,,,,,");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService)
        .executeBundle(
            argThat(
                b ->
                    b != null
                        && b.getEntry().size() == 1
                        && b.getEntry().get(0).getRequest().getMethod() == Bundle.HTTPVerb.PUT
                        && b.getEntry().get(0).getRequest().getUrl().contains("SRC-1")));
  }

  // -------------------------------------------------------------------------
  // is_team flag
  // -------------------------------------------------------------------------

  @Test
  void doPost_isTeamTrue_setsTeamTypeOnBuiltOrg() throws Exception {
    givenCsv(HEADER + "\n,Team Blue,true,,,,,,,,");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Organization org = (Organization) b.getEntry().get(0).getResource();
                  return org != null
                      && !org.getType().isEmpty()
                      && "team".equals(org.getTypeFirstRep().getCodingFirstRep().getCode());
                }));
  }

  @Test
  void doPost_isTeam1_setsTeamTypeOnBuiltOrg() throws Exception {
    givenCsv(HEADER + "\n,Team Blue,1,,,,,,,,");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Organization org = (Organization) b.getEntry().get(0).getResource();
                  return org != null && !org.getType().isEmpty();
                }));
  }

  @Test
  void doPost_isTeamBlank_noTypeOnBuiltOrg() throws Exception {
    givenCsv(HEADER + "\n,Clinic A,,,,,,,,,");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Organization org = (Organization) b.getEntry().get(0).getResource();
                  return org != null && org.getType().isEmpty();
                }));
  }

  // -------------------------------------------------------------------------
  // Parent resolution — cross-batch (FHIR search path)
  // -------------------------------------------------------------------------

  @Test
  void doPost_parentById_validatesViaGetOrganization() throws Exception {
    givenCsv(HEADER + "\n,Child Org,,,parent-99,,,,,,");
    when(organizationService.getOrganization("parent-99")).thenReturn(new Organization());
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService).getOrganization("parent-99");
    verify(organizationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Organization org = (Organization) b.getEntry().get(0).getResource();
                  return org != null
                      && "Organization/parent-99".equals(org.getPartOf().getReference());
                }));
  }

  @Test
  void doPost_parentBySourceParentId_resolvesViaIdentifierSearch() throws Exception {
    givenCsv(HEADER + "\n,Child Org,,,,,SRC-PARENT,,,,");
    when(organizationService.findOrganizationIdByIdentifier(
            OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-PARENT"))
        .thenReturn("parent-found");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Organization org = (Organization) b.getEntry().get(0).getResource();
                  return org != null
                      && "Organization/parent-found".equals(org.getPartOf().getReference());
                }));
  }

  @Test
  void doPost_parentByName_resolvesViaNameSearch() throws Exception {
    givenCsv(HEADER + "\n,Child Org,,,,Parent Org,,,,,");
    when(organizationService.findOrganizationIdByName("Parent Org")).thenReturn("parent-by-name");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Organization org = (Organization) b.getEntry().get(0).getResource();
                  return org != null
                      && "Organization/parent-by-name".equals(org.getPartOf().getReference());
                }));
  }

  @Test
  void doPost_parentIdNotFound_emitsErrorAndContinues() throws Exception {
    givenCsv(HEADER + "\n,Child Org,,,missing-parent,,,,,,\n,Other Org,,,,,,,,,");
    when(organizationService.getOrganization("missing-parent"))
        .thenThrow(new RuntimeException("Not found"));
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    // Other Org in row 2 still processed
    assertSseContains("\"processed\":1");
  }

  @Test
  void doPost_sourceParentIdNotFound_emitsErrorAndContinues() throws Exception {
    givenCsv(HEADER + "\n,Child Org,,,,,SRC-GONE,,,,\n,Other Org,,,,,,,,,");
    when(organizationService.findOrganizationIdByIdentifier(any(), eq("SRC-GONE")))
        .thenReturn(null);
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":1");
  }

  @Test
  void doPost_parentNameNotFound_emitsErrorAndContinues() throws Exception {
    givenCsv(HEADER + "\n,Child Org,,,,Unknown Parent,,,,\n,Other Org,,,,,,,,,");
    when(organizationService.findOrganizationIdByName("Unknown Parent")).thenReturn(null);
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":1");
  }

  // -------------------------------------------------------------------------
  // Intra-batch parent resolution — parent and child in same batch
  // -------------------------------------------------------------------------

  @Test
  void doPost_intraBatchParentBySourceId_flushesParentFirstThenRetries() throws Exception {
    // Row 1 (parent, source_id=SRC-P1) and row 2 (child, source_parent_id=SRC-P1) land in the
    // same batch of 2. FHIR BATCH bundles do not resolve urn:uuid cross-references, so the servlet
    // detects the intra-batch dependency, flushes a 1-entry bundle for the parent, then retries
    // the child — which now resolves the parent from the cross-batch cache as
    // Organization/fhir-parent.
    BulkOrgImportServlet batchServlet =
        new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 2);
    givenCsv(HEADER + "\n,Parent Org,,SRC-P1,,,,,,,\n,Child Org,,,,,SRC-P1,,,,");
    when(organizationService.executeBundle(any()))
        .thenReturn(successBundle("fhir-parent"))
        .thenReturn(successBundle("fhir-child"));

    batchServlet.doPost(request, response);

    // Parent flushed alone, then child in a second bundle
    verify(organizationService, times(2)).executeBundle(any());
    // Second bundle: child references the committed parent by FHIR ID (not urn:uuid)
    verify(organizationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().size() != 1) return false;
                  Organization child = (Organization) b.getEntry().get(0).getResource();
                  return child != null
                      && child.getPartOf() != null
                      && "Organization/fhir-parent".equals(child.getPartOf().getReference());
                }));
    // No FHIR identifier search — parent resolved from cross-batch cache
    verify(organizationService, never()).findOrganizationIdByIdentifier(any(), eq("SRC-P1"));
    assertSseContains("\"processed\":2,\"total\":2");
  }

  @Test
  void doPost_intraBatchParentByName_flushesParentFirstThenRetries() throws Exception {
    // Row 1 creates "Parent Org", row 2 references it by parent_name in the same batch of 2.
    // Servlet detects the intra-batch dependency, flushes the parent first, then retries the child
    // which resolves the parent from the cross-batch cache — no FHIR name search.
    BulkOrgImportServlet batchServlet =
        new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 2);
    givenCsv(HEADER + "\n,Parent Org,,,,,,,,,\n,Child Org,,,,Parent Org,,,,,");
    when(organizationService.executeBundle(any()))
        .thenReturn(successBundle("fhir-parent"))
        .thenReturn(successBundle("fhir-child"));

    batchServlet.doPost(request, response);

    verify(organizationService, times(2)).executeBundle(any());
    // Child bundle: partOf must be Organization/fhir-parent (from cross-batch cache, not urn:uuid)
    verify(organizationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().size() != 1) return false;
                  Organization child = (Organization) b.getEntry().get(0).getResource();
                  return child != null
                      && child.getPartOf() != null
                      && "Organization/fhir-parent".equals(child.getPartOf().getReference());
                }));
    verify(organizationService, never()).findOrganizationIdByName(any());
    assertSseContains("\"processed\":2,\"total\":2");
  }

  @Test
  void doPost_crossBatchParent_resolvedFromCache() throws Exception {
    // Batch 1 (size 1): creates parent, FHIR assigns ID "fhir-parent".
    // Batch 2 (size 1): child references parent by source_parent_id.
    // findOrganizationIdByIdentifier must NOT be called for the parent in batch 2 — cache hit.
    BulkOrgImportServlet batchServlet =
        new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 1);
    givenCsv(HEADER + "\n,Parent Org,,SRC-P2,,,,,,,\n,Child Org,,,,,SRC-P2,,,,");
    when(organizationService.executeBundle(any()))
        .thenReturn(successBundle("fhir-parent"))
        .thenReturn(successBundle("fhir-child"));

    batchServlet.doPost(request, response);

    // executeBundle called twice (one batch per row), never a FHIR identifier search for SRC-P2
    verify(organizationService, times(2)).executeBundle(any());
    verify(organizationService, never()).findOrganizationIdByIdentifier(any(), eq("SRC-P2"));
    assertSseContains("\"processed\":2,\"total\":2");
  }

  // -------------------------------------------------------------------------
  // FHIR batch entry failures — partial success, continue processing
  // -------------------------------------------------------------------------

  @Test
  void doPost_batchEntryFails_emitsErrorForFailedRowOnly() throws Exception {
    BulkOrgImportServlet batchServlet =
        new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 3);
    givenCsv(HEADER + "\n,Org A,,,,,,,,,\n,Org B,,,,,,,,,\n,Org C,,,,,,,,,");
    // Row 2 (Org B) fails, rows 1 and 3 succeed
    when(organizationService.executeBundle(any())).thenReturn(mixedBundle(true, false, true));

    batchServlet.doPost(request, response);

    assertSseContains("\"error\"");
    assertSseContains("\"processed\":2");
  }

  @Test
  void doPost_partialBatchSuccess_continuesProcessingNextBatch() throws Exception {
    // Batch 1 (size 2): row 1 passes, row 2 fails.
    // Batch 2 (size 2): rows 3 and 4 both pass.
    BulkOrgImportServlet batchServlet =
        new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 2);
    givenCsv(HEADER + "\n,Org A,,,,,,,,,\n,Org B,,,,,,,,,\n,Org C,,,,,,,,,\n,Org D,,,,,,,,,");
    when(organizationService.executeBundle(any()))
        .thenReturn(mixedBundle(true, false))
        .thenReturn(successBundle("id-c", "id-d"));

    batchServlet.doPost(request, response);

    verify(organizationService, times(2)).executeBundle(any());
    assertSseContains("\"processed\":3");
    assertSseContains("\"error\"");
  }

  @Test
  void doPost_executeBundleThrows_emitsErrorForAllRowsInBatch() throws Exception {
    BulkOrgImportServlet batchServlet =
        new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 2);
    givenCsv(HEADER + "\n,Org A,,,,,,,,,\n,Org B,,,,,,,,,");
    when(organizationService.executeBundle(any())).thenThrow(new RuntimeException("FHIR down"));

    batchServlet.doPost(request, response);

    assertSseContains("\"error\"");
  }

  @Test
  void doPost_unexpectedException_hidesInternalDetails() throws Exception {
    givenCsv(HEADER + "\n,Clinic A,,,,,,,,,");
    when(organizationService.executeBundle(any()))
        .thenThrow(new RuntimeException("SELECT * FROM organizations"));

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
    BulkOrgImportServlet batchServlet =
        new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 2);
    givenCsv(HEADER + "\n,Org A,,,,,,,,,\n,Org B,,,,,,,,,");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("id-a", "id-b"));

    batchServlet.doPost(request, response);

    assertSseContains("\"done\":true,\"processed\":2,\"failed\":0,\"total\":2");
  }

  @Test
  void doPost_doneEvent_withFhirFailures_reflectsCorrectCounts() throws Exception {
    BulkOrgImportServlet batchServlet =
        new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 3);
    givenCsv(HEADER + "\n,Org A,,,,,,,,,\n,Org B,,,,,,,,,\n,Org C,,,,,,,,,");
    // Row 2 (Org B) fails
    when(organizationService.executeBundle(any())).thenReturn(mixedBundle(true, false, true));

    batchServlet.doPost(request, response);

    assertSseContains("\"done\":true,\"processed\":2,\"failed\":1,\"total\":3");
  }

  @Test
  void doPost_doneEvent_withParseFailures_reflectsCorrectCounts() throws Exception {
    // Row 1 parses fine; row 2 has no name (parse failure); row 3 parses fine.
    givenCsv(HEADER + "\n,Org A,,,,,,,,,\n,,,,,,,,,,\n,Org C,,,,,,,,,");
    when(organizationService.executeBundle(any()))
        .thenReturn(successBundle("id-a"))
        .thenReturn(successBundle("id-c"));

    servlet.doPost(request, response);

    assertSseContains("\"done\":true,\"processed\":2,\"failed\":1,\"total\":3");
  }

  // -------------------------------------------------------------------------
  // Progress is emitted once per batch, not per row
  // -------------------------------------------------------------------------

  @Test
  void doPost_progressEmittedOncePerBatch() throws Exception {
    BulkOrgImportServlet batchServlet =
        new BulkOrgImportServlet(organizationService, csvProcessor, sseHelper, 3);
    givenCsv(HEADER + "\n,Org A,,,,,,,,,\n,Org B,,,,,,,,,\n,Org C,,,,,,,,,");
    when(organizationService.executeBundle(any()))
        .thenReturn(successBundle("id-a", "id-b", "id-c"));

    batchServlet.doPost(request, response);

    // One progress event for the full batch of 3, not three separate events
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
        "name,is_team,source_id,id,parent_id,parent_name,source_parent_id,phone,email,"
            + "physical_address,postal_address"
            + "\nClinic A,true,SRC-99,,,,,,,,");
    when(organizationService.executeBundle(any())).thenReturn(successBundle("new-id"));

    servlet.doPost(request, response);

    verify(organizationService)
        .executeBundle(
            argThat(
                b -> {
                  if (b == null || b.getEntry().isEmpty()) return false;
                  Organization org = (Organization) b.getEntry().get(0).getResource();
                  return org != null
                      && "Clinic A".equals(org.getName())
                      && !org.getType().isEmpty()
                      && b.getEntry().get(0).getRequest().getUrl().contains("SRC-99");
                }));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private Bundle successBundle(String... fhirIds) {
    Bundle response = new Bundle();
    for (String fhirId : fhirIds) {
      Bundle.BundleEntryComponent entry = response.addEntry();
      entry
          .getResponse()
          .setStatus("201 Created")
          .setLocation("Organization/" + fhirId + "/_history/1");
    }
    return response;
  }

  private Bundle mixedBundle(boolean... successes) {
    Bundle response = new Bundle();
    int i = 0;
    for (boolean success : successes) {
      Bundle.BundleEntryComponent entry = response.addEntry();
      if (success) {
        entry
            .getResponse()
            .setStatus("201 Created")
            .setLocation("Organization/id-" + i + "/_history/1");
      } else {
        entry.getResponse().setStatus("400 Bad Request");
        OperationOutcome oo = new OperationOutcome();
        oo.addIssue().setDiagnostics("Validation error for entry " + i);
        entry.setResource(oo);
      }
      i++;
    }
    return response;
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
