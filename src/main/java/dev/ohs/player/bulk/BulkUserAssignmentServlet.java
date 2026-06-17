package dev.ohs.player.bulk;

import dev.ohs.player.endpoints.ServletResponseUtil;
import dev.ohs.player.fhir.LocationService;
import dev.ohs.player.fhir.OrganizationService;
import dev.ohs.player.fhir.PractitionerRoleData;
import dev.ohs.player.fhir.PractitionerRoleService;
import dev.ohs.player.fhir.PractitionerService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class BulkUserAssignmentServlet extends HttpServlet {

  private static final Logger logger = LoggerFactory.getLogger(BulkUserAssignmentServlet.class);

  private final PractitionerRoleService practitionerRoleService;
  private final PractitionerService practitionerService;
  private final OrganizationService organizationService;
  private final LocationService locationService;
  private final CsvProcessor csvProcessor;
  private final SseResponseHelper sseHelper;
  private final int batchSize;

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Part filePart;
    try {
      filePart = request.getPart("file");
    } catch (Exception e) {
      logger.debug("Failed to read multipart file part", e);
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "Failed to read uploaded file");
      return;
    }

    if (filePart == null) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "Missing required file part: 'file'");
      return;
    }

    Path tempPath = csvProcessor.saveTempFile(filePart);
    try {
      processImport(tempPath, response);
    } finally {
      try {
        Files.deleteIfExists(tempPath);
      } catch (IOException e) {
        logger.warn("Failed to delete temp file: {}", tempPath, e);
      }
    }
  }

  private void processImport(Path csvPath, HttpServletResponse response) throws IOException {
    int total = csvProcessor.countDataRows(csvPath);

    sseHelper.configure(response);
    PrintWriter writer = response.getWriter();

    // Cross-run caches: avoid repeated FHIR lookups for the same source_id across batches.
    Map<String, String> practitionerSourceIdToFhirId = new HashMap<>();
    Map<String, String> orgSourceIdToFhirId = new HashMap<>();
    Map<String, String> locationSourceIdToFhirId = new HashMap<>();

    List<PractitionerRoleBatchEntry> currentBatch = new ArrayList<>();
    int processed = 0;
    int failed = 0;
    int rowNumber = 0;

    try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
      String headerLine = reader.readLine();
      if (headerLine == null) return;
      Map<String, Integer> headerIndex = csvProcessor.buildHeaderIndex(headerLine);

      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        rowNumber++;

        try {
          String[] columns = line.split(",", -1);
          PractitionerRoleBatchEntry entry =
              parseRow(
                  columns,
                  headerIndex,
                  rowNumber,
                  practitionerSourceIdToFhirId,
                  orgSourceIdToFhirId,
                  locationSourceIdToFhirId);
          currentBatch.add(entry);

          if (currentBatch.size() >= batchSize) {
            int[] counts = flushBatch(currentBatch, writer);
            processed += counts[0];
            failed += counts[1];
            if (counts[0] > 0) sseHelper.emitProgress(writer, processed, total);
            currentBatch.clear();
          }
        } catch (BulkImportRowException e) {
          logger.error(
              "Bulk user assignment row {} failed validation: {}", rowNumber, e.getMessage());
          sseHelper.emitError(writer, e.getClientMessage(), rowNumber);
          failed++;
        }
      }

      if (!currentBatch.isEmpty()) {
        int[] counts = flushBatch(currentBatch, writer);
        processed += counts[0];
        failed += counts[1];
        if (counts[0] > 0) sseHelper.emitProgress(writer, processed, total);
      }
    }

    sseHelper.emitDone(writer, processed, failed, total);
  }

  // Returns int[2]: [0] = success count in this batch, [1] = failure count in this batch.
  private int[] flushBatch(List<PractitionerRoleBatchEntry> batch, PrintWriter writer) {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.BATCH);

    for (PractitionerRoleBatchEntry entry : batch) {
      PractitionerRole role = practitionerRoleService.buildPractitionerRole(entry.getData());
      Bundle.BundleEntryComponent bundleEntry = bundle.addEntry();
      bundleEntry.setFullUrl("urn:uuid:" + entry.getUuid());
      bundleEntry.setResource(role);
      bundleEntry.getRequest().setMethod(Bundle.HTTPVerb.POST).setUrl("PractitionerRole");
    }

    Bundle responseBundle;
    try {
      responseBundle = practitionerRoleService.executeBundle(bundle);
    } catch (Exception e) {
      logger.error("FHIR batch execution failed", e);
      for (PractitionerRoleBatchEntry entry : batch) {
        sseHelper.emitError(
            writer, "An unexpected error occurred processing this row", entry.getRowNumber());
      }
      return new int[] {0, batch.size()};
    }

    int successes = 0;
    int failures = 0;
    List<Bundle.BundleEntryComponent> responseEntries = responseBundle.getEntry();
    for (int i = 0; i < batch.size(); i++) {
      PractitionerRoleBatchEntry entry = batch.get(i);
      if (i >= responseEntries.size()) {
        logger.error(
            "FHIR server returned fewer response entries than expected for row {}",
            entry.getRowNumber());
        sseHelper.emitError(writer, "Unexpected server response", entry.getRowNumber());
        failures++;
        continue;
      }

      Bundle.BundleEntryComponent responseEntry = responseEntries.get(i);
      String status =
          responseEntry.getResponse() != null ? responseEntry.getResponse().getStatus() : null;

      if (status != null && (status.startsWith("200") || status.startsWith("201"))) {
        successes++;
      } else {
        String errorMsg = extractBatchEntryError(responseEntry);
        logger.error("FHIR batch entry failed at row {}: {}", entry.getRowNumber(), errorMsg);
        sseHelper.emitError(writer, errorMsg, entry.getRowNumber());
        failures++;
      }
    }

    return new int[] {successes, failures};
  }

  private PractitionerRoleBatchEntry parseRow(
      String[] columns,
      Map<String, Integer> headerIndex,
      int rowNumber,
      Map<String, String> practitionerSourceIdToFhirId,
      Map<String, String> orgSourceIdToFhirId,
      Map<String, String> locationSourceIdToFhirId) {

    String practitionerId = csvProcessor.getColumn(columns, headerIndex, "practitioner_id");
    String practitionerSourceId =
        csvProcessor.getColumn(columns, headerIndex, "practitioner_source_id");
    String orgId = csvProcessor.getColumn(columns, headerIndex, "org_id");
    String orgSourceId = csvProcessor.getColumn(columns, headerIndex, "org_source_id");
    String locationId = csvProcessor.getColumn(columns, headerIndex, "location_id");
    String locationSourceId = csvProcessor.getColumn(columns, headerIndex, "location_source_id");

    String practitionerRef =
        resolvePractitionerReference(
            practitionerId, practitionerSourceId, practitionerSourceIdToFhirId);
    String orgRef = resolveOrgReference(orgId, orgSourceId, orgSourceIdToFhirId);
    List<String> locationRefs =
        resolveLocationReferences(locationId, locationSourceId, locationSourceIdToFhirId);

    PractitionerRoleData data = new PractitionerRoleData();
    data.setPractitionerFhirId(practitionerRef);
    data.setOrganizationFhirId(orgRef);
    data.setLocationFhirIds(locationRefs.isEmpty() ? null : locationRefs);

    return new PractitionerRoleBatchEntry(UUID.randomUUID().toString(), data, rowNumber);
  }

  private String resolvePractitionerReference(
      @Nullable String practitionerId,
      @Nullable String practitionerSourceId,
      Map<String, String> cache) {
    if (practitionerId != null) {
      try {
        practitionerService.getPractitioner(practitionerId);
      } catch (Exception e) {
        throw new BulkImportRowException("Practitioner not found: " + practitionerId);
      }
      return "Practitioner/" + practitionerId;
    }

    if (practitionerSourceId != null) {
      String cached = cache.get(practitionerSourceId);
      if (cached != null) return "Practitioner/" + cached;
      String found =
          practitionerService.findPractitionerIdByIdentifier(
              PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM, practitionerSourceId);
      if (found == null) {
        throw new BulkImportRowException(
            "Practitioner not found with source_id: " + practitionerSourceId);
      }
      cache.put(practitionerSourceId, found);
      return "Practitioner/" + found;
    }

    throw new BulkImportRowException("practitioner_id or practitioner_source_id is required");
  }

  private @Nullable String resolveOrgReference(
      @Nullable String orgId, @Nullable String orgSourceId, Map<String, String> cache) {
    if (orgId != null) {
      try {
        organizationService.getOrganization(orgId);
      } catch (Exception e) {
        throw new BulkImportRowException("Organization not found: " + orgId);
      }
      return "Organization/" + orgId;
    }

    if (orgSourceId != null) {
      String cached = cache.get(orgSourceId);
      if (cached != null) return "Organization/" + cached;
      String found =
          organizationService.findOrganizationIdByIdentifier(
              OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM, orgSourceId);
      if (found == null) {
        throw new BulkImportRowException("Organization not found with source_id: " + orgSourceId);
      }
      cache.put(orgSourceId, found);
      return "Organization/" + found;
    }

    return null;
  }

  private List<String> resolveLocationReferences(
      @Nullable String locationIds, @Nullable String locationSourceIds, Map<String, String> cache) {
    List<String> refs = new ArrayList<>();

    if (locationIds != null) {
      for (String id : locationIds.split(";", -1)) {
        String trimmed = id.trim();
        if (trimmed.isBlank()) continue;
        try {
          locationService.getLocation(trimmed);
        } catch (Exception e) {
          throw new BulkImportRowException("Location not found: " + trimmed);
        }
        refs.add("Location/" + trimmed);
      }
      return refs;
    }

    if (locationSourceIds != null) {
      for (String sourceId : locationSourceIds.split(";", -1)) {
        String trimmed = sourceId.trim();
        if (trimmed.isBlank()) continue;
        String cached = cache.get(trimmed);
        if (cached != null) {
          refs.add("Location/" + cached);
          continue;
        }
        String found =
            locationService.findLocationIdByIdentifier(
                LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, trimmed);
        if (found == null) {
          throw new BulkImportRowException("Location not found with source_id: " + trimmed);
        }
        cache.put(trimmed, found);
        refs.add("Location/" + found);
      }
    }

    return refs;
  }

  private String extractBatchEntryError(Bundle.BundleEntryComponent responseEntry) {
    if (responseEntry.getResource() instanceof OperationOutcome) {
      OperationOutcome oo = (OperationOutcome) responseEntry.getResource();
      if (!oo.getIssue().isEmpty()) {
        String diag = oo.getIssueFirstRep().getDiagnostics();
        if (diag != null && !diag.isBlank()) return diag;
      }
    }
    String status =
        responseEntry.getResponse() != null ? responseEntry.getResponse().getStatus() : "unknown";
    return "Server returned status: " + status;
  }
}
