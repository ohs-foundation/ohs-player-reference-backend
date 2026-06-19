package dev.ohs.player.bulk;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.ohs.player.auth.AuthenticatedUser;
import dev.ohs.player.auth.AuthorizationHandler;
import dev.ohs.player.fhir.PractitionerService;
import dev.ohs.player.iam.IamGroupRepresentation;
import dev.ohs.player.iam.IamProviderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BulkUserImportServletTest {

  @Mock private IamProviderService iamProviderService;
  @Mock private PractitionerService practitionerService;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private Part filePart;

  private CsvProcessor csvProcessor;
  private SseResponseHelper sseHelper;
  private BulkUserImportServlet servlet;
  private StringWriter responseBuffer;

  private static final String HEADER =
      "id,username,first_name,last_name,email,group,password,is_password_temp,"
          + "dob,gender,national_id,phone,source_id";

  @BeforeEach
  void setUp() throws Exception {
    csvProcessor = new CsvProcessor();
    sseHelper = new SseResponseHelper();
    servlet =
        new BulkUserImportServlet(iamProviderService, practitionerService, csvProcessor, sseHelper);
    responseBuffer = new StringWriter();
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseBuffer));
    lenient()
        .when(request.getAttribute(AuthorizationHandler.AUTH_USER_ATTRIBUTE))
        .thenReturn(new AuthenticatedUser("user-id", "test-user", Set.of("bulk-import.manage")));
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  @Test
  void doPost_missingFilePart_returns400() throws Exception {
    when(request.getPart("file")).thenReturn(null);

    servlet.doPost(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(iamProviderService, practitionerService);
  }

  // -------------------------------------------------------------------------
  // Create path
  // -------------------------------------------------------------------------

  @Test
  void doPost_singleRowCreate_callsCreateAndEmitsProgress() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any())).thenReturn("iam-id-1");
    when(practitionerService.createPractitioner(any(), any())).thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).createUser(any());
    verify(practitionerService).createPractitioner(eq("iam-id-1"), any());
    verify(iamProviderService).resetPassword(eq("iam-id-1"), eq("jdoe123"), eq(false));
    assertSseContains("\"processed\":1,\"total\":1");
  }

  @Test
  void doPost_noPassword_defaultsToUsernameSuffix() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any())).thenReturn("iam-id-1");
    when(practitionerService.createPractitioner(any(), any())).thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).resetPassword("iam-id-1", "jdoe123", false);
  }

  @Test
  void doPost_withPassword_usesProvidedPassword() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,s3cret!,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any())).thenReturn("iam-id-1");
    when(practitionerService.createPractitioner(any(), any())).thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).resetPassword("iam-id-1", "s3cret!", false);
  }

  @Test
  void doPost_isPasswordTempTrue_passesTemporaryFlag() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,pass,true,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any())).thenReturn("iam-id-1");
    when(practitionerService.createPractitioner(any(), any())).thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).resetPassword("iam-id-1", "pass", true);
  }

  @Test
  void doPost_isPasswordTemp1_passesTemporaryFlag() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,pass,1,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any())).thenReturn("iam-id-1");
    when(practitionerService.createPractitioner(any(), any())).thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).resetPassword("iam-id-1", "pass", true);
  }

  // -------------------------------------------------------------------------
  // Update path — via id
  // -------------------------------------------------------------------------

  @Test
  void doPost_rowWithId_callsUpdatePath() throws Exception {
    givenCsv(HEADER + "\nprac-123,jdoe,John,Doe,jdoe@example.com,,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    Practitioner existing = practitionerWithIam("iam-id-1");
    when(practitionerService.getPractitioner("prac-123")).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn("iam-id-1");
    when(practitionerService.updatePractitioner(any(), any(), any()))
        .thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(practitionerService).getPractitioner("prac-123");
    verify(iamProviderService).updateUser(eq("iam-id-1"), any());
    verify(practitionerService).updatePractitioner(eq("prac-123"), eq("iam-id-1"), any());
    verify(iamProviderService, never()).createUser(any());
  }

  // -------------------------------------------------------------------------
  // Update path — via source_id
  // -------------------------------------------------------------------------

  @Test
  void doPost_rowWithSourceId_resolvesAndUpdates() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,,,,,,,SRC-1");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(practitionerService.findPractitionerIdByIdentifier(
            PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-1"))
        .thenReturn("prac-456");
    Practitioner existing = practitionerWithIam("iam-id-2");
    when(practitionerService.getPractitioner("prac-456")).thenReturn(existing);
    when(practitionerService.extractIamUserId(existing)).thenReturn("iam-id-2");
    when(practitionerService.updatePractitioner(any(), any(), any()))
        .thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(practitionerService)
        .findPractitionerIdByIdentifier(PractitionerService.SOURCE_ID_IDENTIFIER_SYSTEM, "SRC-1");
    verify(iamProviderService).updateUser(eq("iam-id-2"), any());
  }

  @Test
  void doPost_sourceIdNotFoundInFhir_fallsBackToCreate() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,,,,,,,SRC-MISSING");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(practitionerService.findPractitionerIdByIdentifier(any(), any())).thenReturn(null);
    when(iamProviderService.createUser(any())).thenReturn("iam-new");
    when(practitionerService.createPractitioner(any(), any())).thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).createUser(any());
    verify(practitionerService, never()).getPractitioner(any());
  }

  // -------------------------------------------------------------------------
  // Group handling
  // -------------------------------------------------------------------------

  @Test
  void doPost_groupNameResolved_callsAddUserToGroup() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,clinicians,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of(groupRep("group-id-1", "clinicians")));
    when(iamProviderService.createUser(any())).thenReturn("iam-id-1");
    when(practitionerService.createPractitioner(any(), any())).thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService).addUserToGroup("iam-id-1", "group-id-1");
  }

  @Test
  void doPost_unknownGroupName_emitsErrorAndStops() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,nonexistent,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    verify(iamProviderService, never()).createUser(any());
  }

  @Test
  void doPost_addUserToGroupThrows_warnsAndRowSucceeds() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,clinicians,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of(groupRep("group-id-1", "clinicians")));
    when(iamProviderService.createUser(any())).thenReturn("iam-id-1");
    when(practitionerService.createPractitioner(any(), any())).thenReturn(new Practitioner());
    doThrow(new RuntimeException("IAM transient error"))
        .when(iamProviderService)
        .addUserToGroup(any(), any());

    servlet.doPost(request, response);

    assertSseContains("\"processed\":1");
    verify(iamProviderService).resetPassword(any(), any(), anyBoolean());
  }

  // -------------------------------------------------------------------------
  // Fail-fast behaviour
  // -------------------------------------------------------------------------

  @Test
  void doPost_iamCreateFails_rollsBackAndEmitsError() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any())).thenThrow(new RuntimeException("IAM down"));

    servlet.doPost(request, response);

    assertSseContains("\"error\"");
    verify(iamProviderService, never()).resetPassword(any(), any(), anyBoolean());
  }

  @Test
  void doPost_fhirCreateFails_rollsBackIamAndEmitsError() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any())).thenReturn("iam-id-1");
    when(practitionerService.createPractitioner(any(), any()))
        .thenThrow(new RuntimeException("FHIR down"));

    servlet.doPost(request, response);

    verify(iamProviderService).deleteUser("iam-id-1");
    assertSseContains("\"error\"");
  }

  @Test
  void doPost_unexpectedException_emitsGenericErrorMessage() throws Exception {
    givenCsv(HEADER + "\n,jdoe,John,Doe,jdoe@example.com,,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any()))
        .thenThrow(new RuntimeException("SELECT * FROM users WHERE name='jdoe'"));

    servlet.doPost(request, response);

    String output = responseBuffer.toString();
    assertSseContains("\"error\"");
    assertTrue(output.contains("unexpected error"), "Expected generic message but got: " + output);
    assertTrue(
        !output.contains("SELECT"), "Internal exception detail must not be exposed to the client");
  }

  @Test
  void doPost_firstRowFails_secondRowNotProcessed() throws Exception {
    givenCsv(
        HEADER
            + "\n,jdoe,John,Doe,jdoe@example.com,,,,,,,,"
            + "\n,jsmith,Jane,Smith,jsmith@example.com,,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any())).thenThrow(new RuntimeException("IAM down"));

    servlet.doPost(request, response);

    verify(iamProviderService, times(1)).createUser(any());
    assertSseContains("\"row\":1");
  }

  // -------------------------------------------------------------------------
  // Column order independence
  // -------------------------------------------------------------------------

  @Test
  void doPost_shuffledColumns_parsedCorrectly() throws Exception {
    givenCsv(
        "email,last_name,username,first_name,id,group,password,is_password_temp,dob,gender,national_id,phone,source_id"
            + "\njdoe@example.com,Doe,jdoe,John,,,,,,,,,");
    when(iamProviderService.listGroups()).thenReturn(List.of());
    when(iamProviderService.createUser(any())).thenReturn("iam-id-1");
    when(practitionerService.createPractitioner(any(), any())).thenReturn(new Practitioner());

    servlet.doPost(request, response);

    verify(iamProviderService)
        .createUser(
            argThat(
                u -> "jdoe".equals(u.getUsername()) && "jdoe@example.com".equals(u.getEmail())));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void givenCsv(String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    when(request.getPart("file")).thenReturn(filePart);
    when(filePart.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
  }

  private void assertSseContains(String fragment) {
    assertTrue(
        responseBuffer.toString().contains(fragment),
        "Expected SSE output to contain: " + fragment + "\nActual: " + responseBuffer);
  }

  private Practitioner practitionerWithIam(String iamUserId) {
    Practitioner p = new Practitioner();
    org.hl7.fhir.r4.model.Identifier id = new org.hl7.fhir.r4.model.Identifier();
    id.setSystem(PractitionerService.KEYCLOAK_IDENTIFIER_SYSTEM);
    id.setValue(iamUserId);
    p.addIdentifier(id);
    return p;
  }

  private IamGroupRepresentation groupRep(String id, String name) {
    IamGroupRepresentation rep = new IamGroupRepresentation();
    rep.setId(id);
    rep.setName(name);
    return rep;
  }
}
