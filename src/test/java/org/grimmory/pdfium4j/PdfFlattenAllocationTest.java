package org.grimmory.pdfium4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.grimmory.pdfium4j.util.AllocationTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PdfFlattenAllocationTest {

  private final NoAllocationAsserter asserter = new NoAllocationAsserter();
  private PdfDocument doc;

  /** Allocation tolerance allowing for input and output PDF byte arrays plus minor noise. */
  private static final long STEADY_STATE_TOLERANCE = 2500000;

  @BeforeAll
  void setUp() throws IOException {
    asserter.verifyAllocationTrackingAvailable();
    Path source = findCorpusPdf();
    doc = PdfDocument.open(source);

    // Disable logging to avoid noise
    LogManager.getLogManager().reset();
    Logger.getLogger("").setLevel(Level.OFF);

    // Aggressive Warmup
    warmup();
  }

  private void warmup() {
    FlattenConfig config = new FlattenConfig().mode(FlattenConfig.FlattenMode.LOGICAL);
    for (int i = 0; i < 50; i++) {
      byte[] res = doc.flattenAdvanced(config);
      assertTrue(res.length > 0);
    }
  }

  @AfterAll
  void tearDown() {
    if (doc != null) {
      doc.close();
    }
  }

  @Test
  void advancedLogicalFlattenDoesNotAllocateAfterWarmup() {
    FlattenConfig config = new FlattenConfig().mode(FlattenConfig.FlattenMode.LOGICAL);

    // Local warmup
    warmup();

    for (int i = 0; i < 10; i++) {
      asserter.startRecording();
      byte[] res = doc.flattenAdvanced(config);
      assertTrue(res.length > 0);
      asserter.assertNoAllocations(STEADY_STATE_TOLERANCE);
    }
  }

  private static Path findCorpusPdf() throws IOException {
    return AllocationTestUtils.getTestPdf(PdfFlattenAllocationTest.class);
  }
}
