package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import org.grimmory.pdfium4j.model.PageBox;
import org.grimmory.pdfium4j.util.AllocationTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfEditAllocationTest {

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();
  private PdfDocument doc;

  private static final long STEADY_STATE_TOLERANCE = 65536; // 64 KB

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

    // Warmup JIT for box, redact, flatten, and import
    try (PdfPage page = doc.page(0)) {
      for (int i = 0; i < 200; i++) {
        page.getMediaBox();
        page.setCropBox(10.0f, 10.0f, 300.0f, 400.0f);
        page.getCropBox();
        page.redact(15.0f, 15.0f, 40.0f, 40.0f);
        page.flatten(true);
        page.flattenToImage(0.1f);
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
  void cropBoxOperationsAreEfficient() {
    try (PdfPage page = doc.page(0)) {
      asserter.startRecording();
      page.setCropBox(10.0f, 10.0f, 300.0f, 400.0f);
      PageBox cropBox = page.getCropBox();
      assertNotNull(cropBox);
      asserter.assertNoAllocations(STEADY_STATE_TOLERANCE);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageRedactionIsEfficient() {
    try (PdfPage page = doc.page(0)) {
      asserter.startRecording();
      page.redact(10.0f, 10.0f, 50.0f, 50.0f);
      asserter.assertNoAllocations(STEADY_STATE_TOLERANCE);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageFlatteningIsEfficient() {
    try (PdfPage page = doc.page(0)) {
      asserter.startRecording();
      boolean ok = page.flatten(true);
      assertTrue(ok);
      asserter.assertNoAllocations(
          STEADY_STATE_TOLERANCE * 4); // Allow minor FFM parameter mappings
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageFlattenToImageIsEfficient() {
    // Create a temporary document so we don't destroy the shared one
    try (PdfDocument tempDoc = PdfDocument.open(findCorpusPdf());
        PdfPage tempPage = tempDoc.page(0)) {
      asserter.startRecording();
      tempPage.flattenToImage(1.0f);
      asserter.assertNoAllocations(STEADY_STATE_TOLERANCE * 8);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void pageRenderingIsEfficient() {
    try (PdfPage page = doc.page(0);
        java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
      int w = (int) page.size().width();
      int h = (int) page.size().height();
      int stride = w * 4;
      java.lang.foreign.MemorySegment dest = arena.allocate(h * stride);
      asserter.startRecording();
      page.renderTo(dest, w, h, stride, 0, 0xFFFFFFFF);
      asserter.assertNoAllocations(STEADY_STATE_TOLERANCE * 4);
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  void importPagesIsEfficient() {
    try (PdfDocument destDoc = PdfDocument.create()) {
      asserter.startRecording();
      destDoc.importAllPages(doc);
      assertTrue(destDoc.pageCount() > 0);
      asserter.assertNoAllocations(
          STEADY_STATE_TOLERANCE * 16); // Allow minor heap allocations for new doc/arena wrappers
    }
  }

  private static Path findCorpusPdf() {
    try {
      return AllocationTestUtils.getTestPdf(PdfEditAllocationTest.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to find test PDF", e);
    }
  }
}
