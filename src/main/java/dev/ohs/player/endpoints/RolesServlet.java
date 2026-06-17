package dev.ohs.player.endpoints;

import dev.ohs.player.auth.AuthorizationHandler;
import dev.ohs.player.auth.RoleLevel;
import dev.ohs.player.iam.AvailableRolesResponse;
import dev.ohs.player.iam.IamProviderService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class RolesServlet extends HttpServlet {

  private static final Logger logger = LoggerFactory.getLogger(RolesServlet.class);

  private final IamProviderService iamProviderService;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!AuthorizationHandler.require(request, response, "roles", RoleLevel.VIEW)) return;
    try {
      AvailableRolesResponse roles = iamProviderService.listAvailableRoles();
      ServletResponseUtil.writeJsonResponse(response, HttpServletResponse.SC_OK, roles);
    } catch (Exception e) {
      logger.error("Failed to list available roles from IAM provider", e);
      ServletResponseUtil.writeJsonError(
          response,
          ServletResponseUtil.iamErrorStatus(e),
          "Failed to list available roles from IAM provider");
    }
  }
}
