package dev.ohs.player.bulk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvProcessorTest {

  private CsvProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new CsvProcessor();
  }

  // -------------------------------------------------------------------------
  // buildHeaderIndex
  // -------------------------------------------------------------------------

  @Test
  void buildHeaderIndex_standardOrder_mapsCorrectly() {
    Map<String, Integer> index = processor.buildHeaderIndex("username,email,first_name");
    assertEquals(0, index.get("username"));
    assertEquals(1, index.get("email"));
    assertEquals(2, index.get("first_name"));
  }

  @Test
  void buildHeaderIndex_shuffledOrder_mapsCorrectly() {
    Map<String, Integer> index = processor.buildHeaderIndex("phone,id,username,email");
    assertEquals(0, index.get("phone"));
    assertEquals(1, index.get("id"));
    assertEquals(2, index.get("username"));
    assertEquals(3, index.get("email"));
  }

  @Test
  void buildHeaderIndex_trimsWhitespace() {
    Map<String, Integer> index = processor.buildHeaderIndex(" username , email ");
    assertEquals(0, index.get("username"));
    assertEquals(1, index.get("email"));
  }

  // -------------------------------------------------------------------------
  // getColumn
  // -------------------------------------------------------------------------

  @Test
  void getColumn_presentColumn_returnsValue() {
    Map<String, Integer> index = processor.buildHeaderIndex("username,email");
    String[] row = {"jdoe", "jdoe@example.com"};
    assertEquals("jdoe", processor.getColumn(row, index, "username"));
    assertEquals("jdoe@example.com", processor.getColumn(row, index, "email"));
  }

  @Test
  void getColumn_absentColumnName_returnsNull() {
    Map<String, Integer> index = processor.buildHeaderIndex("username");
    String[] row = {"jdoe"};
    assertNull(processor.getColumn(row, index, "phone"));
  }

  @Test
  void getColumn_indexOutOfRange_returnsNull() {
    Map<String, Integer> index = processor.buildHeaderIndex("username,email,phone");
    String[] row = {"jdoe"}; // only one value, index 1 and 2 out of range
    assertNull(processor.getColumn(row, index, "email"));
    assertNull(processor.getColumn(row, index, "phone"));
  }

  @Test
  void getColumn_blankValue_returnsNull() {
    Map<String, Integer> index = processor.buildHeaderIndex("username,email");
    String[] row = {"jdoe", "   "};
    assertNull(processor.getColumn(row, index, "email"));
  }

  @Test
  void getColumn_trimsValue() {
    Map<String, Integer> index = processor.buildHeaderIndex("username");
    String[] row = {"  jdoe  "};
    assertEquals("jdoe", processor.getColumn(row, index, "username"));
  }

  // -------------------------------------------------------------------------
  // countDataRows
  // -------------------------------------------------------------------------

  @Test
  void countDataRows_emptyFile_returnsZero(@TempDir Path tempDir) throws IOException {
    Path csv = tempDir.resolve("empty.csv");
    Files.writeString(csv, "", StandardCharsets.UTF_8);
    assertEquals(0, processor.countDataRows(csv));
  }

  @Test
  void countDataRows_headerOnly_returnsZero(@TempDir Path tempDir) throws IOException {
    Path csv = tempDir.resolve("header.csv");
    Files.writeString(csv, "username,email\n", StandardCharsets.UTF_8);
    assertEquals(0, processor.countDataRows(csv));
  }

  @Test
  void countDataRows_twoDataRows_returnsTwo(@TempDir Path tempDir) throws IOException {
    Path csv = tempDir.resolve("data.csv");
    Files.writeString(
        csv,
        "username,email\njdoe,jdoe@example.com\njsmith,jsmith@example.com\n",
        StandardCharsets.UTF_8);
    assertEquals(2, processor.countDataRows(csv));
  }
}
