package dev.ohs.player.bulk;

import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class CsvProcessor {

  public Path saveTempFile(Part filePart) throws IOException {
    Path tempPath = Path.of(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".csv");
    try (InputStream in = filePart.getInputStream()) {
      Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
    }
    return tempPath;
  }

  public int countDataRows(Path csvFile) throws IOException {
    try (var lines = Files.lines(csvFile, StandardCharsets.UTF_8)) {
      long count = lines.count();
      return count > 0 ? (int) (count - 1) : 0;
    }
  }

  public Map<String, Integer> buildHeaderIndex(String headerLine) {
    String[] parts = headerLine.split(",", -1);
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < parts.length; i++) {
      index.put(parts[i].trim(), i);
    }
    return index;
  }

  public @Nullable String getColumn(String[] columns, Map<String, Integer> index, String name) {
    Integer i = index.get(name);
    if (i == null || i >= columns.length) return null;
    String value = columns[i].trim();
    return value.isEmpty() ? null : value;
  }
}
