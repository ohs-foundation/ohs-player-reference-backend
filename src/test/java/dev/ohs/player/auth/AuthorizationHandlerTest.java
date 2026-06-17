package dev.ohs.player.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthorizationHandlerTest {

  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  @BeforeEach
  void setUp() throws Exception {
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
  }

  // --- hasRole hierarchy tests ---

  @Test
  void hasRole_ViewRequired_ViewRoleSatisfies() {
    AuthenticatedUser user = user("users.view");
    assertTrue(AuthorizationHandler.hasRole(user, "users", RoleLevel.VIEW));
  }

  @Test
  void hasRole_ViewRequired_EditRoleSatisfies() {
    AuthenticatedUser user = user("users.edit");
    assertTrue(AuthorizationHandler.hasRole(user, "users", RoleLevel.VIEW));
  }

  @Test
  void hasRole_ViewRequired_ManageRoleSatisfies() {
    AuthenticatedUser user = user("users.manage");
    assertTrue(AuthorizationHandler.hasRole(user, "users", RoleLevel.VIEW));
  }

  @Test
  void hasRole_EditRequired_EditRoleSatisfies() {
    AuthenticatedUser user = user("users.edit");
    assertTrue(AuthorizationHandler.hasRole(user, "users", RoleLevel.EDIT));
  }

  @Test
  void hasRole_EditRequired_ManageRoleSatisfies() {
    AuthenticatedUser user = user("users.manage");
    assertTrue(AuthorizationHandler.hasRole(user, "users", RoleLevel.EDIT));
  }

  @Test
  void hasRole_EditRequired_ViewRoleInsufficient() {
    AuthenticatedUser user = user("users.view");
    assertFalse(AuthorizationHandler.hasRole(user, "users", RoleLevel.EDIT));
  }

  @Test
  void hasRole_ManageRequired_ManageRoleSatisfies() {
    AuthenticatedUser user = user("users.manage");
    assertTrue(AuthorizationHandler.hasRole(user, "users", RoleLevel.MANAGE));
  }

  @Test
  void hasRole_ManageRequired_EditRoleInsufficient() {
    AuthenticatedUser user = user("users.edit");
    assertFalse(AuthorizationHandler.hasRole(user, "users", RoleLevel.MANAGE));
  }

  @Test
  void hasRole_ManageRequired_ViewRoleInsufficient() {
    AuthenticatedUser user = user("users.view");
    assertFalse(AuthorizationHandler.hasRole(user, "users", RoleLevel.MANAGE));
  }

  @Test
  void hasRole_WrongResource_ReturnsFalse() {
    AuthenticatedUser user = user("groups.manage");
    assertFalse(AuthorizationHandler.hasRole(user, "users", RoleLevel.VIEW));
  }

  @Test
  void hasRole_EmptyRoles_ReturnsFalse() {
    AuthenticatedUser user = new AuthenticatedUser("id", "user", Set.of());
    assertFalse(AuthorizationHandler.hasRole(user, "users", RoleLevel.VIEW));
  }

  // --- require() tests ---

  @Test
  void require_NullUser_Returns401() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE)).thenReturn(null);

    boolean result = AuthorizationHandler.require(request, response, "users", RoleLevel.VIEW);

    assertFalse(result);
    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  void require_InsufficientRole_Returns403() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE))
        .thenReturn(user("users.view"));

    boolean result = AuthorizationHandler.require(request, response, "users", RoleLevel.MANAGE);

    assertFalse(result);
    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  void require_SufficientRole_ReturnsTrue() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE))
        .thenReturn(user("users.manage"));

    boolean result = AuthorizationHandler.require(request, response, "users", RoleLevel.VIEW);

    assertTrue(result);
    verify(response, never()).setStatus(anyInt());
  }

  private static AuthenticatedUser user(String... roles) {
    return new AuthenticatedUser("sub", "user", Set.of(roles));
  }
}
