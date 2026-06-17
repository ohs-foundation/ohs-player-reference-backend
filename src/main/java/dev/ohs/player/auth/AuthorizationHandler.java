package dev.ohs.player.auth;

import dev.ohs.player.endpoints.ServletResponseUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

public final class AuthorizationHandler {

  public static final String AUTH_USER_ATTRIBUTE = "authenticatedUser";

  private AuthorizationHandler() {}

  public static AuthenticatedUser getUser(HttpServletRequest request) {
    return (AuthenticatedUser) request.getAttribute(AUTH_USER_ATTRIBUTE);
  }

  /**
   * Returns true if the user holds a role that satisfies {@code requiredLevel} for the resource.
   */
  public static boolean hasRole(AuthenticatedUser user, String resource, RoleLevel requiredLevel) {
    Set<String> roles = user.getRoles();
    switch (requiredLevel) {
      case VIEW:
        return roles.contains(resource + ".view")
            || roles.contains(resource + ".edit")
            || roles.contains(resource + ".manage");
      case EDIT:
        return roles.contains(resource + ".edit") || roles.contains(resource + ".manage");
      case MANAGE:
        return roles.contains(resource + ".manage");
      default:
        return false;
    }
  }

  /**
   * Enforces the required role for the request. Writes a 401 or 403 response and returns {@code
   * false} when the check fails, so callers can early-return.
   */
  public static boolean require(
      HttpServletRequest request,
      HttpServletResponse response,
      String resource,
      RoleLevel requiredLevel)
      throws IOException {
    AuthenticatedUser user = getUser(request);
    if (user == null) {
      ServletResponseUtil.writeJsonError(
          response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthenticated request");
      return false;
    }
    if (!hasRole(user, resource, requiredLevel)) {
      ServletResponseUtil.writeJsonError(
          response,
          HttpServletResponse.SC_FORBIDDEN,
          "Insufficient permissions. Required: "
              + resource
              + "."
              + requiredLevel.name().toLowerCase());
      return false;
    }
    return true;
  }
}
