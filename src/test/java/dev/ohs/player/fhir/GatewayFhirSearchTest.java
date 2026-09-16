package dev.ohs.player.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import com.google.fhir.gateway.HttpFhirClient;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpResponse;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GatewayFhirSearchTest {

  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4Cached();

  @Mock private HttpFhirClient client;

  private GatewayFhirSearch search() {
    return new GatewayFhirSearch(client, FHIR_CONTEXT);
  }

  @Test
  void search_AppendsJsonFormatToPreEncodedUrl() throws IOException {
    String url =
        "Patient?organization="
            + URLEncoder.encode("Organization/a", StandardCharsets.UTF_8)
            + "&_count=1";
    when(client.getResource(anyString())).thenReturn(ok(new Bundle()));

    search().search(url);

    verify(client).getResource("Patient?organization=Organization%2Fa&_count=1&_format=json");
  }

  @Test
  void search_UrlWithoutQuery_AddsQuery() throws IOException {
    when(client.getResource(anyString())).thenReturn(ok(new Bundle()));

    search().search("Patient");

    verify(client).getResource("Patient?_format=json");
  }

  @Test
  void search_ErrorStatus_ThrowsUpstreamException() throws IOException {
    when(client.getResource(anyString())).thenReturn(response(403, "{}"));

    assertThrows(GatewayFhirSearch.UpstreamException.class, () -> search().search("Patient?x=1"));
  }

  @Test
  void search_ServerErrorStatus_ThrowsUpstreamException() throws IOException {
    when(client.getResource(anyString())).thenReturn(response(503, "unavailable"));

    assertThrows(GatewayFhirSearch.UpstreamException.class, () -> search().search("Patient?x=1"));
  }

  @Test
  void search_IoFailure_ThrowsUpstreamException() throws IOException {
    when(client.getResource(anyString())).thenThrow(new IOException("connection reset"));

    assertThrows(GatewayFhirSearch.UpstreamException.class, () -> search().search("Patient?x=1"));
  }

  @Test
  void search_BodyIsNotBundle_ThrowsUpstreamException() throws IOException {
    when(client.getResource(anyString())).thenReturn(ok(new Patient()));

    assertThrows(GatewayFhirSearch.UpstreamException.class, () -> search().search("Patient?x=1"));
  }

  @Test
  void search_BodyIsNotJson_ThrowsUpstreamException() throws IOException {
    when(client.getResource(anyString())).thenReturn(response(200, "<html/>"));

    assertThrows(GatewayFhirSearch.UpstreamException.class, () -> search().search("Patient?x=1"));
  }

  @Test
  void searchAll_FollowsNextLinkAsRelativeQuery() throws IOException {
    Bundle first =
        page("p1")
            .setLink(List.of(nextLink("http://fhir:8080/fhir?_getpages=abc&_getpagesoffset=1")));
    when(client.getResource("Patient?x=1&_format=json")).thenReturn(ok(first));
    when(client.getResource("?_getpages=abc&_getpagesoffset=1&_format=json"))
        .thenReturn(ok(page("p2")));

    List<Resource> resources = search().searchAll("Patient?x=1");

    assertEquals(2, resources.size());
    assertEquals("p2", resources.get(1).getIdElement().getIdPart());
  }

  @Test
  void searchAll_MorePagesThanLimit_Throws() throws IOException {
    Bundle endless = page("p").setLink(List.of(nextLink("http://fhir/fhir?_getpages=abc")));
    when(client.getResource(anyString())).thenAnswer(invocation -> ok(endless));

    assertThrows(
        GatewayFhirSearch.UpstreamException.class, () -> search().searchAll("Patient?x=1"));
    verify(client, times(GatewayFhirSearch.MAX_PAGES)).getResource(anyString());
  }

  private static Bundle page(String patientId) {
    Bundle bundle = new Bundle();
    bundle.addEntry().setResource(new Patient().setId(patientId));
    return bundle;
  }

  private static Bundle.BundleLinkComponent nextLink(String url) {
    return new Bundle.BundleLinkComponent().setRelation(Bundle.LINK_NEXT).setUrl(url);
  }

  private static HttpResponse ok(Resource resource) {
    return response(200, FHIR_CONTEXT.newJsonParser().encodeResourceToString(resource));
  }

  private static HttpResponse response(int status, String body) {
    BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, status, "status");
    response.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
    return response;
  }
}
