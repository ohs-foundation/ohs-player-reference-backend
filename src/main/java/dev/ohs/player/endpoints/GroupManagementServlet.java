package dev.ohs.player.endpoints;

import dev.ohs.player.iam.IamGroup;
import dev.ohs.player.iam.IamGroupRepresentation;
import dev.ohs.player.iam.IamProviderService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class GroupManagementServlet extends HttpServlet {

  private static final Logger logger = LoggerFactory.getLogger(GroupManagementServlet.class);

  private final IamProviderService iamProviderService;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String pathInfo = request.getPathInfo();

    if (pathInfo == null || pathInfo.equals("/")) {
      handleListGroups(response);
      return;
    }

    String groupId = extractGroupId(pathInfo);
    if (groupId == null) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "Invalid resource path");
      return;
    }

    handleGetGroup(groupId, response);
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String pathInfo = request.getPathInfo();

    String[] memberPath = extractMemberPath(pathInfo);
    if (memberPath != null) {
      handleAddMember(memberPath[0], memberPath[1], response);
      return;
    }

    if (pathInfo != null && !pathInfo.equals("/")) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "Invalid resource path");
      return;
    }

    handleCreateGroup(request, response);
  }

  @Override
  protected void doPut(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String pathInfo = request.getPathInfo();
    String groupId = extractGroupId(pathInfo);
    if (groupId == null) {
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "Group ID is required in path: /api/groups/{group-id}");
      return;
    }

    IamGroup group = parseRequestBody(request, response);
    if (group == null) return;
    if (!validateRequiredFields(group, response)) return;

    try {
      iamProviderService.updateGroup(groupId, group);
    } catch (Exception e) {
      logger.error("Failed to update group in IAM provider: id={}", groupId, e);
      ServletResponseUtil.writeJsonError(
          response,
          ServletResponseUtil.iamErrorStatus(e),
          "Failed to update group in IAM provider: " + e.getMessage());
      return;
    }

    IamGroupRepresentation updated;
    try {
      updated = iamProviderService.getGroup(groupId);
    } catch (Exception e) {
      logger.error("Failed to retrieve group after update: id={}", groupId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Group updated but failed to retrieve updated representation");
      return;
    }

    ServletResponseUtil.writeJsonResponse(response, HttpServletResponse.SC_OK, updated);
  }

  @Override
  protected void doDelete(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String pathInfo = request.getPathInfo();

    String[] memberPath = extractMemberPath(pathInfo);
    if (memberPath != null) {
      handleRemoveMember(memberPath[0], memberPath[1], response);
      return;
    }

    String groupId = extractGroupId(pathInfo);
    if (groupId == null) {
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "Group ID is required in path: /api/groups/{group-id}");
      return;
    }

    try {
      iamProviderService.deleteGroup(groupId);
    } catch (Exception e) {
      logger.error("Failed to delete group in IAM provider: id={}", groupId, e);
      ServletResponseUtil.writeJsonError(
          response,
          ServletResponseUtil.iamErrorStatus(e),
          "Failed to delete group in IAM provider: " + e.getMessage());
      return;
    }

    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private void handleListGroups(HttpServletResponse response) throws IOException {
    try {
      List<IamGroupRepresentation> groups = iamProviderService.listGroups();
      ServletResponseUtil.writeJsonResponse(response, HttpServletResponse.SC_OK, groups);
    } catch (Exception e) {
      logger.error("Failed to list groups from IAM provider", e);
      ServletResponseUtil.writeJsonError(
          response,
          ServletResponseUtil.iamErrorStatus(e),
          "Failed to list groups from IAM provider");
    }
  }

  private void handleGetGroup(String groupId, HttpServletResponse response) throws IOException {
    try {
      IamGroupRepresentation group = iamProviderService.getGroup(groupId);
      ServletResponseUtil.writeJsonResponse(response, HttpServletResponse.SC_OK, group);
    } catch (Exception e) {
      logger.error("Failed to get group from IAM provider: id={}", groupId, e);
      ServletResponseUtil.writeJsonError(
          response,
          ServletResponseUtil.iamErrorStatus(e),
          "Failed to get group from IAM provider: " + e.getMessage());
    }
  }

  private void handleCreateGroup(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    IamGroup group = parseRequestBody(request, response);
    if (group == null) return;
    if (!validateRequiredFields(group, response)) return;

    IamGroupRepresentation created;
    try {
      created = iamProviderService.createGroup(group);
    } catch (Exception e) {
      logger.error("Failed to create group in IAM provider", e);
      ServletResponseUtil.writeJsonError(
          response,
          ServletResponseUtil.iamErrorStatus(e),
          "Failed to create group in IAM provider: " + e.getMessage());
      return;
    }

    ServletResponseUtil.writeJsonResponse(response, HttpServletResponse.SC_CREATED, created);
  }

  private void handleAddMember(String groupId, String userId, HttpServletResponse response)
      throws IOException {
    try {
      iamProviderService.addUserToGroup(userId, groupId);
      logger.info("Added user {} to group {}", userId, groupId);
    } catch (Exception e) {
      logger.error("Failed to add user {} to group {}", userId, groupId, e);
      ServletResponseUtil.writeJsonError(
          response,
          ServletResponseUtil.iamErrorStatus(e),
          "Failed to add user to group: " + e.getMessage());
      return;
    }

    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private void handleRemoveMember(String groupId, String userId, HttpServletResponse response)
      throws IOException {
    try {
      iamProviderService.removeUserFromGroup(userId, groupId);
      logger.info("Removed user {} from group {}", userId, groupId);
    } catch (Exception e) {
      logger.error("Failed to remove user {} from group {}", userId, groupId, e);
      ServletResponseUtil.writeJsonError(
          response,
          ServletResponseUtil.iamErrorStatus(e),
          "Failed to remove user from group: " + e.getMessage());
      return;
    }

    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private IamGroup parseRequestBody(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      return ServletResponseUtil.getObjectMapper().readValue(request.getReader(), IamGroup.class);
    } catch (Exception e) {
      logger.debug("Failed to parse request body", e);
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "Invalid or malformed request body");
      return null;
    }
  }

  private boolean validateRequiredFields(IamGroup group, HttpServletResponse response)
      throws IOException {
    if (group.getName() == null || group.getName().isBlank()) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "name is required");
      return false;
    }
    return true;
  }

  /** Extracts a single-segment group ID from a path like {@code /{id}}. */
  private String extractGroupId(String pathInfo) {
    if (pathInfo == null || pathInfo.equals("/")) return null;
    String id = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    if (id.isBlank() || id.contains("/")) return null;
    return id;
  }

  /**
   * Extracts {@code [groupId, userId]} from a path like {@code /{groupId}/members/{userId}}.
   * Returns null if the path does not match that pattern.
   */
  private String[] extractMemberPath(String pathInfo) {
    if (pathInfo == null) return null;
    String[] parts = pathInfo.split("/", -1);
    if (parts.length == 4
        && parts[0].isEmpty()
        && !parts[1].isBlank()
        && "members".equals(parts[2])
        && !parts[3].isBlank()) {
      return new String[] {parts[1], parts[3]};
    }
    return null;
  }
}
