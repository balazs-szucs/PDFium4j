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

  private static final int WARMUP_ITERATIONS = 400;

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();
  private MemorySegment corruptPdf;
  private Arena arena;

  @BeforeAll
  void setUp() throws IOException {
    asserter.verifyAllocationTrackingAvailable();
    arena = Arena.ofShared();
    
    Path corpusPdf = findCorpusPdf("gutenberg/996_Don Quixote.pdf");
    byte[] data = Files.readAllBytes(corpusPdf);
    corruptPdf = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, data);
    
    // Disable logging to avoid noise
    java.util.logging.LogManager.getLogManager().reset();
    java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
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
    for (int i = 0; i < 5000; i++) {
      out.reset();
      PdfSaver.repair(corruptPdf, out);
    }

    asserter.startRecording();
    out.reset();
    PdfSaver.repair(corruptPdf, out);
    
    asserter.assertNoAllocations(0);
    assertTrue(out.size() > 0, "Repair should produce output");
  }

  @AfterAll
  void tearDown() {
    if (arena != null) {
      arena.close();
    }
  }

  private Path findCorpusPdf(String relativePath) {
    Path projectRoot = Path.of("").toAbsolutePath();
    Path corpusPdf = projectRoot.resolve("corpus").resolve(relativePath);
    if (!Files.exists(corpusPdf)) {
        // Fallback for different test execution environments
        corpusPdf = projectRoot.resolve("..").resolve("corpus").resolve(relativePath);
    }
    if (!Files.exists(corpusPdf)) {
        throw new IllegalStateException("Corpus PDF not found at: " + corpusPdf);
    }
    return corpusPdf;
  }

  private String relativeRelativePath(String path) {
      return path; // Simple for now
  }
}
