package dev.ohs.player.bulk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SseResponseHelperTest {

  private SseResponseHelper helper;
  private StringWriter buffer;
  private PrintWriter writer;

  @BeforeEach
  void setUp() {
    helper = new SseResponseHelper();
    buffer = new StringWriter();
    writer = new PrintWriter(buffer);
  }

  @Test
  void configure_setsCorrectHeaders() {
    HttpServletResponse response = mock(HttpServletResponse.class);
    helper.configure(response);
    verify(response).setContentType("text/event-stream");
    verify(response).setCharacterEncoding("UTF-8");
    verify(response).setHeader("Cache-Control", "no-cache");
    verify(response).setHeader("X-Accel-Buffering", "no");
    verify(response).setBufferSize(0);
  }

  @Test
  void emitProgress_writesCorrectEvent() {
    helper.emitProgress(writer, 5, 100);
    assertEquals("data: {\"processed\":5,\"total\":100}\n\n", buffer.toString());
  }

  @Test
  void emitError_writesCorrectEvent() {
    helper.emitError(writer, "Group not found: clinicians", 3);
    assertEquals(
        "data: {\"error\":\"Group not found: clinicians\",\"row\":3}\n\n", buffer.toString());
  }

  @Test
  void emitError_escapesQuotesInMessage() {
    helper.emitError(writer, "field \"username\" is required", 1);
    assertTrue(buffer.toString().contains("\\\"username\\\""));
  }

  @Test
  void emitError_nullMessage_writesEmptyString() {
    helper.emitError(writer, null, 1);
    assertTrue(buffer.toString().contains("\"error\":\"\""));
  }
}
