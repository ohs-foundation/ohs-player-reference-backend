package dev.ohs.player.endpoints;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for standardized HTTP servlet response writing. Provides consistent error handling,
 * JSON serialization, and response formatting across servlet endpoints.
 */
public final class ServletResponseUtil {

  private static final Logger logger = LoggerFactory.getLogger(ServletResponseUtil.class);
  private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

  public static final String CONTENT_TYPE_JSON = "application/json";
  public static final String CONTENT_TYPE_FHIR_JSON = "application/fhir+json";
  public static final String CONTENT_TYPE_TEXT = "text/plain";

  private ServletResponseUtil() {
    // Utility class - prevent instantiation
  }

  /** Creates and configures the shared ObjectMapper instance. */
  private static ObjectMapper createObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    mapper.findAndRegisterModules();
    return mapper;
  }

  /**
   * Writes an HTTP response with the specified status, content type, and body.
   *
   * @param response the HttpServletResponse to write to
   * @param status HTTP status code
   * @param contentType MIME type of the response
   * @param body response body content (may be null)
   * @throws IOException if writing to the response fails
   */
  public static void writeResponse(
      HttpServletResponse response, int status, String contentType, String body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(contentType);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(body != null ? body : "");
  }

  /**
   * Writes a standardized JSON error response. Creates a JSON object with error message and
   * timestamp.
   *
   * @param response the HttpServletResponse to write to
   * @param status HTTP status code
   * @param errorMessage error message to include
   * @throws IOException if writing to the response fails
   */
  public static void writeJsonError(HttpServletResponse response, int status, String errorMessage)
      throws IOException {
    Map<String, Object> errorBody = new HashMap<>();
    errorBody.put("error", errorMessage);
    errorBody.put("timestamp", Instant.now().toString());
    errorBody.put("status", status);

    writeJsonResponse(response, status, errorBody);
  }

  /**
   * Writes a JSON response by serializing the provided object.
   *
   * @param response the HttpServletResponse to write to
   * @param status HTTP status code
   * @param jsonObject object to serialize to JSON
   * @throws IOException if serialization or writing fails
   */
  public static void writeJsonResponse(HttpServletResponse response, int status, Object jsonObject)
      throws IOException {
    try {
      String json = OBJECT_MAPPER.writeValueAsString(jsonObject);
      writeResponse(response, status, CONTENT_TYPE_JSON, json);
    } catch (Exception e) {
      logger.error("Failed to serialize response object to JSON", e);
      // Fallback to simple error response
      writeResponse(
          response,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          CONTENT_TYPE_JSON,
          "{\"error\":\"Internal serialization error\"}");
    }
  }

  /**
   * Writes a successful JSON response with HTTP 200 status.
   *
   * @param response the HttpServletResponse to write to
   * @param jsonObject object to serialize to JSON
   * @throws IOException if serialization or writing fails
   */
  public static void writeJsonSuccess(HttpServletResponse response, Object jsonObject)
      throws IOException {
    writeJsonResponse(response, HttpServletResponse.SC_OK, jsonObject);
  }

  /**
   * Gets the shared ObjectMapper instance for custom serialization needs.
   *
   * @return configured ObjectMapper instance
   */
  public static ObjectMapper getObjectMapper() {
    return OBJECT_MAPPER;
  }
}
