package dev.ohs.player.endpoints;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.ohs.player.iam.IamProviderException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for ServletResponseUtil. */
@ExtendWith(MockitoExtension.class)
class ServletResponseUtilTest {

  @Mock private HttpServletResponse mockResponse;

  private StringWriter stringWriter;
  private PrintWriter printWriter;

  @BeforeEach
  void setUp() {
    stringWriter = new StringWriter();
    printWriter = new PrintWriter(stringWriter);
  }

  @Test
  void testWriteResponse_Success() throws Exception {
    when(mockResponse.getWriter()).thenReturn(printWriter);

    ServletResponseUtil.writeResponse(mockResponse, 200, "application/json", "{\"status\":\"ok\"}");

    verify(mockResponse).setStatus(200);
    verify(mockResponse).setContentType("application/json");
    verify(mockResponse).setCharacterEncoding("UTF-8");
    assertEquals("{\"status\":\"ok\"}", stringWriter.toString());
  }

  @Test
  void testWriteResponse_NullBody() throws Exception {
    when(mockResponse.getWriter()).thenReturn(printWriter);

    ServletResponseUtil.writeResponse(mockResponse, 204, "application/json", null);

    verify(mockResponse).setStatus(204);
    verify(mockResponse).setContentType("application/json");
    assertEquals("", stringWriter.toString());
  }

  @Test
  void testWriteJsonError_StandardError() throws Exception {
    when(mockResponse.getWriter()).thenReturn(printWriter);

    ServletResponseUtil.writeJsonError(mockResponse, 400, "Bad request");

    verify(mockResponse).setStatus(400);
    verify(mockResponse).setContentType("application/json");

    String output = stringWriter.toString();
    assertTrue(output.contains("\"error\":\"Bad request\""));
    assertTrue(output.contains("\"status\":400"));
    assertTrue(output.contains("\"timestamp\""));
  }

  @Test
  void testWriteJsonError_SpecialCharacters() throws Exception {
    when(mockResponse.getWriter()).thenReturn(printWriter);

    ServletResponseUtil.writeJsonError(mockResponse, 500, "Error with \"quotes\" and \n newlines");

    verify(mockResponse).setStatus(500);

    String output = stringWriter.toString();
    // Jackson should properly escape special characters
    assertTrue(output.contains("\"error\":"));
    // Should be valid JSON (no unescaped quotes breaking the structure)
    assertFalse(output.contains("\"quotes\" and"));
  }

  @Test
  void testWriteJsonResponse_WithMap() throws Exception {
    when(mockResponse.getWriter()).thenReturn(printWriter);

    Map<String, Object> data = new HashMap<>();
    data.put("name", "John Doe");
    data.put("age", 30);
    data.put("active", true);

    ServletResponseUtil.writeJsonResponse(mockResponse, 200, data);

    verify(mockResponse).setStatus(200);
    verify(mockResponse).setContentType("application/json");

    String output = stringWriter.toString();
    assertTrue(output.contains("\"name\":\"John Doe\""));
    assertTrue(output.contains("\"age\":30"));
    assertTrue(output.contains("\"active\":true"));
  }

  @Test
  void testWriteJsonResponse_WithComplexObject() throws Exception {
    when(mockResponse.getWriter()).thenReturn(printWriter);

    TestObject testObj = new TestObject("test-id", "Test Name");

    ServletResponseUtil.writeJsonResponse(mockResponse, 201, testObj);

    verify(mockResponse).setStatus(201);

    String output = stringWriter.toString();
    assertTrue(output.contains("\"id\":\"test-id\""));
    assertTrue(output.contains("\"name\":\"Test Name\""));
  }

  @Test
  void testWriteJsonSuccess_DefaultsTo200() throws Exception {
    when(mockResponse.getWriter()).thenReturn(printWriter);

    Map<String, String> data = Map.of("result", "success");

    ServletResponseUtil.writeJsonSuccess(mockResponse, data);

    verify(mockResponse).setStatus(200);
    verify(mockResponse).setContentType("application/json");

    String output = stringWriter.toString();
    assertTrue(output.contains("\"result\":\"success\""));
  }

  @Test
  void testConstants() {
    assertEquals("application/json", ServletResponseUtil.CONTENT_TYPE_JSON);
    assertEquals("application/fhir+json", ServletResponseUtil.CONTENT_TYPE_FHIR_JSON);
    assertEquals("text/plain", ServletResponseUtil.CONTENT_TYPE_TEXT);
  }

  @Test
  void testGetObjectMapper_NotNull() {
    assertNotNull(ServletResponseUtil.getObjectMapper());
  }

  @Test
  void testGetObjectMapper_IsSingleton() {
    assertSame(ServletResponseUtil.getObjectMapper(), ServletResponseUtil.getObjectMapper());
  }

  // -------------------------------------------------------------------------
  // iamErrorStatus
  // -------------------------------------------------------------------------

  @Test
  void iamErrorStatus_IamProviderException_ReturnsItsStatusCode() {
    assertEquals(
        409, ServletResponseUtil.iamErrorStatus(new IamProviderException(409, "Conflict")));
  }

  @Test
  void iamErrorStatus_IamProviderException_PreservesAnyUpstreamStatus() {
    assertEquals(
        404, ServletResponseUtil.iamErrorStatus(new IamProviderException(404, "Not found")));
    assertEquals(
        422, ServletResponseUtil.iamErrorStatus(new IamProviderException(422, "Unprocessable")));
  }

  @Test
  void iamErrorStatus_GenericException_Returns502() {
    assertEquals(502, ServletResponseUtil.iamErrorStatus(new RuntimeException("boom")));
  }

  // Helper class for testing complex object serialization
  static class TestObject {
    private final String id;
    private final String name;

    public TestObject(String id, String name) {
      this.id = id;
      this.name = name;
    }

    public String getId() {
      return id;
    }

    public String getName() {
      return name;
    }
  }
}
