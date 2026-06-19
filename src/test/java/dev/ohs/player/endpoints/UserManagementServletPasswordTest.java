package dev.ohs.player.endpoints;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import dev.ohs.player.auth.AuthenticatedUser;
import dev.ohs.player.auth.AuthorizationHandler;
import dev.ohs.player.fhir.PractitionerService;
import dev.ohs.player.iam.IamProviderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Set;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserManagementServletPasswordTest {

  @Mock private IamProviderService iamProviderService;
  @Mock private PractitionerService practitionerService;
  @Mock private FhirContext fhirContext;
  @Mock private IParser fhirParser;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private UserManagementServlet servlet;
  private StringWriter stringWriter;

  private static final String PRACTITIONER_ID = "practitioner-123";
  private static final String IAM_USER_ID = "keycloak-uuid-456";
  private static final String PASSWORD_PATH = "/" + PRACTITIONER_ID + "/password";

  @BeforeEach
  void setUp() throws Exception {
    servlet = new UserManagementServlet(iamProviderService, practitionerService, fhirContext);
    stringWriter = new StringWriter();
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));
    lenient().when(fhirContext.newJsonParser()).thenReturn(fhirParser);
    lenient()
        .when(fhirParser.encodeResourceToString(any()))
        .thenReturn("{\"resourceType\":\"Practitioner\"}");
    lenient()
        .when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE))
        .thenReturn(new AuthenticatedUser("user-id", "test-user", Set.of("users.manage")));
  }

  // -------------------------------------------------------------------------
  // PUT /api/users/{id}/password — happy paths
  // -------------------------------------------------------------------------

  @Test
  void doPut_PasswordPath_ValidRequest_Returns204() throws Exception {
    givenPasswordPath();
    givenRequestBody("{\"password\":\"secret123\",\"temporary\":false}");
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);

    servlet.doPut(request, response);

    verify(iamProviderService).resetPassword(IAM_USER_ID, "secret123", false);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  void doPut_PasswordPath_TemporaryFalseByDefault_WhenOmittedInBody() throws Exception {
    givenPasswordPath();
    givenRequestBody("{\"password\":\"secret123\"}");
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);

    servlet.doPut(request, response);

    verify(iamProviderService).resetPassword(IAM_USER_ID, "secret123", false);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  void doPut_PasswordPath_TemporaryTrue_WhenExplicitlySet() throws Exception {
    givenPasswordPath();
    givenRequestBody("{\"password\":\"secret123\",\"temporary\":true}");
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);

    servlet.doPut(request, response);

    verify(iamProviderService).resetPassword(IAM_USER_ID, "secret123", true);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  // -------------------------------------------------------------------------
  // PUT /api/users/{id}/password — validation errors
  // -------------------------------------------------------------------------

  @Test
  void doPut_PasswordPath_MissingPasswordField_Returns400() throws Exception {
    givenPasswordPath();
    givenRequestBody("{\"temporary\":false}");

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerService, iamProviderService);
  }

  @Test
  void doPut_PasswordPath_BlankPassword_Returns400() throws Exception {
    givenPasswordPath();
    givenRequestBody("{\"password\":\"   \"}");

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerService, iamProviderService);
  }

  @Test
  void doPut_PasswordPath_MalformedBody_Returns400() throws Exception {
    givenPasswordPath();
    givenRequestBody("not-json{{{");

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerService, iamProviderService);
  }

  // -------------------------------------------------------------------------
  // PUT /api/users/{id}/password — downstream errors
  // -------------------------------------------------------------------------

  @Test
  void doPut_PasswordPath_PractitionerNotFound_Proxies404() throws Exception {
    givenPasswordPath();
    givenRequestBody("{\"password\":\"secret123\"}");
    when(practitionerService.getPractitioner(PRACTITIONER_ID))
        .thenThrow(new ResourceNotFoundException("not found"));

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void doPut_PasswordPath_FhirServerUnavailable_Returns502() throws Exception {
    givenPasswordPath();
    givenRequestBody("{\"password\":\"secret123\"}");
    when(practitionerService.getPractitioner(PRACTITIONER_ID))
        .thenThrow(new RuntimeException("connection refused"));

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void doPut_PasswordPath_PractitionerHasNoIamId_Returns422() throws Exception {
    givenPasswordPath();
    givenRequestBody("{\"password\":\"secret123\"}");
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(null);

    servlet.doPut(request, response);

    verify(response).setStatus(422);
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void doPut_PasswordPath_IamResetFails_Returns502() throws Exception {
    givenPasswordPath();
    givenRequestBody("{\"password\":\"secret123\"}");
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);
    doThrow(new RuntimeException("Keycloak error"))
        .when(iamProviderService)
        .resetPassword(eq(IAM_USER_ID), eq("secret123"), eq(false));

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  // -------------------------------------------------------------------------
  // PUT /api/users/{id}/password — routing edge cases
  // -------------------------------------------------------------------------

  @Test
  void doPut_SingleSegmentPath_RoutesToExistingUpdateFlow() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    givenRequestBody(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"firstName\":\"John\",\"lastName\":\"Doe\",\"enabled\":true}");
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);
    when(practitionerService.updatePractitioner(eq(PRACTITIONER_ID), eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());

    servlet.doPut(request, response);

    verify(iamProviderService).updateUser(eq(IAM_USER_ID), any());
    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(iamProviderService, never()).resetPassword(any(), any(), anyBoolean());
  }

  @Test
  void doPut_TwoSegmentPathWithNonPasswordSuffix_Returns400() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID + "/roles");

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerService, iamProviderService);
  }

  @Test
  void doPut_BlankIdSegmentWithPasswordSuffix_Returns400() throws Exception {
    when(request.getPathInfo()).thenReturn("//password");

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerService, iamProviderService);
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private void givenPasswordPath() {
    when(request.getPathInfo()).thenReturn(PASSWORD_PATH);
  }

  private void givenRequestBody(String json) throws Exception {
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
  }
}
