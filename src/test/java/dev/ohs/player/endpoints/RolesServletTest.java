package dev.ohs.player.endpoints;

import static org.mockito.Mockito.*;

import dev.ohs.player.auth.AuthenticatedUser;
import dev.ohs.player.auth.AuthorizationHandler;
import dev.ohs.player.iam.AvailableRolesResponse;
import dev.ohs.player.iam.IamProviderException;
import dev.ohs.player.iam.IamProviderService;
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
class RolesServletTest {

  @Mock private IamProviderService iamProviderService;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private RolesServlet servlet;

  @BeforeEach
  void setUp() throws Exception {
    servlet = new RolesServlet(iamProviderService);
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    lenient()
        .when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE))
        .thenReturn(new AuthenticatedUser("user-id", "test-user", Set.of("roles.view")));
  }

  @Test
  void doGet_Success_WritesRolesAndReturns200() throws Exception {
    when(iamProviderService.listAvailableRoles()).thenReturn(new AvailableRolesResponse());

    servlet.doGet(request, response);

    verify(iamProviderService).listAvailableRoles();
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doGet_IamProviderException_ProxiesStatusCode() throws Exception {
    when(iamProviderService.listAvailableRoles())
        .thenThrow(new IamProviderException(503, "Keycloak down"));

    servlet.doGet(request, response);

    verify(response).setStatus(503);
  }

  @Test
  void doGet_GenericException_Returns502() throws Exception {
    when(iamProviderService.listAvailableRoles()).thenThrow(new RuntimeException("unexpected"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  @Test
  void doGet_NoAuthUser_Returns401_NeverCallsDownstream() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE)).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verifyNoInteractions(iamProviderService);
  }
}
