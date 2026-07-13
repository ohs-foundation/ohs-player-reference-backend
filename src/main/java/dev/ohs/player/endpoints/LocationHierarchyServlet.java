package dev.ohs.player.endpoints;

import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import dev.ohs.player.auth.AuthorizationHandler;
import dev.ohs.player.auth.RoleLevel;
import dev.ohs.player.fhir.LocationHierarchy;
import dev.ohs.player.fhir.LocationHierarchyService;
import dev.ohs.player.fhir.LocationHierarchyUpstreamException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class LocationHierarchyServlet extends HttpServlet {

  private static final Logger logger = LoggerFactory.getLogger(LocationHierarchyServlet.class);
  private static final Pattern FHIR_ID_PATTERN = Pattern.compile("^[A-Za-z0-9\\-.]{1,64}$");
  private static final String AUTH_RESOURCE = "location-hierarchy";

  private final LocationHierarchyService locationHierarchyService;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!AuthorizationHandler.require(request, response, AUTH_RESOURCE, RoleLevel.VIEW)) return;

    String rootId = extractRootId(request.getPathInfo());
    if (rootId == null) {
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "A valid Location root id is required in the request path");
      return;
    }

    LocationHierarchy hierarchy;
    try {
      hierarchy = locationHierarchyService.getLocationHierarchy(rootId);
    } catch (ResourceNotFoundException e) {
      // Only the root read surfaces this; child-search misses come through as
      // LocationHierarchyUpstreamException.
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_NOT_FOUND, "Location root not found: " + rootId);
      return;
    } catch (LocationHierarchyUpstreamException e) {
      logger.error("Upstream FHIR failure building Location hierarchy for root id: {}", rootId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          "Failed to build Location hierarchy from FHIR server");
      return;
    } catch (Exception e) {
      logger.error("Unexpected error building Location hierarchy for root id: {}", rootId, e);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Unexpected error building Location hierarchy");
      return;
    }

    if (hierarchy == null) {
      // The service contract requires throwing ResourceNotFoundException for a missing root, so a
      // null result is an unexpected internal fault rather than a 404.
      logger.error("Location hierarchy service returned null for root id: {}", rootId);
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Unexpected error building Location hierarchy");
      return;
    }
    ServletResponseUtil.writeJsonSuccess(response, hierarchy);
  }

  private @Nullable String extractRootId(String pathInfo) {
    if (pathInfo == null || pathInfo.length() < 2 || pathInfo.charAt(0) != '/') return null;

    String rootId = pathInfo.substring(1);
    if (rootId.indexOf('/') >= 0 || rootId.equals(".") || rootId.equals("..")) return null;

    return FHIR_ID_PATTERN.matcher(rootId).matches() ? rootId : null;
  }
}
