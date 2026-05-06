package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PdfRepairAllocationTest {

  private static final int WARMUP_ITERATIONS = 50;

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();
  private MemorySegment corruptPdf;
  private Arena arena;

  @BeforeAll
  void setUp() throws IOException {
    asserter.verifyAllocationTrackingAvailable();
    arena = Arena.ofShared();
    corruptPdf = arena.allocateFrom(corruptPdfContent(), StandardCharsets.ISO_8859_1);
    
    // Disable logging to avoid noise
    java.util.logging.LogManager.getLogManager().reset();
    java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
  }

  @AfterAll
  void tearDown() {
    if (arena != null) {
      arena.close();
    }
  }

  static boolean pdfiumAvailable() {
    try {
      PdfiumLibrary.initialize();
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @Test
  @EnabledIf("pdfiumAvailable")
  public void repairDoesNotAllocateAfterWarmup() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
    
    // Warmup
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      out.reset();
      PdfSaver.repair(corruptPdf, out);
    }

    asserter.startRecording();
    out.reset();
    PdfSaver.repair(corruptPdf, out);
    
    asserter.assertNoAllocations(256);
    assertTrue(out.size() > 0, "Repair should produce output");
  }

  private static String corruptPdfContent() {
    // Minimal PDF with broken xref and missing trailer start
    return "%PDF-1.4\n"
        + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        + "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
        + "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] >>\nendobj\n"
        + "trailer\n<< /Root 1 0 R /Size 4 >>\n"
        + "%%EOF\n";
  }
}
