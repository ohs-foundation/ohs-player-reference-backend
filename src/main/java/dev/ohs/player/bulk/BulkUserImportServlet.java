package dev.ohs.player.bulk;

import dev.ohs.player.endpoints.ServletResponseUtil;
import dev.ohs.player.fhir.PractitionerService;
import dev.ohs.player.iam.IamGroupRepresentation;
import dev.ohs.player.iam.IamProviderService;
import dev.ohs.player.iam.IamUser;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.r4.model.Practitioner;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class BulkUserImportServlet extends HttpServlet {

  private static final Logger logger = LoggerFactory.getLogger(BulkUserImportServlet.class);

  private final IamProviderService iamProviderService;
  private final PractitionerService practitionerService;
  private final CsvProcessor csvProcessor;
  private final SseResponseHelper sseHelper;

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
    Map<String, String> groupNameToId = buildGroupNameMap();

    sseHelper.configure(response);
    PrintWriter writer = response.getWriter();

    int processed = 0;
    try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
      String headerLine = reader.readLine();
      if (headerLine == null) return;

      Map<String, Integer> headerIndex = csvProcessor.buildHeaderIndex(headerLine);

      String line;
      int rowNumber = 0;
      while ((line = reader.readLine()) != null) {
        rowNumber++;
        if (line.isBlank()) continue;

        try {
          processRow(line.split(",", -1), headerIndex, groupNameToId);
          processed++;
          sseHelper.emitProgress(writer, processed, total);
        } catch (BulkImportRowException e) {
          logger.error("Bulk import failed at row {}", rowNumber, e);
          sseHelper.emitError(writer, e.getClientMessage(), rowNumber);
          return;
        } catch (Exception e) {
          logger.error("Bulk import failed at row {}", rowNumber, e);
          sseHelper.emitError(
              writer, "An unexpected error occurred processing this row", rowNumber);
          return;
        }
      }
    }
  }

  private void processRow(
      String[] columns, Map<String, Integer> headerIndex, Map<String, String> groupNameToId) {
    String id = csvProcessor.getColumn(columns, headerIndex, "id");
    String username = csvProcessor.getColumn(columns, headerIndex, "username");
    String groupName = csvProcessor.getColumn(columns, headerIndex, "group");
    String password = csvProcessor.getColumn(columns, headerIndex, "password");
    String isPasswordTempRaw = csvProcessor.getColumn(columns, headerIndex, "is_password_temp");
    String sourceId = csvProcessor.getColumn(columns, headerIndex, "source_id");

    boolean isPasswordTemp =
        "true".equalsIgnoreCase(isPasswordTempRaw) || "1".equals(isPasswordTempRaw);
    String effectivePassword =
        (password != null && !password.isBlank()) ? password : username + "123";

    IamUser user = new IamUser();
    user.setUsername(username);
    user.setFirstName(csvProcessor.getColumn(columns, headerIndex, "first_name"));
    user.setLastName(csvProcessor.getColumn(columns, headerIndex, "last_name"));
    user.setEmail(csvProcessor.getColumn(columns, headerIndex, "email"));
    user.setEnabled(true);
    user.setDob(csvProcessor.getColumn(columns, headerIndex, "dob"));
    user.setGender(csvProcessor.getColumn(columns, headerIndex, "gender"));
    user.setNationalId(csvProcessor.getColumn(columns, headerIndex, "national_id"));
    user.setPhone(csvProcessor.getColumn(columns, headerIndex, "phone"));
    user.setSourceId(sourceId);

    String resolvedGroupId = resolveGroup(groupName, groupNameToId);

    String practitionerId = id;
    if (practitionerId == null && sourceId != null) {
      practitionerId =
          practitionerService.findPractitionerIdByIdentifier(
              PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM, sourceId);
    }

    if (practitionerId != null) {
      performUpdate(practitionerId, user, resolvedGroupId, effectivePassword, isPasswordTemp);
    } else {
      performCreate(user, resolvedGroupId, effectivePassword, isPasswordTemp);
    }
  }

  /** Returns the resolved group ID, or null if no group was specified. Throws on unknown name. */
  private @Nullable String resolveGroup(
      @Nullable String groupName, Map<String, String> groupNameToId) {
    if (groupName == null) return null;
    String groupId = groupNameToId.get(groupName);
    if (groupId == null) {
      throw new BulkImportRowException("Group not found: " + groupName);
    }
    return groupId;
  }

  private void performCreate(
      IamUser user, @Nullable String resolvedGroupId, String password, boolean isPasswordTemp) {
    String iamUserId = iamProviderService.createUser(user);
    try {
      practitionerService.createPractitioner(iamUserId, user);
    } catch (Exception e) {
      rollbackIamUser(iamUserId);
      throw e;
    }

    if (resolvedGroupId != null) {
      try {
        iamProviderService.addUserToGroup(iamUserId, resolvedGroupId);
      } catch (Exception e) {
        logger.warn(
            "Failed to add user {} to group {}: {}", iamUserId, resolvedGroupId, e.getMessage());
      }
    }

    iamProviderService.resetPassword(iamUserId, password, isPasswordTemp);
  }

  private void performUpdate(
      String practitionerId,
      IamUser user,
      @Nullable String resolvedGroupId,
      String password,
      boolean isPasswordTemp) {
    Practitioner existing = practitionerService.getPractitioner(practitionerId);
    String iamUserId = practitionerService.extractIamUserId(existing);
    if (iamUserId == null) {
      throw new BulkImportRowException("Practitioner " + practitionerId + " has no IAM identifier");
    }

    iamProviderService.updateUser(iamUserId, user);
    practitionerService.updatePractitioner(practitionerId, iamUserId, user);

    if (resolvedGroupId != null) {
      syncSingleGroup(iamUserId, resolvedGroupId);
    }

    iamProviderService.resetPassword(iamUserId, password, isPasswordTemp);
  }

  /** Adds the user to the given group if not already a member. Warns on transient error. */
  private void syncSingleGroup(String iamUserId, String groupId) {
    Set<String> current;
    try {
      current =
          iamProviderService.getUserGroups(iamUserId).stream()
              .map(IamGroupRepresentation::getId)
              .collect(Collectors.toSet());
    } catch (Exception e) {
      logger.warn("Failed to fetch groups for user {}: {}", iamUserId, e.getMessage());
      return;
    }
    if (!current.contains(groupId)) {
      try {
        iamProviderService.addUserToGroup(iamUserId, groupId);
      } catch (Exception e) {
        logger.warn("Failed to add user {} to group {}: {}", iamUserId, groupId, e.getMessage());
      }
    }
  }

  private void rollbackIamUser(String iamUserId) {
    try {
      iamProviderService.deleteUser(iamUserId);
      logger.info("Rolled back IAM user: {}", iamUserId);
    } catch (Exception e) {
      logger.error("Failed to rollback IAM user: {}; manual cleanup required", iamUserId, e);
    }
  }

  private Map<String, String> buildGroupNameMap() {
    try {
      return iamProviderService.listGroups().stream()
          .collect(
              Collectors.toMap(
                  IamGroupRepresentation::getName, IamGroupRepresentation::getId, (a, b) -> a));
    } catch (Exception e) {
      logger.warn("Failed to preload group list: {}", e.getMessage());
      return new HashMap<>();
    }
  }
}
