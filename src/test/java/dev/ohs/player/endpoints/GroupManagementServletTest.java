package dev.ohs.player.endpoints;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.ohs.player.iam.IamGroupRepresentation;
import dev.ohs.player.iam.IamProviderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupManagementServletTest {

  @Mock private IamProviderService iamProviderService;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private GroupManagementServlet servlet;
  private StringWriter stringWriter;

  private static final String GROUP_ID = "group-uuid-123";
  private static final String USER_ID = "user-uuid-456";
  private static final String VALID_GROUP_JSON = "{\"name\":\"clinicians\"}";
  private static final String VALID_GROUP_WITH_ROLES_JSON =
      "{\"name\":\"clinicians\",\"realmRoles\":[\"offline_access\"]}";

  @BeforeEach
  void setUp() throws Exception {
    servlet = new GroupManagementServlet(iamProviderService);
    stringWriter = new StringWriter();
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));
  }

  private void givenRequestBody(String json) throws Exception {
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
  }

  private IamGroupRepresentation sampleGroup() {
    IamGroupRepresentation g = new IamGroupRepresentation();
    g.setId(GROUP_ID);
    g.setName("clinicians");
    g.setPath("/clinicians");
    return g;
  }

  // -------------------------------------------------------------------------
  // GET /api/groups  (list)
  // -------------------------------------------------------------------------

  @Test
  void doGet_NoPath_ListsGroups_Returns200() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    when(iamProviderService.listGroups()).thenReturn(List.of(sampleGroup()));

    servlet.doGet(request, response);

    verify(iamProviderService).listGroups();
    verify(response).setStatus(HttpServletResponse.SC_OK);
    assertTrue(stringWriter.toString().contains("clinicians"));
  }

  @Test
  void doGet_RootPath_ListsGroups_Returns200() throws Exception {
    when(request.getPathInfo()).thenReturn("/");
    when(iamProviderService.listGroups()).thenReturn(Collections.emptyList());

    servlet.doGet(request, response);

    verify(iamProviderService).listGroups();
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doGet_ListGroups_IamFailure_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    when(iamProviderService.listGroups()).thenThrow(new RuntimeException("connection refused"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  // -------------------------------------------------------------------------
  // GET /api/groups/{id}
  // -------------------------------------------------------------------------

  @Test
  void doGet_WithId_ReturnsGroup_Returns200() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID);
    when(iamProviderService.getGroup(GROUP_ID)).thenReturn(sampleGroup());

    servlet.doGet(request, response);

    verify(iamProviderService).getGroup(GROUP_ID);
    verify(response).setStatus(HttpServletResponse.SC_OK);
    assertTrue(stringWriter.toString().contains(GROUP_ID));
  }

  @Test
  void doGet_WithId_IamFailure_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID);
    when(iamProviderService.getGroup(GROUP_ID)).thenThrow(new RuntimeException("upstream error"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  @Test
  void doGet_InvalidPath_Returns400() throws Exception {
    when(request.getPathInfo()).thenReturn("/group/extra/segments");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verify(iamProviderService, never()).getGroup(any());
  }

  // -------------------------------------------------------------------------
  // POST /api/groups  (create)
  // -------------------------------------------------------------------------

  @Test
  void doPost_ValidRequest_CreatesGroup_Returns201() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    givenRequestBody(VALID_GROUP_JSON);
    when(iamProviderService.createGroup(any())).thenReturn(sampleGroup());

    servlet.doPost(request, response);

    verify(iamProviderService).createGroup(any());
    verify(response).setStatus(HttpServletResponse.SC_CREATED);
    assertTrue(stringWriter.toString().contains(GROUP_ID));
  }

  @Test
  void doPost_MissingName_Returns400_NoDownstreamCalls() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    givenRequestBody("{\"realmRoles\":[\"admin\"]}");

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(stringWriter.toString().contains("name is required"));
    verify(iamProviderService, never()).createGroup(any());
  }

  @Test
  void doPost_MalformedBody_Returns400() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    givenRequestBody("not-valid{json");

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verify(iamProviderService, never()).createGroup(any());
  }

  @Test
  void doPost_IamFailure_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    givenRequestBody(VALID_GROUP_JSON);
    when(iamProviderService.createGroup(any())).thenThrow(new RuntimeException("keycloak down"));

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  @Test
  void doPost_WithRoles_PassesRolesToService() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    givenRequestBody(VALID_GROUP_WITH_ROLES_JSON);
    when(iamProviderService.createGroup(any())).thenReturn(sampleGroup());

    servlet.doPost(request, response);

    verify(iamProviderService)
        .createGroup(
            argThat(
                g ->
                    "clinicians".equals(g.getName())
                        && g.getRealmRoles() != null
                        && g.getRealmRoles().contains("offline_access")));
    verify(response).setStatus(HttpServletResponse.SC_CREATED);
  }

  // -------------------------------------------------------------------------
  // POST /api/groups/{groupId}/members/{userId}  (add member)
  // -------------------------------------------------------------------------

  @Test
  void doPost_AddMember_Returns204() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID + "/members/" + USER_ID);
    doNothing().when(iamProviderService).addUserToGroup(USER_ID, GROUP_ID);

    servlet.doPost(request, response);

    verify(iamProviderService).addUserToGroup(USER_ID, GROUP_ID);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  void doPost_AddMember_IamFailure_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID + "/members/" + USER_ID);
    doThrow(new RuntimeException("user not found"))
        .when(iamProviderService)
        .addUserToGroup(USER_ID, GROUP_ID);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  // -------------------------------------------------------------------------
  // PUT /api/groups/{id}  (update)
  // -------------------------------------------------------------------------

  @Test
  void doPut_ValidRequest_UpdatesGroup_Returns200() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID);
    givenRequestBody(VALID_GROUP_JSON);
    doNothing().when(iamProviderService).updateGroup(eq(GROUP_ID), any());
    when(iamProviderService.getGroup(GROUP_ID)).thenReturn(sampleGroup());

    servlet.doPut(request, response);

    verify(iamProviderService).updateGroup(eq(GROUP_ID), any());
    verify(iamProviderService).getGroup(GROUP_ID);
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doPut_MissingId_Returns400() throws Exception {
    when(request.getPathInfo()).thenReturn(null);

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verify(iamProviderService, never()).updateGroup(any(), any());
  }

  @Test
  void doPut_MissingName_Returns400() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID);
    givenRequestBody("{}");

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verify(iamProviderService, never()).updateGroup(any(), any());
  }

  @Test
  void doPut_IamUpdateFailure_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID);
    givenRequestBody(VALID_GROUP_JSON);
    doThrow(new RuntimeException("keycloak error"))
        .when(iamProviderService)
        .updateGroup(any(), any());

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  @Test
  void doPut_IamGetFailureAfterUpdate_Returns500() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID);
    givenRequestBody(VALID_GROUP_JSON);
    doNothing().when(iamProviderService).updateGroup(eq(GROUP_ID), any());
    when(iamProviderService.getGroup(GROUP_ID)).thenThrow(new RuntimeException("read error"));

    servlet.doPut(request, response);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  // -------------------------------------------------------------------------
  // DELETE /api/groups/{id}
  // -------------------------------------------------------------------------

  @Test
  void doDelete_ValidId_DeletesGroup_Returns204() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID);
    doNothing().when(iamProviderService).deleteGroup(GROUP_ID);

    servlet.doDelete(request, response);

    verify(iamProviderService).deleteGroup(GROUP_ID);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  void doDelete_MissingId_Returns400() throws Exception {
    when(request.getPathInfo()).thenReturn(null);

    servlet.doDelete(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verify(iamProviderService, never()).deleteGroup(any());
  }

  @Test
  void doDelete_IamFailure_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID);
    doThrow(new RuntimeException("keycloak down")).when(iamProviderService).deleteGroup(GROUP_ID);

    servlet.doDelete(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  // -------------------------------------------------------------------------
  // DELETE /api/groups/{groupId}/members/{userId}  (remove member)
  // -------------------------------------------------------------------------

  @Test
  void doDelete_RemoveMember_Returns204() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID + "/members/" + USER_ID);
    doNothing().when(iamProviderService).removeUserFromGroup(USER_ID, GROUP_ID);

    servlet.doDelete(request, response);

    verify(iamProviderService).removeUserFromGroup(USER_ID, GROUP_ID);
    verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  @Test
  void doDelete_RemoveMember_IamFailure_Returns502() throws Exception {
    when(request.getPathInfo()).thenReturn("/" + GROUP_ID + "/members/" + USER_ID);
    doThrow(new RuntimeException("user not found"))
        .when(iamProviderService)
        .removeUserFromGroup(USER_ID, GROUP_ID);

    servlet.doDelete(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }
}
