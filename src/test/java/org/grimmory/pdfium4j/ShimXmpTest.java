package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.grimmory.pdfium4j.model.MetadataTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ShimXmpTest {

  private static final Path SAMPLE_PDF = Path.of("src/test/resources/minimal.pdf");

  @BeforeAll
  static void setup() {
    PdfiumLibrary.initialize();
  }

  /*
  @Test
  void testCustomXmpRoundtrip() {
    try (PdfDocument doc = PdfDocument.open(SAMPLE_PDF)) {
      String ns = "http://pdfium4j.org/test/1.0/";
      String prefix = "p4j";
      String key = "testField";
      String value = "Hello Shim!";

      doc.setCustomXmpMetadata(ns, prefix, key, value);

      Optional<String> result = doc.customXmpMetadata(ns, key);
      assertTrue(result.isPresent());
      assertEquals(value, result.get());
    }
  }
  */

  @Test
  void testStandardMetadataUtf8() {
    try (PdfDocument doc = PdfDocument.open(SAMPLE_PDF)) {
      String title = "UTF-8 Title: ☕";
      doc.setMetadata(MetadataTag.TITLE, title);

      assertEquals(title, doc.metadata(MetadataTag.TITLE).orElse(""));
    }
  }

  /*
  @Test
  void testCustomXmpBagRoundtrip() {
    try (PdfDocument doc = PdfDocument.open(SAMPLE_PDF)) {
      String ns = "http://pdfium4j.org/test/1.0/";
      String prefix = "p4j";
      String key = "tags";
      List<String> values = List.of("tag1", "tag2", "tag3");

      doc.setCustomXmpMetadataList(ns, prefix, key, values);

      List<String> result = doc.customXmpMetadataList(ns, key);
      assertEquals(values.size(), result.size());
      assertTrue(result.containsAll(values));
    }
  }
  */

}
