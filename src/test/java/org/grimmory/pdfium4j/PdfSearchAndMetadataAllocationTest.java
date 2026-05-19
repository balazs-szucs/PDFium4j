package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.grimmory.pdfium4j.model.MetadataTag;
import org.grimmory.pdfium4j.search.IntCursor;
import org.grimmory.pdfium4j.util.AllocationTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfSearchAndMetadataAllocationTest {

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();
  private PdfDocument doc;
  private static final long STEADY_STATE_TOLERANCE = 256 * 1024;

  private final Utf8Consumer consumer =
      (addr, len) -> {
        if (addr == 0) {
          throw new AssertionError("Address must not be zero");
        }
      };

  static boolean pdfiumAvailable() {
    try {
      PdfiumLibrary.initialize();
      return true;
    } catch (Throwable _) {
      return false;
    }
  }

  @BeforeAll
  void setUp() {
    asserter.verifyAllocationTrackingAvailable();
    Path source = findCorpusPdf();
    doc = PdfDocument.open(source);
    doc.indexText();

    // Extensive JIT warmup using the exact same lambda and execution paths
    for (int i = 0; i < 4000; i++) {
      doc.metadataUtf8(MetadataTag.TITLE, consumer);
      IntCursor cursor = doc.textIndex.search("the");
      while (cursor.hasNext()) {
        cursor.nextInt();
      }
    }
  }

  @AfterAll
  void tearDown() {
    if (doc != null) {
      doc.close();
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void metadataUtf8DoesNotAllocate() {
    asserter.startRecording();
    doc.metadataUtf8(MetadataTag.TITLE, consumer);
    asserter.assertNoAllocations(STEADY_STATE_TOLERANCE);
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void searchWithTrigramIndexDoesNotAllocate() {
    asserter.startRecording();
    IntCursor cursor = doc.textIndex.search("the");
    assertTrue(cursor.hasNext());
    while (cursor.hasNext()) {
      cursor.nextInt();
    }
    asserter.assertNoAllocations(STEADY_STATE_TOLERANCE);
  }

  private static Path findCorpusPdf() {
    try {
      return AllocationTestUtils.getTestPdf(PdfSearchAndMetadataAllocationTest.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to find test PDF", e);
    }
  }
}
