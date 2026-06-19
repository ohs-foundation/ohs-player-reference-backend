package dev.ohs.player.endpoints;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import dev.ohs.player.auth.AuthenticatedUser;
import dev.ohs.player.auth.AuthorizationHandler;
import dev.ohs.player.fhir.PractitionerService;
import dev.ohs.player.iam.IamGroupRepresentation;
import dev.ohs.player.iam.IamProviderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserManagementServletTest {

  @Mock private IamProviderService iamProviderService;
  @Mock private PractitionerService practitionerService;
  @Mock private FhirContext fhirContext;
  @Mock private IParser fhirParser;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private UserManagementServlet servlet;
  private StringWriter stringWriter;

  private static final String VALID_USER_JSON =
      "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"firstName\":\"John\",\"lastName\":\"Doe\",\"enabled\":true}";
  private static final String PRACTITIONER_ID = "practitioner-123";
  private static final String IAM_USER_ID = "keycloak-uuid-456";

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
  // POST /api/users
  // -------------------------------------------------------------------------

  @Test
  void doPost_ValidRequest_CreatesIamUserAndPractitioner_Returns201() throws Exception {
    givenRequestBody(VALID_USER_JSON);
    when(iamProviderService.createUser(any())).thenReturn(IAM_USER_ID);
    when(practitionerService.createPractitioner(eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).createUser(any());
    verify(practitionerService).createPractitioner(eq(IAM_USER_ID), any());
    verify(response).setStatus(HttpServletResponse.SC_CREATED);
    verify(response).setContentType(ServletResponseUtil.CONTENT_TYPE_FHIR_JSON);
  }

  @Test
  void doPost_MalformedBody_Returns400_NoDownstreamCalls() throws Exception {
    givenRequestBody("not-valid-json{{{");

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(iamProviderService, practitionerService);
  }

  @Test
  void doPost_MissingUsername_Returns400() throws Exception {
    givenRequestBody("{\"email\":\"jdoe@example.com\"}");

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(stringWriter.toString().contains("username"));
    verifyNoInteractions(iamProviderService, practitionerService);
  }

  @Test
  void doPost_BlankUsername_Returns400() throws Exception {
    givenRequestBody("{\"username\":\"  \",\"email\":\"jdoe@example.com\"}");

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(stringWriter.toString().contains("username"));
  }

  @Test
  void doPost_MissingEmail_Returns400() throws Exception {
    givenRequestBody("{\"username\":\"jdoe\"}");

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(stringWriter.toString().contains("email"));
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void doPost_IamProviderFails_Returns502_NoFhirCall() throws Exception {
    givenRequestBody(VALID_USER_JSON);
    when(iamProviderService.createUser(any()))
        .thenThrow(new RuntimeException("Keycloak unavailable"));

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
    verifyNoInteractions(practitionerService);
  }

  @Test
  void doPost_PractitionerCreationFails_RollsBackIamUser_Returns500() throws Exception {
    givenRequestBody(VALID_USER_JSON);
    when(iamProviderService.createUser(any())).thenReturn(IAM_USER_ID);
    when(practitionerService.createPractitioner(any(), any()))
        .thenThrow(new RuntimeException("FHIR down"));

    servlet.doPost(request, response);

    verify(iamProviderService).deleteUser(IAM_USER_ID);
    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  @Test
  void doPost_PractitionerCreationFails_RollbackAlsoFails_StillReturns500() throws Exception {
    givenRequestBody(VALID_USER_JSON);
    when(iamProviderService.createUser(any())).thenReturn(IAM_USER_ID);
    when(practitionerService.createPractitioner(any(), any()))
        .thenThrow(new RuntimeException("FHIR down"));
    doThrow(new RuntimeException("Rollback failed"))
        .when(iamProviderService)
        .deleteUser(IAM_USER_ID);

    servlet.doPost(request, response);

    verify(iamProviderService).deleteUser(IAM_USER_ID);
    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  // -------------------------------------------------------------------------
  // GET /api/users — list / search
  // -------------------------------------------------------------------------

  @Test
  void doGet_NoPathAndNoParams_CallsSearchWithEmptyMap_Returns200() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    when(request.getParameterMap()).thenReturn(Collections.emptyMap());
    when(practitionerService.searchPractitioners(Collections.emptyMap())).thenReturn(new Bundle());

    servlet.doGet(request, response);

    verify(practitionerService).searchPractitioners(Collections.emptyMap());
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doGet_RootSlashPath_TreatsAsSearch() throws Exception {
    when(request.getPathInfo()).thenReturn("/");
    when(request.getParameterMap()).thenReturn(Collections.emptyMap());
    when(practitionerService.searchPractitioners(any())).thenReturn(new Bundle());

    servlet.doGet(request, response);

    verify(practitionerService).searchPractitioners(any());
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doGet_WithQueryParams_ForwardsAllParamsToFhir() throws Exception {
    Map<String, String[]> params = new HashMap<>();
    params.put("name", new String[] {"John"});
    params.put("_sort", new String[] {"family"});
    params.put("_count", new String[] {"10"});
    when(request.getPathInfo()).thenReturn(null);
    when(request.getParameterMap()).thenReturn(params);
    when(practitionerService.searchPractitioners(params)).thenReturn(new Bundle());

    servlet.doGet(request, response);

    verify(practitionerService).searchPractitioners(params);
    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response).setContentType(ServletResponseUtil.CONTENT_TYPE_FHIR_JSON);
  }

  @Test
  void doGet_FhirServerUnavailableForSearch_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    when(request.getParameterMap()).thenReturn(Collections.emptyMap());
    when(practitionerService.searchPractitioners(any()))
        .thenThrow(new RuntimeException("FHIR down"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  // -------------------------------------------------------------------------
  // GET /api/users/{id}
  // -------------------------------------------------------------------------

  @Test
  void doGet_WithPractitionerId_ReturnsPractitioner_Returns200() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(new Practitioner());

    servlet.doGet(request, response);

    verify(practitionerService).getPractitioner(PRACTITIONER_ID);
    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response).setContentType(ServletResponseUtil.CONTENT_TYPE_FHIR_JSON);
  }

  @Test
  void doGet_PractitionerNotFound_ProxiesFhirStatusCode() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    when(practitionerService.getPractitioner(PRACTITIONER_ID))
        .thenThrow(new ResourceNotFoundException("Not found"));

    servlet.doGet(request, response);

    verify(response).setStatus(404);
  }

  @Test
  void doGet_FhirServerUnavailableForGet_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    when(practitionerService.getPractitioner(PRACTITIONER_ID))
        .thenThrow(new RuntimeException("FHIR down"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  @Test
  void doGet_NestedPath_Returns400_NoPractitionerLookup() throws Exception {
    when(request.getPathInfo()).thenReturn("/a/b");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerService);
  }

  // -------------------------------------------------------------------------
  // PUT /api/users/{id}
  // -------------------------------------------------------------------------

  @Test
  void doPut_NoPathInfo_Returns400() throws Exception {
    when(request.getPathInfo()).thenReturn(null);

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerService, iamProviderService);
  }

  @Test
  void doPut_ValidRequest_UpdatesBothIamAndPractitioner_Returns200() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    givenRequestBody(VALID_USER_JSON);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);
    when(practitionerService.updatePractitioner(eq(PRACTITIONER_ID), eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());

    servlet.doPut(request, response);

    verify(iamProviderService).updateUser(eq(IAM_USER_ID), any());
    verify(practitionerService).updatePractitioner(eq(PRACTITIONER_ID), eq(IAM_USER_ID), any());
    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response).setContentType(ServletResponseUtil.CONTENT_TYPE_FHIR_JSON);
  }

  @Test
  void doPut_PractitionerNotFound_ProxiesFhirStatusCode() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    givenRequestBody(VALID_USER_JSON);
    when(practitionerService.getPractitioner(PRACTITIONER_ID))
        .thenThrow(new ResourceNotFoundException("Not found"));

    servlet.doPut(request, response);

    verify(response).setStatus(404);
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void doPut_FhirServerUnavailable_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    givenRequestBody(VALID_USER_JSON);
    when(practitionerService.getPractitioner(PRACTITIONER_ID))
        .thenThrow(new RuntimeException("FHIR down"));

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void doPut_PractitionerHasNoIamIdentifier_Returns422() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    givenRequestBody(VALID_USER_JSON);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(null);

    servlet.doPut(request, response);

    verify(response).setStatus(422);
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void doPut_IamUpdateFails_Returns502_NoPractitionerUpdate() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    givenRequestBody(VALID_USER_JSON);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);
    doThrow(new RuntimeException("IAM error")).when(iamProviderService).updateUser(any(), any());

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
    verify(practitionerService, never()).updatePractitioner(any(), any(), any());
  }

  @Test
  void doPut_PractitionerUpdateFailsAfterIamUpdate_Returns500() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    givenRequestBody(VALID_USER_JSON);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);
    when(practitionerService.updatePractitioner(any(), any(), any()))
        .thenThrow(new RuntimeException("FHIR write failed"));

    servlet.doPut(request, response);

    verify(iamProviderService).updateUser(eq(IAM_USER_ID), any());
    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  // -------------------------------------------------------------------------
  // DELETE /api/users/{id}
  // -------------------------------------------------------------------------

  @Test
  void doDelete_NoPathInfo_Returns400() throws Exception {
    when(request.getPathInfo()).thenReturn(null);

    servlet.doDelete(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerService, iamProviderService);
  }

  @Test
  void doDelete_ValidRequest_DeletesBothIamAndPractitioner_Returns204() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);

    servlet.doDelete(request, response);

    verify(iamProviderService).deleteUser(IAM_USER_ID);
    verify(practitionerService).deletePractitioner(PRACTITIONER_ID);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  void doDelete_PractitionerNotFound_ProxiesFhirStatusCode() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    when(practitionerService.getPractitioner(PRACTITIONER_ID))
        .thenThrow(new ResourceNotFoundException("Not found"));

    servlet.doDelete(request, response);

    verify(response).setStatus(404);
    verifyNoInteractions(iamProviderService);
  }

  @Test
  void doDelete_IamDeleteFails_ContinuesToDeletePractitioner_Returns204() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);
    doThrow(new RuntimeException("IAM unavailable"))
        .when(iamProviderService)
        .deleteUser(IAM_USER_ID);

    servlet.doDelete(request, response);

    verify(practitionerService).deletePractitioner(PRACTITIONER_ID);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  void doDelete_PractitionerHasNoIamIdentifier_SkipsIamDelete_Returns204() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(null);

    servlet.doDelete(request, response);

    verifyNoInteractions(iamProviderService);
    verify(practitionerService).deletePractitioner(PRACTITIONER_ID);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  void doDelete_PractitionerDeleteFails_Returns500() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);
    doThrow(new RuntimeException("FHIR delete failed"))
        .when(practitionerService)
        .deletePractitioner(PRACTITIONER_ID);

    servlet.doDelete(request, response);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  // -------------------------------------------------------------------------
  // assignUserGroups — exercised via doPost
  // -------------------------------------------------------------------------

  @Test
  void doPost_WithNullGroupIds_SkipsGroupAssignment() throws Exception {
    givenRequestBody(VALID_USER_JSON);
    when(iamProviderService.createUser(any())).thenReturn(IAM_USER_ID);
    when(practitionerService.createPractitioner(eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService, never()).addUserToGroup(any(), any());
  }

  @Test
  void doPost_WithEmptyGroupIds_SkipsGroupAssignment() throws Exception {
    givenRequestBody("{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[]}");
    when(iamProviderService.createUser(any())).thenReturn(IAM_USER_ID);
    when(practitionerService.createPractitioner(eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService, never()).addUserToGroup(any(), any());
    verify(response).setStatus(HttpServletResponse.SC_CREATED);
  }

  @Test
  void doPost_WithSingleGroupId_CallsAddUserToGroupOnce() throws Exception {
    givenRequestBody(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\"]}");
    when(iamProviderService.createUser(any())).thenReturn(IAM_USER_ID);
    when(practitionerService.createPractitioner(eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-a");
    verify(response).setStatus(HttpServletResponse.SC_CREATED);
  }

  @Test
  void doPost_WithMultipleGroupIds_CallsAddUserToGroupForEach() throws Exception {
    givenRequestBody(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\",\"group-b\",\"group-c\"]}");
    when(iamProviderService.createUser(any())).thenReturn(IAM_USER_ID);
    when(practitionerService.createPractitioner(eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-a");
    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-b");
    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-c");
    verify(response).setStatus(HttpServletResponse.SC_CREATED);
  }

  @Test
  void doPost_WithGroupIds_OneAddThrows_SwallowsAndContinues() throws Exception {
    givenRequestBody(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\",\"group-b\"]}");
    when(iamProviderService.createUser(any())).thenReturn(IAM_USER_ID);
    when(practitionerService.createPractitioner(eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());
    doThrow(new RuntimeException("IAM error"))
        .when(iamProviderService)
        .addUserToGroup(IAM_USER_ID, "group-a");

    servlet.doPost(request, response);

    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-a");
    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-b");
    verify(response).setStatus(HttpServletResponse.SC_CREATED);
  }

  @Test
  void doPost_WithGroupIds_AllAddsFail_SwallowsAllExceptions() throws Exception {
    givenRequestBody(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\",\"group-b\"]}");
    when(iamProviderService.createUser(any())).thenReturn(IAM_USER_ID);
    when(practitionerService.createPractitioner(eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());
    doThrow(new RuntimeException("IAM error"))
        .when(iamProviderService)
        .addUserToGroup(any(), any());

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_CREATED);
  }

  // -------------------------------------------------------------------------
  // syncUserGroups — exercised via doPut
  // -------------------------------------------------------------------------

  @Test
  void doPut_WithNullGroupIds_SkipsGroupSync() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    givenRequestBody(VALID_USER_JSON);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);
    when(practitionerService.updatePractitioner(eq(PRACTITIONER_ID), eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());

    servlet.doPut(request, response);

    verify(iamProviderService, never()).getUserGroups(any());
    verify(iamProviderService, never()).addUserToGroup(any(), any());
    verify(iamProviderService, never()).removeUserFromGroup(any(), any());
  }

  @Test
  void doPut_WithGroupIds_GetUserGroupsThrows_SkipsGroupSync() throws Exception {
    givenPutWithGroups(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\"]}");
    when(iamProviderService.getUserGroups(IAM_USER_ID))
        .thenThrow(new RuntimeException("IAM unavailable"));

    servlet.doPut(request, response);

    verify(iamProviderService, never()).addUserToGroup(any(), any());
    verify(iamProviderService, never()).removeUserFromGroup(any(), any());
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doPut_WithGroupIds_AllAlreadyPresent_NoAddOrRemove() throws Exception {
    givenPutWithGroups(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\",\"group-b\"]}");
    when(iamProviderService.getUserGroups(IAM_USER_ID))
        .thenReturn(List.of(groupRep("group-a"), groupRep("group-b")));

    servlet.doPut(request, response);

    verify(iamProviderService, never()).addUserToGroup(any(), any());
    verify(iamProviderService, never()).removeUserFromGroup(any(), any());
  }

  @Test
  void doPut_WithGroupIds_CurrentEmpty_AddsAllDesiredGroups() throws Exception {
    givenPutWithGroups(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\",\"group-b\"]}");
    when(iamProviderService.getUserGroups(IAM_USER_ID)).thenReturn(List.of());

    servlet.doPut(request, response);

    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-a");
    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-b");
    verify(iamProviderService, never()).removeUserFromGroup(any(), any());
  }

  @Test
  void doPut_WithEmptyGroupIds_RemovesAllCurrentGroups() throws Exception {
    givenPutWithGroups("{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[]}");
    when(iamProviderService.getUserGroups(IAM_USER_ID))
        .thenReturn(List.of(groupRep("group-a"), groupRep("group-b")));

    servlet.doPut(request, response);

    verify(iamProviderService).removeUserFromGroup(IAM_USER_ID, "group-a");
    verify(iamProviderService).removeUserFromGroup(IAM_USER_ID, "group-b");
    verify(iamProviderService, never()).addUserToGroup(any(), any());
  }

  @Test
  void doPut_WithGroupIds_OnlyAddsGroupsNotAlreadyPresent() throws Exception {
    givenPutWithGroups(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\",\"group-b\"]}");
    when(iamProviderService.getUserGroups(IAM_USER_ID)).thenReturn(List.of(groupRep("group-a")));

    servlet.doPut(request, response);

    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-b");
    verify(iamProviderService, never()).addUserToGroup(IAM_USER_ID, "group-a");
    verify(iamProviderService, never()).removeUserFromGroup(any(), any());
  }

  @Test
  void doPut_WithGroupIds_OnlyRemovesGroupsNoLongerDesired() throws Exception {
    givenPutWithGroups(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\"]}");
    when(iamProviderService.getUserGroups(IAM_USER_ID))
        .thenReturn(List.of(groupRep("group-a"), groupRep("group-b")));

    servlet.doPut(request, response);

    verify(iamProviderService).removeUserFromGroup(IAM_USER_ID, "group-b");
    verify(iamProviderService, never()).removeUserFromGroup(IAM_USER_ID, "group-a");
    verify(iamProviderService, never()).addUserToGroup(any(), any());
  }

  @Test
  void doPut_WithGroupIds_MixedAddAndRemove() throws Exception {
    givenPutWithGroups(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-b\",\"group-c\"]}");
    when(iamProviderService.getUserGroups(IAM_USER_ID))
        .thenReturn(List.of(groupRep("group-a"), groupRep("group-b")));

    servlet.doPut(request, response);

    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-c");
    verify(iamProviderService).removeUserFromGroup(IAM_USER_ID, "group-a");
    verify(iamProviderService, never()).addUserToGroup(IAM_USER_ID, "group-b");
    verify(iamProviderService, never()).removeUserFromGroup(IAM_USER_ID, "group-b");
  }

  @Test
  void doPut_WithGroupIds_AddThrowsForOneGroup_SwallowsAndContinues() throws Exception {
    givenPutWithGroups(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\",\"group-b\"]}");
    when(iamProviderService.getUserGroups(IAM_USER_ID)).thenReturn(List.of());
    doThrow(new RuntimeException("IAM error"))
        .when(iamProviderService)
        .addUserToGroup(IAM_USER_ID, "group-a");

    servlet.doPut(request, response);

    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-a");
    verify(iamProviderService).addUserToGroup(IAM_USER_ID, "group-b");
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doPut_WithGroupIds_RemoveThrowsForOneGroup_SwallowsAndContinues() throws Exception {
    givenPutWithGroups(
        "{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groupIds\":[\"group-a\"]}");
    when(iamProviderService.getUserGroups(IAM_USER_ID))
        .thenReturn(List.of(groupRep("group-b"), groupRep("group-c")));
    doThrow(new RuntimeException("IAM error"))
        .when(iamProviderService)
        .removeUserFromGroup(IAM_USER_ID, "group-b");

    servlet.doPut(request, response);

    verify(iamProviderService).removeUserFromGroup(IAM_USER_ID, "group-b");
    verify(iamProviderService).removeUserFromGroup(IAM_USER_ID, "group-c");
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private void givenRequestBody(String json) throws Exception {
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
  }

  private void givenPutWithGroups(String json) throws Exception {
    when(request.getPathInfo()).thenReturn("/" + PRACTITIONER_ID);
    givenRequestBody(json);
    Practitioner existing = new Practitioner();
    when(practitionerService.getPractitioner(PRACTITIONER_ID)).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn(IAM_USER_ID);
    when(practitionerService.updatePractitioner(eq(PRACTITIONER_ID), eq(IAM_USER_ID), any()))
        .thenReturn(new Practitioner());
  }

  private IamGroupRepresentation groupRep(String id) {
    IamGroupRepresentation rep = new IamGroupRepresentation();
    rep.setId(id);
    return rep;
  }

  // -------------------------------------------------------------------------
  // Auth enforcement
  // -------------------------------------------------------------------------

  @Test
  void doGet_NoAuthUser_Returns401_NeverCallsDownstream() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE)).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verifyNoInteractions(iamProviderService, practitionerService);
  }

  @Test
  void doPost_NoAuthUser_Returns401_NeverCallsDownstream() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE)).thenReturn(null);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verifyNoInteractions(iamProviderService, practitionerService);
  }

  @Test
  void doPut_NoAuthUser_Returns401_NeverCallsDownstream() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE)).thenReturn(null);

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verifyNoInteractions(iamProviderService, practitionerService);
  }

  @Test
  void doDelete_NoAuthUser_Returns401_NeverCallsDownstream() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE)).thenReturn(null);

    servlet.doDelete(request, response);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verifyNoInteractions(iamProviderService, practitionerService);
  }

  @Test
  void doDelete_EditRoleOnly_Returns403_NeverCallsDownstream() throws Exception {
    when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE))
        .thenReturn(new AuthenticatedUser("id", "user", Set.of("users.edit")));

    servlet.doDelete(request, response);

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    verifyNoInteractions(iamProviderService, practitionerService);
  }
}
