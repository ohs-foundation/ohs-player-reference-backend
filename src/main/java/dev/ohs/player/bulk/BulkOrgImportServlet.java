package dev.ohs.player.bulk;

import dev.ohs.player.endpoints.ServletResponseUtil;
import dev.ohs.player.fhir.OrgData;
import dev.ohs.player.fhir.OrganizationService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
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
import org.hl7.fhir.r4.model.Organization;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class BulkOrgImportServlet extends HttpServlet {

  private static final Logger logger = LoggerFactory.getLogger(BulkOrgImportServlet.class);

  private final OrganizationService organizationService;
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

    // Caches for FHIR IDs committed in previous batches. Used to resolve parent references to
    // orgs that were created in an earlier batch without requiring a FHIR search.
    Map<String, String> crossSourceIdToFhirId = new HashMap<>();
    Map<String, String> crossNameToFhirId = new HashMap<>();

    // Intra-batch UUID maps: track orgs accumulating in the current batch so that a later row in
    // the same batch can reference an earlier row's org via urn:uuid without a FHIR search.
    Map<String, String> batchSourceIdToUuid = new HashMap<>();
    Map<String, String> batchNameToUuid = new HashMap<>();

    List<OrgBatchEntry> currentBatch = new ArrayList<>();
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
          OrgBatchEntry entry;
          try {
            entry =
                parseRow(
                    columns,
                    headerIndex,
                    rowNumber,
                    crossSourceIdToFhirId,
                    crossNameToFhirId,
                    batchSourceIdToUuid,
                    batchNameToUuid);
          } catch (IntraBatchFlushSignal e) {
            // Parent is in the current batch. Flush it so the parent is committed and its FHIR ID
            // lands in the cross-batch cache, then retry this row.
            int[] counts =
                flushBatch(currentBatch, writer, crossSourceIdToFhirId, crossNameToFhirId);
            processed += counts[0];
            failed += counts[1];
            if (counts[0] > 0) sseHelper.emitProgress(writer, processed, total);
            currentBatch.clear();
            batchSourceIdToUuid.clear();
            batchNameToUuid.clear();
            entry =
                parseRow(
                    columns,
                    headerIndex,
                    rowNumber,
                    crossSourceIdToFhirId,
                    crossNameToFhirId,
                    batchSourceIdToUuid,
                    batchNameToUuid);
          }

          currentBatch.add(entry);
          if (entry.getOrgData().getSourceId() != null) {
            batchSourceIdToUuid.put(entry.getOrgData().getSourceId(), entry.getUuid());
          }
          batchNameToUuid.put(entry.getOrgData().getName(), entry.getUuid());

          if (currentBatch.size() >= batchSize) {
            int[] counts =
                flushBatch(currentBatch, writer, crossSourceIdToFhirId, crossNameToFhirId);
            processed += counts[0];
            failed += counts[1];
            if (counts[0] > 0) sseHelper.emitProgress(writer, processed, total);
            currentBatch.clear();
            batchSourceIdToUuid.clear();
            batchNameToUuid.clear();
          }
        } catch (BulkImportRowException e) {
          logger.error("Bulk org import row {} failed validation: {}", rowNumber, e.getMessage());
          sseHelper.emitError(writer, e.getClientMessage(), rowNumber);
          failed++;
        }
      }

      if (!currentBatch.isEmpty()) {
        int[] counts = flushBatch(currentBatch, writer, crossSourceIdToFhirId, crossNameToFhirId);
        processed += counts[0];
        failed += counts[1];
        if (counts[0] > 0) sseHelper.emitProgress(writer, processed, total);
      }
    }

    sseHelper.emitDone(writer, processed, failed, total);
  }

  // Returns int[2]: [0] = success count in this batch, [1] = failure count in this batch.
  private int[] flushBatch(
      List<OrgBatchEntry> batch,
      PrintWriter writer,
      Map<String, String> crossSourceIdToFhirId,
      Map<String, String> crossNameToFhirId) {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.BATCH);

    for (OrgBatchEntry entry : batch) {
      Organization org = organizationService.buildOrganization(entry.getOrgData());
      Bundle.BundleEntryComponent bundleEntry = bundle.addEntry();
      bundleEntry.setFullUrl("urn:uuid:" + entry.getUuid());
      bundleEntry.setResource(org);

      Bundle.BundleEntryRequestComponent req = bundleEntry.getRequest();
      String fhirId = entry.getFhirId();
      String sourceId = entry.getOrgData().getSourceId();
      if (fhirId != null) {
        req.setMethod(Bundle.HTTPVerb.PUT);
        req.setUrl("Organization/" + fhirId);
      } else if (sourceId != null) {
        req.setMethod(Bundle.HTTPVerb.PUT);
        req.setUrl(
            "Organization?identifier="
                + URLEncoder.encode(
                    OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM + "|" + sourceId,
                    StandardCharsets.UTF_8));
      } else {
        req.setMethod(Bundle.HTTPVerb.POST);
        req.setUrl("Organization");
      }
    }

    Bundle responseBundle;
    try {
      responseBundle = organizationService.executeBundle(bundle);
    } catch (Exception e) {
      logger.error("FHIR batch execution failed", e);
      for (OrgBatchEntry entry : batch) {
        sseHelper.emitError(
            writer, "An unexpected error occurred processing this row", entry.getRowNumber());
      }
      return new int[] {0, batch.size()};
    }

    int successes = 0;
    int failures = 0;
    List<Bundle.BundleEntryComponent> responseEntries = responseBundle.getEntry();
    for (int i = 0; i < batch.size(); i++) {
      OrgBatchEntry entry = batch.get(i);
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
        String resultId = extractFhirId(responseEntry, entry.getFhirId());
        if (resultId != null) {
          String sourceId = entry.getOrgData().getSourceId();
          if (sourceId != null) crossSourceIdToFhirId.put(sourceId, resultId);
          crossNameToFhirId.put(entry.getOrgData().getName(), resultId);
        }
      } else {
        String errorMsg = extractBatchEntryError(responseEntry);
        logger.error("FHIR batch entry failed at row {}: {}", entry.getRowNumber(), errorMsg);
        sseHelper.emitError(writer, errorMsg, entry.getRowNumber());
        failures++;
      }
    }

    return new int[] {successes, failures};
  }

  private OrgBatchEntry parseRow(
      String[] columns,
      Map<String, Integer> headerIndex,
      int rowNumber,
      Map<String, String> crossSourceIdToFhirId,
      Map<String, String> crossNameToFhirId,
      Map<String, String> batchSourceIdToUuid,
      Map<String, String> batchNameToUuid) {
    String id = csvProcessor.getColumn(columns, headerIndex, "id");
    String name = csvProcessor.getColumn(columns, headerIndex, "name");
    String sourceId = csvProcessor.getColumn(columns, headerIndex, "source_id");
    String isTeamRaw = csvProcessor.getColumn(columns, headerIndex, "is_team");
    String parentId = csvProcessor.getColumn(columns, headerIndex, "parent_id");
    String parentName = csvProcessor.getColumn(columns, headerIndex, "parent_name");
    String sourceParentId = csvProcessor.getColumn(columns, headerIndex, "source_parent_id");

    if (name == null || name.isBlank()) {
      throw new BulkImportRowException("name is required");
    }

    String parentRef =
        resolveParentReference(
            parentId,
            sourceParentId,
            parentName,
            crossSourceIdToFhirId,
            crossNameToFhirId,
            batchSourceIdToUuid,
            batchNameToUuid);

    OrgData data = new OrgData();
    data.setName(name);
    data.setSourceId(sourceId);
    data.setTeam("true".equalsIgnoreCase(isTeamRaw) || "1".equals(isTeamRaw));
    data.setPhone(csvProcessor.getColumn(columns, headerIndex, "phone"));
    data.setEmail(csvProcessor.getColumn(columns, headerIndex, "email"));
    data.setPhysicalAddress(csvProcessor.getColumn(columns, headerIndex, "physical_address"));
    data.setPostalAddress(csvProcessor.getColumn(columns, headerIndex, "postal_address"));
    data.setParentFhirId(parentRef);

    return new OrgBatchEntry(UUID.randomUUID().toString(), data, id, rowNumber);
  }

  private @Nullable String resolveParentReference(
      @Nullable String parentId,
      @Nullable String sourceParentId,
      @Nullable String parentName,
      Map<String, String> crossSourceIdToFhirId,
      Map<String, String> crossNameToFhirId,
      Map<String, String> batchSourceIdToUuid,
      Map<String, String> batchNameToUuid) {
    if (parentId != null) {
      try {
        organizationService.getOrganization(parentId);
      } catch (Exception e) {
        throw new BulkImportRowException("Parent organization not found: " + parentId);
      }
      return "Organization/" + parentId;
    }

    if (sourceParentId != null) {
      // Parent is in the current accumulating batch — FHIR BATCH bundles do not resolve
      // cross-entry urn:uuid references. Signal the caller to flush first, then retry.
      if (batchSourceIdToUuid.containsKey(sourceParentId)) throw new IntraBatchFlushSignal();
      String cached = crossSourceIdToFhirId.get(sourceParentId);
      if (cached != null) return "Organization/" + cached;
      String found =
          organizationService.findOrganizationIdByIdentifier(
              OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM, sourceParentId);
      if (found == null) {
        throw new BulkImportRowException(
            "Parent organization not found with source_id: " + sourceParentId);
      }
      return "Organization/" + found;
    }

    if (parentName != null) {
      if (batchNameToUuid.containsKey(parentName)) throw new IntraBatchFlushSignal();
      String cached = crossNameToFhirId.get(parentName);
      if (cached != null) return "Organization/" + cached;
      String found = organizationService.findOrganizationIdByName(parentName);
      if (found == null) {
        throw new BulkImportRowException("Parent organization not found with name: " + parentName);
      }
      return "Organization/" + found;
    }

    return null;
  }

  private @Nullable String extractFhirId(
      Bundle.BundleEntryComponent responseEntry, @Nullable String knownFhirId) {
    if (knownFhirId != null) return knownFhirId;
    if (responseEntry.getResponse() == null) return null;
    String location = responseEntry.getResponse().getLocation();
    if (location == null || location.isBlank()) return null;
    // Handles: "Organization/id", "Organization/id/_history/1",
    // "http://server/Organization/id/_history/1"
    String[] parts = location.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if ("Organization".equals(parts[i])
          && !parts[i + 1].isBlank()
          && !"_history".equals(parts[i + 1])) {
        return parts[i + 1];
      }
    }
    return null;
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
