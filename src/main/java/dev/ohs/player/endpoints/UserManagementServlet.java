package dev.ohs.player.endpoints;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import dev.ohs.player.fhir.PractitionerService;
import dev.ohs.player.iam.IamProviderService;
import dev.ohs.player.iam.IamUser;
import dev.ohs.player.iam.PasswordUpdate;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Practitioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class UserManagementServlet extends HttpServlet {

  private static final Logger logger = LoggerFactory.getLogger(UserManagementServlet.class);
  private static final int SC_UNPROCESSABLE_ENTITY = 422;

  private final IamProviderService iamProviderService;
  private final PractitionerService practitionerService;
  private final FhirContext fhirContext;

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    IamUser user = parseRequestBody(request, response);
    if (user == null) return;
    if (!validateRequiredFields(user, response)) return;

    String iamUserId;
    try {
      iamUserId = iamProviderService.createUser(user);
    } catch (Exception e) {
      logger.error("Failed to create user in IAM provider", e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to create user in IAM provider: " + e.getMessage());
      return;
    }

    Practitioner practitioner;
    try {
      practitioner = practitionerService.createPractitioner(iamUserId, user);
    } catch (Exception e) {
      logger.error("Failed to create Practitioner; rolling back IAM user: {}", iamUserId, e);
      rollbackIamUser(iamUserId);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to create Practitioner resource; IAM user creation was rolled back");
      return;
    }

    String fhirJson = fhirContext.newJsonParser().encodeResourceToString(practitioner);
    ServletResponseUtil.writeResponse(
        response,
        HttpServletResponse.SC_CREATED,
        ServletResponseUtil.CONTENT_TYPE_FHIR_JSON,
        fhirJson);
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String pathInfo = request.getPathInfo();

    if (pathInfo == null || pathInfo.equals("/")) {
      handleSearch(request.getParameterMap(), response);
    } else {
      String practitionerId = extractIdFromPath(pathInfo);
      if (practitionerId == null) {
        ServletResponseUtil.writeJsonError(
            response, HttpServletResponse.SC_BAD_REQUEST, "Invalid resource path");
        return;
      }
      handleGetById(practitionerId, response);
    }
  }

  @Override
  protected void doPut(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String pathInfo = request.getPathInfo();

    if (isPasswordResetPath(pathInfo)) {
      handlePasswordReset(extractIdFromPasswordPath(pathInfo), request, response);
      return;
    }

    String practitionerId = extractIdFromPath(pathInfo);
    if (practitionerId == null) {
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "Practitioner ID is required in path: /api/users/{practitioner-id}");
      return;
    }

    IamUser user = parseRequestBody(request, response);
    if (user == null) return;
    if (!validateRequiredFields(user, response)) return;

    Practitioner existing;
    try {
      existing = practitionerService.getPractitioner(practitionerId);
    } catch (BaseServerResponseException e) {
      logger.warn("Practitioner not found for update: id={}", practitionerId);
      ServletResponseUtil.writeJsonError(
          response, e.getStatusCode(), "Practitioner not found: " + practitionerId);
      return;
    } catch (Exception e) {
      logger.error("Failed to retrieve Practitioner for update: id={}", practitionerId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to retrieve Practitioner from FHIR server");
      return;
    }

    String iamUserId = practitionerService.extractIamUserId(existing);
    if (iamUserId == null) {
      ServletResponseUtil.writeJsonError(
          response, SC_UNPROCESSABLE_ENTITY, "Practitioner has no IAM provider identifier");
      return;
    }

    try {
      iamProviderService.updateUser(iamUserId, user);
    } catch (Exception e) {
      logger.error("Failed to update user in IAM provider: id={}", iamUserId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to update user in IAM provider: " + e.getMessage());
      return;
    }

    Practitioner updated;
    try {
      updated = practitionerService.updatePractitioner(practitionerId, iamUserId, user);
    } catch (Exception e) {
      logger.error("Failed to update Practitioner: id={}", practitionerId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "IAM provider updated but failed to update Practitioner resource");
      return;
    }

    String fhirJson = fhirContext.newJsonParser().encodeResourceToString(updated);
    ServletResponseUtil.writeResponse(
        response, HttpServletResponse.SC_OK, ServletResponseUtil.CONTENT_TYPE_FHIR_JSON, fhirJson);
  }

  @Override
  protected void doDelete(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String practitionerId = extractIdFromPath(request.getPathInfo());
    if (practitionerId == null) {
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "Practitioner ID is required in path: /api/users/{practitioner-id}");
      return;
    }

    Practitioner existing;
    try {
      existing = practitionerService.getPractitioner(practitionerId);
    } catch (BaseServerResponseException e) {
      logger.warn("Practitioner not found for delete: id={}", practitionerId);
      ServletResponseUtil.writeJsonError(
          response, e.getStatusCode(), "Practitioner not found: " + practitionerId);
      return;
    } catch (Exception e) {
      logger.error("Failed to retrieve Practitioner for delete: id={}", practitionerId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to retrieve Practitioner from FHIR server");
      return;
    }

    String iamUserId = practitionerService.extractIamUserId(existing);
    if (iamUserId != null) {
      try {
        iamProviderService.deleteUser(iamUserId);
      } catch (Exception e) {
        logger.warn(
            "Failed to delete IAM user: {}; continuing with Practitioner deletion", iamUserId, e);
      }
    } else {
      logger.warn(
          "Practitioner has no IAM provider identifier, skipping IAM deletion: practitionerId={}",
          practitionerId);
    }

    try {
      practitionerService.deletePractitioner(practitionerId);
    } catch (Exception e) {
      logger.error("Failed to delete Practitioner: id={}", practitionerId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to delete Practitioner resource");
      return;
    }

    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private void handleSearch(Map<String, String[]> params, HttpServletResponse response)
      throws IOException {
    try {
      Bundle bundle = practitionerService.searchPractitioners(params);
      String fhirJson = fhirContext.newJsonParser().encodeResourceToString(bundle);
      ServletResponseUtil.writeResponse(
          response,
          HttpServletResponse.SC_OK,
          ServletResponseUtil.CONTENT_TYPE_FHIR_JSON,
          fhirJson);
    } catch (Exception e) {
      logger.error("Failed to search Practitioners", e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to retrieve Practitioners from FHIR server");
    }
  }

  private void handleGetById(String practitionerId, HttpServletResponse response)
      throws IOException {
    try {
      Practitioner practitioner = practitionerService.getPractitioner(practitionerId);
      String fhirJson = fhirContext.newJsonParser().encodeResourceToString(practitioner);
      ServletResponseUtil.writeResponse(
          response,
          HttpServletResponse.SC_OK,
          ServletResponseUtil.CONTENT_TYPE_FHIR_JSON,
          fhirJson);
    } catch (BaseServerResponseException e) {
      logger.warn("Practitioner not found: id={}", practitionerId);
      ServletResponseUtil.writeJsonError(
          response, e.getStatusCode(), "Practitioner not found: " + practitionerId);
    } catch (Exception e) {
      logger.error("Failed to retrieve Practitioner: id={}", practitionerId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to retrieve Practitioner from FHIR server");
    }
  }

  private IamUser parseRequestBody(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      return ServletResponseUtil.getObjectMapper().readValue(request.getReader(), IamUser.class);
    } catch (Exception e) {
      logger.debug("Failed to parse request body", e);
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "Invalid or malformed request body");
      return null;
    }
  }

  private boolean validateRequiredFields(IamUser user, HttpServletResponse response)
      throws IOException {
    if (user.getUsername() == null || user.getUsername().isBlank()) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "username is required");
      return false;
    }
    if (user.getEmail() == null || user.getEmail().isBlank()) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "email is required");
      return false;
    }
    return true;
  }

  private String extractIdFromPath(String pathInfo) {
    if (pathInfo == null || pathInfo.equals("/")) return null;
    String id = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    if (id.isBlank() || id.contains("/")) return null;
    return id;
  }

  private boolean isPasswordResetPath(String pathInfo) {
    if (pathInfo == null) return false;
    String[] parts = pathInfo.split("/", -1);
    return parts.length == 3 && !parts[1].isBlank() && "password".equals(parts[2]);
  }

  private String extractIdFromPasswordPath(String pathInfo) {
    return pathInfo.split("/", -1)[1];
  }

  private void handlePasswordReset(
      String practitionerId, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    PasswordUpdate passwordUpdate;
    try {
      passwordUpdate =
          ServletResponseUtil.getObjectMapper()
              .readValue(request.getReader(), PasswordUpdate.class);
    } catch (Exception e) {
      logger.debug("Failed to parse password reset request body", e);
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "Invalid or malformed request body");
      return;
    }

    if (passwordUpdate.getPassword() == null || passwordUpdate.getPassword().isBlank()) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_BAD_REQUEST, "password is required");
      return;
    }

    Practitioner existing;
    try {
      existing = practitionerService.getPractitioner(practitionerId);
    } catch (BaseServerResponseException e) {
      logger.warn("Practitioner not found for password reset: id={}", practitionerId);
      ServletResponseUtil.writeJsonError(
          response, e.getStatusCode(), "Practitioner not found: " + practitionerId);
      return;
    } catch (Exception e) {
      logger.error("Failed to retrieve Practitioner for password reset: id={}", practitionerId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to retrieve Practitioner from FHIR server");
      return;
    }

    String iamUserId = practitionerService.extractIamUserId(existing);
    if (iamUserId == null) {
      ServletResponseUtil.writeJsonError(
          response, SC_UNPROCESSABLE_ENTITY, "Practitioner has no IAM provider identifier");
      return;
    }

    try {
      iamProviderService.resetPassword(
          iamUserId, passwordUpdate.getPassword(), passwordUpdate.isTemporary());
    } catch (Exception e) {
      logger.error("Failed to reset password for IAM user: id={}", iamUserId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to reset password in IAM provider: " + e.getMessage());
      return;
    }

    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private void rollbackIamUser(String iamUserId) {
    try {
      iamProviderService.deleteUser(iamUserId);
      logger.info("Successfully rolled back IAM user: {}", iamUserId);
    } catch (Exception e) {
      logger.error("Failed to rollback IAM user: {}; manual cleanup required", iamUserId, e);
    }
  }
}
