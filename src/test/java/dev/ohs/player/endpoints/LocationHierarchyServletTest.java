package dev.ohs.player.endpoints;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import dev.ohs.player.auth.AuthenticatedUser;
import dev.ohs.player.auth.AuthorizationHandler;
import dev.ohs.player.fhir.LocationHierarchy;
import dev.ohs.player.fhir.LocationHierarchyService;
import dev.ohs.player.fhir.LocationHierarchyUpstreamException;
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
class LocationHierarchyServletTest {

  private static final String ROOT_ID = "3e39604c-57c9-44c4-863b-2e873e4ba613";

  @Mock private LocationHierarchyService locationHierarchyService;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private LocationHierarchyServlet servlet;

  @BeforeEach
  void setUp() throws Exception {
    servlet = new LocationHierarchyServlet(locationHierarchyService);
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    lenient()
        .when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE))
        .thenReturn(
            new AuthenticatedUser("user-id", "test-user", Set.of("location-hierarchy.view")));
  }

  @Test
  void doGet_Unauthenticated_Returns401BeforeReadingPathOrCallingService() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE)).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(request, never()).getPathInfo();
    verifyNoInteractions(locationHierarchyService);
  }

  @Test
  void doGet_InsufficientRole_Returns403BeforeCallingService() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE))
        .thenReturn(new AuthenticatedUser("user-id", "test-user", Set.of("groups.view")));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    verifyNoInteractions(locationHierarchyService);
  }

  @Test
  void doGet_InvalidPath_Returns400BeforeCallingService() throws Exception {
    when(request.getPathInfo()).thenReturn("/../child");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(locationHierarchyService);
  }

  @Test
  void doGet_ValidRoot_ReturnsHierarchy() throws Exception {
    LocationHierarchy hierarchy = new LocationHierarchy();
    when(request.getPathInfo()).thenReturn("/" + ROOT_ID);
    when(locationHierarchyService.getLocationHierarchy(ROOT_ID)).thenReturn(hierarchy);

    servlet.doGet(request, response);

    verify(locationHierarchyService).getLocationHierarchy(ROOT_ID);
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doGet_MissingRoot_Returns404() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + ROOT_ID);
    when(locationHierarchyService.getLocationHierarchy(ROOT_ID))
        .thenThrow(new ResourceNotFoundException("not found"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
  }

  @Test
  void doGet_UpstreamFailure_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + ROOT_ID);
    when(locationHierarchyService.getLocationHierarchy(ROOT_ID))
        .thenThrow(new LocationHierarchyUpstreamException("FHIR unavailable"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  @Test
  void doGet_UnexpectedError_Returns500() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + ROOT_ID);
    when(locationHierarchyService.getLocationHierarchy(ROOT_ID))
        .thenThrow(new RuntimeException("Unexpected Error"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  @Test
  void doGet_ServiceReturnsNull_Returns500() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + ROOT_ID);
    when(locationHierarchyService.getLocationHierarchy(ROOT_ID)).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }
}
