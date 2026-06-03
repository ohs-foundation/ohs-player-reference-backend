package dev.ohs.player.endpoints;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import dev.ohs.player.fhir.PractitionerDetail;
import dev.ohs.player.fhir.PractitionerDetailService;
import dev.ohs.player.fhir.PractitionerRoleDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PractitionerDetailsServletTest {

  @Mock private PractitionerDetailService practitionerDetailService;
  @Mock private FhirContext fhirContext;
  @Mock private IParser fhirParser;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private PractitionerDetailsServlet servlet;
  private StringWriter stringWriter;

  private static final String IAM_ID = "keycloak-uuid-123";
  private static final String PRACTITIONER_ID = "p1";

  @BeforeEach
  void setUp() throws Exception {
    servlet = new PractitionerDetailsServlet(practitionerDetailService, fhirContext);
    stringWriter = new StringWriter();
    lenient().when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));
    lenient().when(fhirContext.newJsonParser()).thenReturn(fhirParser);
    lenient()
        .when(fhirParser.encodeResourceToString(any()))
        .thenReturn("{\"resourceType\":\"Practitioner\"}");
  }

  // -------------------------------------------------------------------------
  // Validation — missing required params
  // -------------------------------------------------------------------------

  @Test
  void doGet_NeitherIamIdNorPractitionerId_Returns400() throws Exception {
    when(request.getParameter("iam-id")).thenReturn(null);
    when(request.getParameter("practitioner-id")).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(stringWriter.toString().contains("iam-id"));
    verifyNoInteractions(practitionerDetailService);
  }

  @Test
  void doGet_BlankIamId_BlankPractitionerId_Returns400() throws Exception {
    when(request.getParameter("iam-id")).thenReturn("  ");
    when(request.getParameter("practitioner-id")).thenReturn("  ");

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verifyNoInteractions(practitionerDetailService);
  }

  // -------------------------------------------------------------------------
  // practitioner-id path (Step 2 only)
  // -------------------------------------------------------------------------

  @Test
  void doGet_PractitionerIdProvided_SkipsResolveStep_Returns200() throws Exception {
    givenPractitionerIdParam(PRACTITIONER_ID);
    when(practitionerDetailService.fetchPractitionerDetail(eq(PRACTITIONER_ID), isNull(), isNull()))
        .thenReturn(buildDetail());

    servlet.doGet(request, response);

    verify(practitionerDetailService, never()).resolvePractitionerIdFromIamId(any());
    verify(practitionerDetailService).fetchPractitionerDetail(PRACTITIONER_ID, null, null);
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doGet_PractitionerIdProvided_NotFound_Returns404() throws Exception {
    givenPractitionerIdParam(PRACTITIONER_ID);
    when(practitionerDetailService.fetchPractitionerDetail(any(), any(), any())).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
  }

  @Test
  void doGet_PractitionerIdProvided_FhirFails_Returns502() throws Exception {
    givenPractitionerIdParam(PRACTITIONER_ID);
    when(practitionerDetailService.fetchPractitionerDetail(any(), any(), any()))
        .thenThrow(new RuntimeException("FHIR down"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  @Test
  void doGet_PractitionerIdWithOrganisationFilter_PassesFilterToService() throws Exception {
    givenPractitionerIdParam(PRACTITIONER_ID);
    when(request.getParameter("organisation-id")).thenReturn("org-1");
    when(request.getParameter("location-id")).thenReturn(null);
    when(practitionerDetailService.fetchPractitionerDetail(
            eq(PRACTITIONER_ID), eq("org-1"), isNull()))
        .thenReturn(buildDetail());

    servlet.doGet(request, response);

    verify(practitionerDetailService).fetchPractitionerDetail(PRACTITIONER_ID, "org-1", null);
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doGet_PractitionerIdWithLocationFilter_PassesFilterToService() throws Exception {
    givenPractitionerIdParam(PRACTITIONER_ID);
    when(request.getParameter("organisation-id")).thenReturn(null);
    when(request.getParameter("location-id")).thenReturn("loc-1");
    when(practitionerDetailService.fetchPractitionerDetail(
            eq(PRACTITIONER_ID), isNull(), eq("loc-1")))
        .thenReturn(buildDetail());

    servlet.doGet(request, response);

    verify(practitionerDetailService).fetchPractitionerDetail(PRACTITIONER_ID, null, "loc-1");
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  // -------------------------------------------------------------------------
  // iam-id path (Step 1 + Step 2)
  // -------------------------------------------------------------------------

  @Test
  void doGet_IamIdProvided_ResolvesAndFetchesDetail_Returns200() throws Exception {
    givenIamIdParam(IAM_ID);
    when(practitionerDetailService.resolvePractitionerIdFromIamId(IAM_ID))
        .thenReturn(PRACTITIONER_ID);
    when(practitionerDetailService.fetchPractitionerDetail(eq(PRACTITIONER_ID), isNull(), isNull()))
        .thenReturn(buildDetail());

    servlet.doGet(request, response);

    verify(practitionerDetailService).resolvePractitionerIdFromIamId(IAM_ID);
    verify(practitionerDetailService).fetchPractitionerDetail(PRACTITIONER_ID, null, null);
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  void doGet_IamIdNotFound_Returns404_NoFetchCall() throws Exception {
    givenIamIdParam(IAM_ID);
    when(practitionerDetailService.resolvePractitionerIdFromIamId(IAM_ID)).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    verify(practitionerDetailService, never()).fetchPractitionerDetail(any(), any(), any());
  }

  @Test
  void doGet_IamIdResolveFails_Returns502_NoFetchCall() throws Exception {
    givenIamIdParam(IAM_ID);
    when(practitionerDetailService.resolvePractitionerIdFromIamId(IAM_ID))
        .thenThrow(new RuntimeException("FHIR down"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
    verify(practitionerDetailService, never()).fetchPractitionerDetail(any(), any(), any());
  }

  @Test
  void doGet_IamIdResolvedButDetailNotFound_Returns404() throws Exception {
    givenIamIdParam(IAM_ID);
    when(practitionerDetailService.resolvePractitionerIdFromIamId(IAM_ID))
        .thenReturn(PRACTITIONER_ID);
    when(practitionerDetailService.fetchPractitionerDetail(any(), any(), any())).thenReturn(null);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
  }

  @Test
  void doGet_IamIdResolvedButFetchFails_Returns502() throws Exception {
    givenIamIdParam(IAM_ID);
    when(practitionerDetailService.resolvePractitionerIdFromIamId(IAM_ID))
        .thenReturn(PRACTITIONER_ID);
    when(practitionerDetailService.fetchPractitionerDetail(any(), any(), any()))
        .thenThrow(new RuntimeException("FHIR down"));

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
  }

  // -------------------------------------------------------------------------
  // Response structure
  // -------------------------------------------------------------------------

  @Test
  void doGet_Success_ResponseContainsPractitionerAndRolesKeys() throws Exception {
    givenPractitionerIdParam(PRACTITIONER_ID);
    when(practitionerDetailService.fetchPractitionerDetail(any(), any(), any()))
        .thenReturn(buildDetail());

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response).setContentType(ServletResponseUtil.CONTENT_TYPE_JSON);
    String body = stringWriter.toString();
    assertTrue(body.contains("\"practitioner\""));
    assertTrue(body.contains("\"practitionerRoles\""));
  }

  @Test
  void doGet_Success_DetailWithNullOrganization_ResponseHasNullOrganization() throws Exception {
    givenPractitionerIdParam(PRACTITIONER_ID);
    PractitionerDetail detail = new PractitionerDetail();
    detail.setPractitioner(new Practitioner());
    PractitionerRoleDetail rd = new PractitionerRoleDetail();
    rd.setPractitionerRole(new PractitionerRole());
    rd.setOrganization(null);
    rd.setLocations(List.of());
    rd.setCareTeams(List.of());
    detail.setPractitionerRoles(List.of(rd));
    when(practitionerDetailService.fetchPractitionerDetail(any(), any(), any())).thenReturn(detail);

    servlet.doGet(request, response);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    assertTrue(stringWriter.toString().contains("\"organization\":null"));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void givenPractitionerIdParam(String practitionerId) {
    when(request.getParameter("iam-id")).thenReturn(null);
    when(request.getParameter("practitioner-id")).thenReturn(practitionerId);
    lenient().when(request.getParameter("organisation-id")).thenReturn(null);
    lenient().when(request.getParameter("location-id")).thenReturn(null);
  }

  private void givenIamIdParam(String iamId) {
    when(request.getParameter("iam-id")).thenReturn(iamId);
    lenient().when(request.getParameter("practitioner-id")).thenReturn(null);
    lenient().when(request.getParameter("organisation-id")).thenReturn(null);
    lenient().when(request.getParameter("location-id")).thenReturn(null);
  }

  private PractitionerDetail buildDetail() {
    PractitionerDetail detail = new PractitionerDetail();
    detail.setPractitioner(new Practitioner());
    PractitionerRoleDetail rd = new PractitionerRoleDetail();
    rd.setPractitionerRole(new PractitionerRole());
    rd.setOrganization(new Organization());
    rd.setLocations(List.of());
    rd.setCareTeams(List.of());
    detail.setPractitionerRoles(List.of(rd));
    return detail;
  }
}
