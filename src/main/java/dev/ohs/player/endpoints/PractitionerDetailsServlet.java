package dev.ohs.player.endpoints;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ohs.player.auth.AuthorizationHandler;
import dev.ohs.player.auth.RoleLevel;
import dev.ohs.player.fhir.PractitionerDetail;
import dev.ohs.player.fhir.PractitionerDetailService;
import dev.ohs.player.fhir.PractitionerRoleDetail;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Location;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class PractitionerDetailsServlet extends HttpServlet {

  private static final Logger logger = LoggerFactory.getLogger(PractitionerDetailsServlet.class);

  private final PractitionerDetailService practitionerDetailService;
  private final FhirContext fhirContext;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!AuthorizationHandler.require(request, response, "practitioner-details", RoleLevel.VIEW))
      return;
    String iamId = request.getParameter("iam-id");
    String practitionerId = request.getParameter("practitioner-id");
    String organisationId = request.getParameter("organisation-id");
    String locationId = request.getParameter("location-id");

    boolean hasIamId = iamId != null && !iamId.isBlank();
    boolean hasPractitionerId = practitionerId != null && !practitionerId.isBlank();

    if (!hasIamId && !hasPractitionerId) {
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "Either 'iam-id' or 'practitioner-id' query parameter is required");
      return;
    }

    if (hasIamId) {
      String resolved = resolveFromIamId(iamId, response);
      if (resolved == null) return;
      practitionerId = resolved;
    }

    PractitionerDetail detail = fetchDetail(practitionerId, organisationId, locationId, response);
    if (detail == null) return;

    String json;
    try {
      json = serializeDetail(detail);
    } catch (Exception e) {
      logger.error("Failed to serialize practitioner detail for id: {}", practitionerId, e);
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to serialize response");
      return;
    }

    ServletResponseUtil.writeResponse(
        response, HttpServletResponse.SC_OK, ServletResponseUtil.CONTENT_TYPE_JSON, json);
  }

  private @Nullable String resolveFromIamId(String iamId, HttpServletResponse response)
      throws IOException {
    String resolved;
    try {
      resolved = practitionerDetailService.resolvePractitionerIdFromIamId(iamId);
    } catch (Exception e) {
      logger.error("Failed to resolve practitioner from iam-id: {}", iamId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to resolve practitioner from FHIR server");
      return null;
    }
    if (resolved == null) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_NOT_FOUND, "No practitioner found for iam-id: " + iamId);
      return null;
    }
    return resolved;
  }

  private @Nullable PractitionerDetail fetchDetail(
      String practitionerId,
      @Nullable String organisationId,
      @Nullable String locationId,
      HttpServletResponse response)
      throws IOException {
    PractitionerDetail detail;
    try {
      detail =
          practitionerDetailService.fetchPractitionerDetail(
              practitionerId, organisationId, locationId);
    } catch (Exception e) {
      logger.error("Failed to fetch practitioner details for id: {}", practitionerId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to fetch practitioner details from FHIR server");
      return null;
    }
    if (detail == null) {
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_NOT_FOUND,
          "No practitioner found for id: " + practitionerId);
      return null;
    }
    return detail;
  }

  private String serializeDetail(PractitionerDetail detail) throws IOException {
    IParser fhirParser = fhirContext.newJsonParser();
    ObjectMapper mapper = ServletResponseUtil.getObjectMapper();

    ObjectNode root = mapper.createObjectNode();
    root.set(
        "practitioner",
        mapper.readTree(fhirParser.encodeResourceToString(detail.getPractitioner())));

    ArrayNode rolesArray = mapper.createArrayNode();
    for (PractitionerRoleDetail roleDetail : detail.getPractitionerRoles()) {
      ObjectNode roleNode = mapper.createObjectNode();
      roleNode.set(
          "practitionerRole",
          mapper.readTree(fhirParser.encodeResourceToString(roleDetail.getPractitionerRole())));

      if (roleDetail.getOrganization() != null) {
        roleNode.set(
            "organization",
            mapper.readTree(fhirParser.encodeResourceToString(roleDetail.getOrganization())));
      } else {
        roleNode.putNull("organization");
      }

      ArrayNode locationsArray = mapper.createArrayNode();
      for (Location loc : roleDetail.getLocations()) {
        locationsArray.add(mapper.readTree(fhirParser.encodeResourceToString(loc)));
      }
      roleNode.set("locations", locationsArray);

      ArrayNode careTeamsArray = mapper.createArrayNode();
      for (CareTeam ct : roleDetail.getCareTeams()) {
        careTeamsArray.add(mapper.readTree(fhirParser.encodeResourceToString(ct)));
      }
      roleNode.set("careTeams", careTeamsArray);

      rolesArray.add(roleNode);
    }
    root.set("practitionerRoles", rolesArray);

    return mapper.writeValueAsString(root);
  }
}
