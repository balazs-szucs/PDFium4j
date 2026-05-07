package org.grimmory.pdfium4j;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PdfRepairAllocationTest {

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();
  private MemorySegment corruptPdf;
  private Arena arena;

  /**
   * Allocation tolerance for JVM/JIT noise.
   */
  private static final long STEADY_STATE_TOLERANCE = 8192;

  @BeforeAll
  void setUp() throws IOException {
    asserter.verifyAllocationTrackingAvailable();
    arena = Arena.ofShared();

    Path corpusPdf = findCorpusPdf("gutenberg/1063_The Cask of Amontillado.pdf");
    byte[] data = Files.readAllBytes(corpusPdf);
    corruptPdf = arena.allocateFrom(JAVA_BYTE, data);

    // Disable logging to avoid noise
    LogManager.getLogManager().reset();
    Logger.getLogger("").setLevel(Level.OFF);
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
    // Pre-allocate a large enough buffer to avoid resizing during test (Amontillado is 367KB)
    ByteArrayOutputStream out = new ByteArrayOutputStream(1024 * 1024);

    // Warmup
    for (int i = 0; i < 100; i++) {
      out.reset();
      PdfSaver.repair(corruptPdf, out);
    }

    asserter.startRecording();
    out.reset();
    PdfSaver.repair(corruptPdf, out);

    asserter.assertNoAllocations(STEADY_STATE_TOLERANCE);
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
}
