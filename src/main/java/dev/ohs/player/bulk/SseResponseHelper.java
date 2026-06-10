package dev.ohs.player.bulk;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class SseResponseHelper {

  public void configure(HttpServletResponse response) {
    response.setContentType(MediaType.SERVER_SENT_EVENTS);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    response.setHeader("X-Accel-Buffering", "no");
    response.setBufferSize(0);
  }

  public void emitProgress(PrintWriter writer, int processed, int total) {
    writer.write("data: {\"processed\":" + processed + ",\"total\":" + total + "}\n\n");
    writer.flush();
  }

  public void emitError(PrintWriter writer, String message, int row) {
    String escaped =
        message == null
            ? ""
            : message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    writer.write("data: {\"error\":\"" + escaped + "\",\"row\":" + row + "}\n\n");
    writer.flush();
  }
}
