package dev.ohs.player.bulk;

import dev.ohs.player.auth.AuthorizationHandler;
import dev.ohs.player.auth.RoleLevel;
import dev.ohs.player.endpoints.ServletResponseUtil;
import dev.ohs.player.fhir.LocationData;
import dev.ohs.player.fhir.LocationService;
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
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class BulkLocationImportServlet extends HttpServlet {

  private static final Logger logger = LoggerFactory.getLogger(BulkLocationImportServlet.class);

  private final LocationService locationService;
  private final OrganizationService organizationService;
  private final CsvProcessor csvProcessor;
  private final SseResponseHelper sseHelper;
  private final int batchSize;

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!AuthorizationHandler.require(request, response, "bulk-import", RoleLevel.MANAGE)) return;
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

    // Caches for FHIR IDs committed in previous batches.
    Map<String, String> crossSourceIdToFhirId = new HashMap<>();
    Map<String, String> crossNameToFhirId = new HashMap<>();
    // Materialized path caches for alias building.
    Map<String, String> crossFhirIdToNamePath = new HashMap<>();
    Map<String, String> crossFhirIdToUuidPath = new HashMap<>();

    // Intra-batch maps: detect when a child's parent is still accumulating in the current batch.
    Map<String, String> batchSourceIdToUuid = new HashMap<>();
    Map<String, String> batchNameToUuid = new HashMap<>();

    List<LocationBatchEntry> currentBatch = new ArrayList<>();
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
          LocationBatchEntry entry;
          try {
            entry =
                parseRow(
                    columns,
                    headerIndex,
                    rowNumber,
                    crossSourceIdToFhirId,
                    crossNameToFhirId,
                    crossFhirIdToNamePath,
                    crossFhirIdToUuidPath,
                    batchSourceIdToUuid,
                    batchNameToUuid);
          } catch (IntraBatchFlushSignal e) {
            // Parent is in the current batch. Flush it so the parent is committed and its FHIR ID
            // lands in the cross-batch cache, then retry this row.
            int[] counts =
                flushBatch(
                    currentBatch,
                    writer,
                    crossSourceIdToFhirId,
                    crossNameToFhirId,
                    crossFhirIdToNamePath,
                    crossFhirIdToUuidPath);
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
                    crossFhirIdToNamePath,
                    crossFhirIdToUuidPath,
                    batchSourceIdToUuid,
                    batchNameToUuid);
          }

          currentBatch.add(entry);
          if (entry.getLocationData().getSourceId() != null) {
            batchSourceIdToUuid.put(entry.getLocationData().getSourceId(), entry.getUuid());
          }
          batchNameToUuid.put(entry.getLocationData().getName(), entry.getUuid());

          if (currentBatch.size() >= batchSize) {
            int[] counts =
                flushBatch(
                    currentBatch,
                    writer,
                    crossSourceIdToFhirId,
                    crossNameToFhirId,
                    crossFhirIdToNamePath,
                    crossFhirIdToUuidPath);
            processed += counts[0];
            failed += counts[1];
            if (counts[0] > 0) sseHelper.emitProgress(writer, processed, total);
            currentBatch.clear();
            batchSourceIdToUuid.clear();
            batchNameToUuid.clear();
          }
        } catch (BulkImportRowException e) {
          logger.error(
              "Bulk location import row {} failed validation: {}", rowNumber, e.getMessage());
          sseHelper.emitError(writer, e.getClientMessage(), rowNumber);
          failed++;
        }
      }

      if (!currentBatch.isEmpty()) {
        int[] counts =
            flushBatch(
                currentBatch,
                writer,
                crossSourceIdToFhirId,
                crossNameToFhirId,
                crossFhirIdToNamePath,
                crossFhirIdToUuidPath);
        processed += counts[0];
        failed += counts[1];
        if (counts[0] > 0) sseHelper.emitProgress(writer, processed, total);
      }
    }

    sseHelper.emitDone(writer, processed, failed, total);
  }

  // Returns int[2]: [0] = success count in this batch, [1] = failure count in this batch.
  private int[] flushBatch(
      List<LocationBatchEntry> batch,
      PrintWriter writer,
      Map<String, String> crossSourceIdToFhirId,
      Map<String, String> crossNameToFhirId,
      Map<String, String> crossFhirIdToNamePath,
      Map<String, String> crossFhirIdToUuidPath) {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.BATCH);

    for (LocationBatchEntry entry : batch) {
      Location loc = locationService.buildLocation(entry.getLocationData());
      Bundle.BundleEntryComponent bundleEntry = bundle.addEntry();
      bundleEntry.setFullUrl("urn:uuid:" + entry.getUuid());
      bundleEntry.setResource(loc);

      Bundle.BundleEntryRequestComponent req = bundleEntry.getRequest();
      String fhirId = entry.getFhirId();
      String sourceId = entry.getLocationData().getSourceId();
      if (fhirId != null) {
        req.setMethod(Bundle.HTTPVerb.PUT);
        req.setUrl("Location/" + fhirId);
      } else if (sourceId != null) {
        req.setMethod(Bundle.HTTPVerb.PUT);
        req.setUrl(
            "Location?identifier="
                + URLEncoder.encode(
                    LocationService.SOURCE_ID_IDENTIFIER_SYSTEM + "|" + sourceId,
                    StandardCharsets.UTF_8));
      } else {
        req.setMethod(Bundle.HTTPVerb.POST);
        req.setUrl("Location");
      }
    }

    Bundle responseBundle;
    try {
      responseBundle = locationService.executeBundle(bundle);
    } catch (Exception e) {
      logger.error("FHIR batch execution failed", e);
      for (LocationBatchEntry entry : batch) {
        sseHelper.emitError(
            writer, "An unexpected error occurred processing this row", entry.getRowNumber());
      }
      return new int[] {0, batch.size()};
    }

    int successes = 0;
    int failures = 0;
    List<String> aliasUpdateIds = new ArrayList<>();
    List<LocationBatchEntry> aliasUpdateEntries = new ArrayList<>();
    List<Bundle.BundleEntryComponent> responseEntries = responseBundle.getEntry();
    for (int i = 0; i < batch.size(); i++) {
      LocationBatchEntry entry = batch.get(i);
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
          String sourceId = entry.getLocationData().getSourceId();
          if (sourceId != null) crossSourceIdToFhirId.put(sourceId, resultId);
          crossNameToFhirId.put(entry.getLocationData().getName(), resultId);

          String uuidPath = entry.getLocationData().getUuidPath();
          if (uuidPath != null) {
            String finalPath = uuidPath.replace(entry.getUuid(), resultId);
            crossFhirIdToUuidPath.put(resultId, finalPath);
            if (!finalPath.equals(uuidPath)) {
              // Alias on server still has temp UUID; queue a fix now that we know the real ID.
              entry.getLocationData().setUuidPath(finalPath);
              aliasUpdateIds.add(resultId);
              aliasUpdateEntries.add(entry);
            }
          }
          String namePath = entry.getLocationData().getNamePath();
          if (namePath != null) {
            crossFhirIdToNamePath.put(resultId, namePath);
          }
        }
      } else {
        String errorMsg = extractBatchEntryError(responseEntry);
        logger.error("FHIR batch entry failed at row {}: {}", entry.getRowNumber(), errorMsg);
        sseHelper.emitError(writer, errorMsg, entry.getRowNumber());
        failures++;
      }
    }

    if (!aliasUpdateIds.isEmpty()) {
      Bundle fixBundle = new Bundle();
      fixBundle.setType(Bundle.BundleType.BATCH);
      for (int j = 0; j < aliasUpdateIds.size(); j++) {
        String resultId = aliasUpdateIds.get(j);
        LocationBatchEntry entry = aliasUpdateEntries.get(j);
        Location loc = locationService.buildLocation(entry.getLocationData());
        Bundle.BundleEntryComponent fixEntry = fixBundle.addEntry();
        fixEntry.setResource(loc);
        fixEntry.getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Location/" + resultId);
      }
      try {
        locationService.executeBundle(fixBundle);
      } catch (Exception e) {
        logger.warn(
            "Failed to update UUID path aliases for {} location(s)", aliasUpdateIds.size(), e);
      }
    }

    return new int[] {successes, failures};
  }

  private LocationBatchEntry parseRow(
      String[] columns,
      Map<String, Integer> headerIndex,
      int rowNumber,
      Map<String, String> crossSourceIdToFhirId,
      Map<String, String> crossNameToFhirId,
      Map<String, String> crossFhirIdToNamePath,
      Map<String, String> crossFhirIdToUuidPath,
      Map<String, String> batchSourceIdToUuid,
      Map<String, String> batchNameToUuid) {
    String id = csvProcessor.getColumn(columns, headerIndex, "id");
    String name = csvProcessor.getColumn(columns, headerIndex, "name");
    String sourceId = csvProcessor.getColumn(columns, headerIndex, "source_id");
    String physicalTypeRaw = csvProcessor.getColumn(columns, headerIndex, "physical_type");
    String level = csvProcessor.getColumn(columns, headerIndex, "level");
    String longitudeRaw = csvProcessor.getColumn(columns, headerIndex, "longitude");
    String latitudeRaw = csvProcessor.getColumn(columns, headerIndex, "latitude");
    String parentId = csvProcessor.getColumn(columns, headerIndex, "parent_id");
    String sourceParentId = csvProcessor.getColumn(columns, headerIndex, "source_parent_id");
    String orgId = csvProcessor.getColumn(columns, headerIndex, "org_id");
    String sourceOrgId = csvProcessor.getColumn(columns, headerIndex, "source_org_id");

    if (name == null || name.isBlank()) {
      throw new BulkImportRowException("name is required");
    }

    String parentRef =
        resolveParentLocationReference(
            parentId,
            sourceParentId,
            crossSourceIdToFhirId,
            crossFhirIdToNamePath,
            crossFhirIdToUuidPath,
            batchSourceIdToUuid);

    String managingOrgRef = resolveManagingOrgReference(orgId, sourceOrgId);

    String physicalTypeCode = locationService.resolvePhysicalTypeCode(physicalTypeRaw);
    String physicalTypeDisplay =
        (physicalTypeRaw != null && !physicalTypeRaw.isBlank())
            ? locationService.capitalizeFirst(physicalTypeRaw)
            : null;

    String tempUuid = UUID.randomUUID().toString();
    // For updates with a known FHIR ID, use it directly so the alias is correct from the start.
    String aliasUuidSegment = (id != null && !id.isBlank()) ? id : tempUuid;
    String parentFhirId = extractLocationFhirId(parentRef);
    String[] aliases =
        buildAliases(
            name, aliasUuidSegment, parentFhirId, crossFhirIdToNamePath, crossFhirIdToUuidPath);

    LocationData data = new LocationData();
    data.setName(name);
    data.setSourceId(sourceId);
    data.setPhysicalTypeCode(physicalTypeCode);
    data.setPhysicalTypeDisplay(physicalTypeDisplay);
    data.setLevel(level);
    data.setLongitude(parseDouble(longitudeRaw));
    data.setLatitude(parseDouble(latitudeRaw));
    data.setManagingOrgFhirId(managingOrgRef);
    data.setParentFhirId(parentRef);
    data.setNamePath(aliases[0]);
    data.setUuidPath(aliases[1]);

    return new LocationBatchEntry(tempUuid, data, id, rowNumber);
  }

  private @Nullable String resolveParentLocationReference(
      @Nullable String parentId,
      @Nullable String sourceParentId,
      Map<String, String> crossSourceIdToFhirId,
      Map<String, String> crossFhirIdToNamePath,
      Map<String, String> crossFhirIdToUuidPath,
      Map<String, String> batchSourceIdToUuid) {
    if (parentId != null) {
      Location parentLoc;
      try {
        parentLoc = locationService.getLocation(parentId);
      } catch (Exception e) {
        throw new BulkImportRowException("Parent location not found: " + parentId);
      }
      // Pre-populate path cache so buildAliases can use it without an extra FHIR GET.
      if (!crossFhirIdToNamePath.containsKey(parentId)) {
        String parentName = parentLoc.getName() != null ? parentLoc.getName() : parentId;
        crossFhirIdToNamePath.put(parentId, parentName);
        crossFhirIdToUuidPath.put(parentId, parentId);
      }
      return "Location/" + parentId;
    }

    if (sourceParentId != null) {
      // Parent is in the current accumulating batch — FHIR BATCH bundles do not resolve
      // cross-entry urn:uuid references. Signal the caller to flush first, then retry.
      if (batchSourceIdToUuid.containsKey(sourceParentId)) throw new IntraBatchFlushSignal();
      String cached = crossSourceIdToFhirId.get(sourceParentId);
      if (cached != null) return "Location/" + cached;
      String found =
          locationService.findLocationIdByIdentifier(
              LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, sourceParentId);
      if (found == null) {
        throw new BulkImportRowException(
            "Parent location not found with source_id: " + sourceParentId);
      }
      return "Location/" + found;
    }

    return null;
  }

  private @Nullable String resolveManagingOrgReference(
      @Nullable String orgId, @Nullable String sourceOrgId) {
    if (orgId != null) {
      try {
        organizationService.getOrganization(orgId);
      } catch (Exception e) {
        throw new BulkImportRowException("Managing organization not found: " + orgId);
      }
      return "Organization/" + orgId;
    }

    if (sourceOrgId != null) {
      String found =
          organizationService.findOrganizationIdByIdentifier(
              OrganizationService.SOURCE_ID_IDENTIFIER_SYSTEM, sourceOrgId);
      if (found == null) {
        throw new BulkImportRowException(
            "Managing organization not found with source_id: " + sourceOrgId);
      }
      return "Organization/" + found;
    }

    return null;
  }

  // Returns [namePath, uuidPath] for the current location's alias entries.
  private String[] buildAliases(
      String currentName,
      String currentTempUuid,
      @Nullable String parentFhirId,
      Map<String, String> crossFhirIdToNamePath,
      Map<String, String> crossFhirIdToUuidPath) {
    if (parentFhirId == null) {
      return new String[] {currentName, currentTempUuid};
    }
    String parentNamePath = getOrFetchParentNamePath(parentFhirId, crossFhirIdToNamePath);
    String parentUuidPath = crossFhirIdToUuidPath.getOrDefault(parentFhirId, parentFhirId);
    return new String[] {
      parentNamePath + "/" + currentName, parentUuidPath + "/" + currentTempUuid
    };
  }

  private String getOrFetchParentNamePath(
      String fhirId, Map<String, String> crossFhirIdToNamePath) {
    String cached = crossFhirIdToNamePath.get(fhirId);
    if (cached != null) return cached;
    // Pre-existing location not fetched during this import run: fetch name lazily.
    try {
      Location loc = locationService.getLocation(fhirId);
      String name = loc.getName() != null ? loc.getName() : fhirId;
      crossFhirIdToNamePath.put(fhirId, name);
      return name;
    } catch (Exception e) {
      logger.warn("Could not fetch name for parent location {}", fhirId);
      crossFhirIdToNamePath.put(fhirId, fhirId);
      return fhirId;
    }
  }

  private @Nullable String extractLocationFhirId(@Nullable String locationRef) {
    if (locationRef == null) return null;
    if (locationRef.startsWith("Location/")) return locationRef.substring(9);
    return null;
  }

  private @Nullable Double parseDouble(@Nullable String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private @Nullable String extractFhirId(
      Bundle.BundleEntryComponent responseEntry, @Nullable String knownFhirId) {
    if (knownFhirId != null) return knownFhirId;
    if (responseEntry.getResponse() == null) return null;
    String location = responseEntry.getResponse().getLocation();
    if (location == null || location.isBlank()) return null;
    // Handles: "Location/id", "Location/id/_history/1", "http://server/Location/id/_history/1"
    String[] parts = location.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      if ("Location".equals(parts[i])
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
